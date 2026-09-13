"""Run opt-in SQL tests using an extracted Windows server, without installing a service."""

import argparse
import datetime
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import uuid
import shutil
import xml.etree.ElementTree as ET

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

project = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server', required=True, type=Path, help='Root of an extracted MySQL or MariaDB Windows ZIP distribution')
parser.add_argument('--engine', choices=('mysql', 'mariadb'), default='mysql')
parser.add_argument('--client', type=Path, help='MySQL mysql.exe for inspection and shutdown, required with MariaDB')
parser.add_argument('--platforms', nargs='*', choices=('paper', 'velocity'), default=[])
parser.add_argument('--modules', type=Path, help='Local Node dependency directory, required for Paper checks')
args = parser.parse_args()
if 'paper' in args.platforms and args.modules is None:
    parser.error('Paper checks require --modules')
if args.engine == 'mariadb' and args.client is None:
    parser.error('MariaDB checks require --client pointing to a MySQL mysql.exe')
base = args.server.resolve()
server_binary = base / 'bin' / ('mysqld.exe' if args.engine == 'mysql' else 'mariadbd.exe')
assert server_binary.is_file(), 'Provide the extracted database server directory'
client_binary = args.client.resolve() if args.client else base / 'bin/mysql.exe'
assert client_binary.is_file() and (client_binary.parent / 'mysqladmin.exe').is_file()
assert (client_binary.parent / 'mysqldump.exe').is_file(), 'The MySQL client directory must include mysqldump.exe'
fixture = project / '.run' / (args.engine + '-test-' + uuid.uuid4().hex)
fixture.mkdir()
data = fixture / 'data'
data.mkdir()
bin_dir = base / 'bin'
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
if os.name != 'nt':
    raise RuntimeError('This portable server runner currently supports Windows only')
now = datetime.datetime.now(datetime.timezone.utc)
ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'ConsentGate disposable test CA')])
ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(ca_key.public_key())
      .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(minutes=5))
      .not_valid_after(now + datetime.timedelta(days=2)).add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
      .sign(ca_key, hashes.SHA256()))
server_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
server = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')]))
          .issuer_name(ca_name).public_key(server_key.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(now + datetime.timedelta(days=2))
          .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]), False)
          .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False).sign(ca_key, hashes.SHA256()))
(fixture / 'ca.pem').write_bytes(ca.public_bytes(serialization.Encoding.PEM))
other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
other_ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(other_key.public_key())
           .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(minutes=5))
           .not_valid_after(now + datetime.timedelta(days=2)).add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
           .sign(other_key, hashes.SHA256()))
(fixture / 'untrusted-ca.pem').write_bytes(other_ca.public_bytes(serialization.Encoding.PEM))
(fixture / 'server.pem').write_bytes(server.public_bytes(serialization.Encoding.PEM))
(fixture / 'server-key.pem').write_bytes(server_key.private_bytes(serialization.Encoding.PEM,
    serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
with socket.socket() as reserved:
    reserved.bind(('127.0.0.1', 0))
    port = reserved.getsockname()[1]
database = 'consentgate_test_' + uuid.uuid4().hex[:16]
password = secrets.token_hex(24)
root_password = secrets.token_hex(24)
client_file = fixture / 'root-client.ini'
server_process = None
with (fixture / 'initialize.log').open('wb') as output:
    initialize = ([str(server_binary), '--no-defaults', '--initialize-insecure', '--basedir=' + str(base), '--datadir=' + str(data)]
                  if args.engine == 'mysql' else [str(bin_dir / 'mariadb-install-db.exe'), '--datadir=' + str(data), '--port=' + str(port)])
    subprocess.run(initialize, check=True, stdout=output,
                   stderr=subprocess.STDOUT, timeout=120, creationflags=flags)
print('Initialized disposable ' + args.engine + ' data.', flush=True)
log = (fixture / 'server.log').open('wb')
try:
    server_args = [str(server_binary), '--no-defaults', '--console',
        '--basedir=' + str(base), '--datadir=' + str(data), '--bind-address=127.0.0.1', '--port=' + str(port),
        '--skip-log-bin', '--local-infile=OFF', '--ssl-ca=' + str(fixture / 'ca.pem'),
        '--ssl-cert=' + str(fixture / 'server.pem'), '--ssl-key=' + str(fixture / 'server-key.pem')]
    if args.engine == 'mysql':
        server_args.append('--mysqlx=OFF')
    server_process = subprocess.Popen(server_args, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if server_process.poll() is not None:
            raise RuntimeError('Database exited; inspect ' + str(fixture / 'server.log'))
        with socket.socket() as check:
            if check.connect_ex(('127.0.0.1', port)) == 0:
                break
        time.sleep(0.2)
    else:
        raise TimeoutError('Database did not start')
    sql = (f'CREATE DATABASE {database} CHARACTER SET utf8mb4;'
           f"CREATE USER 'consentgate_test'@'localhost' IDENTIFIED BY '{password}';"
           f"GRANT ALL ON {database}.* TO 'consentgate_test'@'localhost';"
           f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{root_password}';")
    subprocess.run([str(client_binary), '--no-defaults', '--protocol=TCP', '--host=localhost',
                    '--port=' + str(port), '--user=root', '--ssl-mode=VERIFY_IDENTITY',
                    '--ssl-ca=' + str(fixture / 'ca.pem')], input=sql, text=True, check=True,
                   capture_output=True, timeout=15, creationflags=flags)
    client_file.write_text(f'[client]\nhost=localhost\nport={port}\nuser=root\npassword={root_password}\n'
                          f'ssl-mode=VERIFY_IDENTITY\nssl-ca={(fixture / "ca.pem").as_posix()}\n')
    settings = dict(host='localhost', port=port, database=database, username='consentgate_test',
                    password=password, ca=str(fixture / 'ca.pem'), fixture=str(fixture))
    (fixture / 'connection.json').write_text(json.dumps(settings))
    env = os.environ.copy()
    env.update(CG_TEST_DB_ALLOW_WRITES='true', CG_TEST_DB_HOST='localhost', CG_TEST_DB_PORT=str(port),
               CG_TEST_DB_DATABASE=database, CG_TEST_DB_USERNAME='consentgate_test', CG_TEST_DB_PASSWORD=password,
               CG_TEST_DB_SSL_MODE='verify-full', CG_TEST_DB_SERVER_CERTIFICATE=str(fixture / 'ca.pem'))
    env.update(CG_TEST_DB_UNTRUSTED_CERTIFICATE=str(fixture / 'untrusted-ca.pem'), CG_TEST_DB_WRONG_HOST='127.0.0.1')
    env['CG_TEST_DB_SOCKET_FAULTS'] = 'true'
    env['CG_TEST_DB_CLIENT'] = str(client_binary)
    env['CG_TEST_DB_TYPE'] = args.engine
    print('Running ' + args.engine + ' repository tests with verified TLS on loopback port ' + str(port), flush=True)
    result = subprocess.run([str(project / 'gradlew.bat'), ':core:remoteDatabaseTest', '--console=plain'],
                            cwd=project, env=env, timeout=240, creationflags=flags, capture_output=True, text=True)
    (fixture / 'gradle.log').write_text(result.stdout + result.stderr)
    if result.returncode:
        print(result.stdout[-3500:] + result.stderr[-1000:], flush=True)
        raise RuntimeError('Database integration tests failed')
    report_path = project / 'core/build/test-results/remoteDatabaseTest/TEST-io.github.consentgate.core.storage.RemoteAcceptanceRepositoryTest.xml'
    shutil.copyfile(report_path, fixture / 'repository-results.xml')
    report = ET.parse(report_path).getroot()
    print('Repository checks: ' + report.get('tests') + ' tests, ' + report.get('failures') + ' failures, '
          + report.get('skipped') + ' skipped', flush=True)
    for line in (report.findtext('system-out') or '').splitlines():
        if line.startswith('Remote load'):
            print(line, flush=True)
    for platform in args.platforms:
        command = [os.sys.executable, str(project / 'tools/run_remote_admission_probe.py'), '--platform', platform]
        if args.modules is not None:
            command += ['--modules', str(args.modules.resolve())]
        # This child bounds its own operations and must run its server/config cleanup.
        result = subprocess.run(command, cwd=project, env=env, creationflags=flags,
                                capture_output=True, text=True)
        (fixture / (platform + '-admission.log')).write_text(result.stdout + result.stderr)
        print(result.stdout + result.stderr, flush=True)
        if result.returncode:
            raise RuntimeError(platform + ' remote admission checks failed')
    backup = fixture / 'backup.sql'
    subprocess.run([str(client_binary.parent / 'mysqldump.exe'), '--defaults-file=' + str(client_file),
                    '--single-transaction', '--skip-lock-tables', '--no-tablespaces', '--set-gtid-purged=OFF',
                    '--column-statistics=0', '--result-file=' + str(backup), database],
                   check=True, capture_output=True, timeout=60, creationflags=flags)
    restored_database = 'consentgate_test_restore_' + uuid.uuid4().hex[:16]
    client_command = [str(client_binary), '--defaults-file=' + str(client_file), '--batch', '--skip-column-names', '--default-character-set=utf8mb4']
    subprocess.run(client_command, input='CREATE DATABASE ' + restored_database + ' CHARACTER SET utf8mb4;',
                   text=True, check=True, capture_output=True, timeout=15, creationflags=flags)
    with backup.open('rb') as source:
        subprocess.run(client_command + ['--database=' + restored_database], stdin=source,
                       check=True, capture_output=True, timeout=60, creationflags=flags)
    for table, order in [('cg_schema_history', 'version'), ('cg_player_locks', 'player_uuid,scope'),
                         ('cg_document_revisions', 'scope,document_id,version,locale'), ('cg_acceptance_events', 'event_id'),
                         ('cg_acceptance_state', 'player_uuid,scope,document_id'), ('cg_audit_events', 'request_id')]:
        query = 'SELECT * FROM ' + table + ' ORDER BY ' + order + ';'
        original_rows = subprocess.run(client_command + ['--database=' + database], input=query, text=True,
                                       encoding='utf-8', check=True, capture_output=True, timeout=30, creationflags=flags).stdout
        restored_rows = subprocess.run(client_command + ['--database=' + restored_database], input=query, text=True,
                                       encoding='utf-8', check=True, capture_output=True, timeout=30, creationflags=flags).stdout
        assert original_rows == restored_rows, 'Backup restore mismatch: ' + table
    print('PASS: native SQL backup restored every row in all six tables into a separate disposable database', flush=True)
finally:
    if server_process is not None and server_process.poll() is None:
        if client_file.exists():
            try:
                subprocess.run([str(client_binary.parent / 'mysqladmin.exe'), '--defaults-file=' + str(client_file), 'shutdown'],
                               capture_output=True, timeout=15, creationflags=flags)
            except (OSError, subprocess.TimeoutExpired):
                pass
        try:
            server_process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server_process.terminate()
            server_process.wait(timeout=10)
    log.close()
    print(args.engine + ' stopped. Fixture retained at ' + str(fixture), flush=True)
