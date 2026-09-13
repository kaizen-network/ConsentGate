"""Run opt-in MySQL tests using an extracted Windows server, without installing a service."""

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

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

project = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server', required=True, type=Path, help='Root of an extracted MySQL Windows ZIP distribution')
parser.add_argument('--platforms', nargs='*', choices=('paper', 'velocity'), default=[])
parser.add_argument('--modules', type=Path, help='Local Node dependency directory, required for Paper checks')
args = parser.parse_args()
if 'paper' in args.platforms and args.modules is None:
    parser.error('Paper checks require --modules')
base = args.server.resolve()
assert (base / 'bin/mysqld.exe').is_file(), 'Provide the extracted MySQL server directory'
fixture = project / '.run' / ('mysql-test-' + uuid.uuid4().hex)
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
    subprocess.run([str(bin_dir / 'mysqld.exe'), '--no-defaults', '--initialize-insecure',
                    '--basedir=' + str(base), '--datadir=' + str(data)], check=True, stdout=output,
                   stderr=subprocess.STDOUT, timeout=120, creationflags=flags)
print('Initialized disposable MySQL data.', flush=True)
log = (fixture / 'server.log').open('wb')
try:
    server_process = subprocess.Popen([str(bin_dir / 'mysqld.exe'), '--no-defaults', '--console',
        '--basedir=' + str(base), '--datadir=' + str(data), '--bind-address=127.0.0.1', '--port=' + str(port),
        '--mysqlx=OFF', '--skip-log-bin', '--local-infile=OFF', '--ssl-ca=' + str(fixture / 'ca.pem'),
        '--ssl-cert=' + str(fixture / 'server.pem'), '--ssl-key=' + str(fixture / 'server-key.pem')],
        stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if server_process.poll() is not None:
            raise RuntimeError('MySQL exited; inspect ' + str(fixture / 'server.log'))
        with socket.socket() as check:
            if check.connect_ex(('127.0.0.1', port)) == 0:
                break
        time.sleep(0.2)
    else:
        raise TimeoutError('MySQL did not start')
    sql = (f'CREATE DATABASE {database} CHARACTER SET utf8mb4;'
           f"CREATE USER 'consentgate_test'@'localhost' IDENTIFIED BY '{password}';"
           f"GRANT ALL ON {database}.* TO 'consentgate_test'@'localhost';"
           f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{root_password}';")
    subprocess.run([str(bin_dir / 'mysql.exe'), '--no-defaults', '--protocol=TCP', '--host=localhost',
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
    env['CG_TEST_DB_CLIENT'] = str(bin_dir / 'mysql.exe')
    print('Running MySQL repository tests with verified TLS on loopback port ' + str(port), flush=True)
    result = subprocess.run([str(project / 'gradlew.bat'), '--offline', ':core:remoteDatabaseTest', '--console=plain'],
                            cwd=project, env=env, timeout=240, creationflags=flags, capture_output=True, text=True)
    (fixture / 'gradle.log').write_text(result.stdout + result.stderr)
    print(result.stdout[-3500:] + result.stderr[-1000:], flush=True)
    if result.returncode:
        raise RuntimeError('MySQL integration tests failed')
    for platform in args.platforms:
        command = [os.sys.executable, str(project / 'tools/run_remote_admission_probe.py'), '--platform', platform]
        if args.modules is not None:
            command += ['--modules', str(args.modules.resolve())]
        result = subprocess.run(command, cwd=project, env=env, timeout=300, creationflags=flags,
                                capture_output=True, text=True)
        (fixture / (platform + '-admission.log')).write_text(result.stdout + result.stderr)
        print(result.stdout + result.stderr, flush=True)
        if result.returncode:
            raise RuntimeError(platform + ' remote admission checks failed')
finally:
    if server_process is not None and server_process.poll() is None:
        if client_file.exists():
            subprocess.run([str(bin_dir / 'mysqladmin.exe'), '--defaults-file=' + str(client_file), 'shutdown'],
                           capture_output=True, timeout=15, creationflags=flags)
        try:
            server_process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server_process.terminate()
            server_process.wait(timeout=10)
    log.close()
    print('MySQL stopped. Fixture retained at ' + str(fixture), flush=True)
