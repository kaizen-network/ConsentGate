"""Check a fresh Paper installation, invalid-schema denial, and the packaged admission flow."""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import time
import uuid

from run_velocity_probe import PROBE_CONFIG, PROBE_DOCUMENT
from build_artifacts import platform_artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-jar', required=True, type=Path)
    parser.add_argument('--modules', required=True, type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--version', choices=('1.21.8', '26.1'), required=True, help='Explicit synthetic-client data version')
    parser.add_argument('--cache-from', type=Path, help='Reuse libraries/cache from another local .run server fixture')
    parser.add_argument('--port', type=int, default=25593)
    parser.add_argument('--eula-from', required=True, type=Path, help='Existing test installation with an accepted eula.txt')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    artifact = platform_artifact(project, 'paper')
    assert args.server_jar.is_file()
    assert 'eula=true' in args.eula_from.read_text(), 'Use an already accepted test installation EULA file'
    assert 1 <= args.port <= 65535
    with socket.socket() as check:
        assert check.connect_ex(('127.0.0.1', args.port)) != 0, 'Test port is occupied'
    directory = project / '.run' / ('paper-install-' + uuid.uuid4().hex)
    directory.mkdir()
    print('Fresh installation fixture: ' + str(directory), flush=True)
    if args.cache_from:
        cached = args.cache_from.resolve()
        assert cached.is_relative_to((project / '.run').resolve())
        for name in ('libraries', 'cache'):
            if (cached / name).is_dir():
                shutil.copytree(cached / name, directory / name)
    shutil.copyfile(args.server_jar, directory / 'server.jar')
    shutil.copyfile(args.eula_from, directory / 'eula.txt')
    (directory / 'plugins').mkdir()
    (directory / 'config').mkdir()
    (directory / 'config/paper-global.yml').write_text('spark:\n  enabled: false\n  enable-immediately: false\n')
    (directory / 'plugins/bStats').mkdir()
    (directory / 'plugins/bStats/config.yml').write_text('enabled: false\n')
    (directory / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenable-rcon=false\nenable-query=false\n'
        'enforce-secure-profile=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n'
        'max-players=8\nlevel-type=minecraft:flat\ngenerate-structures=false\nallow-flight=true\n'
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]}\n')
    shutil.copyfile(artifact, directory / 'plugins/ConsentGate.jar')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

    @contextmanager
    def running(phase):
        log = directory / (phase + '.log')
        with log.open('wb') as output:
            process = subprocess.Popen([args.java, '-Xms256m', '-Xmx768m', '-Dterminal.jline=false',
                '-Dterminal.ansi=false', '-jar', 'server.jar', '--nogui'], cwd=directory,
                stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True, creationflags=flags)
            try:
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Paper exited; inspect ' + str(log))
                    if 'Done (' in log.read_text(encoding='utf-8', errors='replace'):
                        break
                    time.sleep(0.2)
                else:
                    raise TimeoutError('Paper startup timed out; inspect ' + str(log))
                yield log
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write('stop\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                    except (BrokenPipeError, subprocess.TimeoutExpired):
                        process.terminate()
                        process.wait(timeout=10)
                process.stdin.close()

    def client(expected, name):
        result = subprocess.run(['node', str(project / 'tools/probe_paper.cjs'), '--modules', str(args.modules.resolve()),
            '--version', args.version, '--port', str(args.port), '--name', name, '--hold', '0', '--play-seconds', '1',
            '--expect', expected], capture_output=True, text=True, timeout=60, creationflags=flags)
        reports = []
        for line in result.stdout.splitlines():
            try:
                reports.append(json.loads(line))
            except ValueError:
                pass
        if result.returncode or not any(item.get('event') == 'result' and item.get('passed') for item in reports):
            raise AssertionError('Installation client failed:\n' + result.stdout + result.stderr)

    data = directory / 'plugins/ConsentGate'
    with running('fresh-disabled') as log:
        assert 'ConsentGate is disabled. Configure documents before enabling it.' in log.read_text()
        for file in ['config.yml', 'messages/en-US.properties', 'messages/id-ID.properties',
                     'documents/terms.yml.example', 'documents/privacy.yml.example']:
            assert (data / file).is_file(), 'Missing default: ' + file
        assert not (data / 'data/consent.db').exists(), 'Disabled startup must not create acceptance storage'
        client('rejoin', 'InstallDisabled')
    print('PASS: fresh installation creates inactive defaults and permits normal disabled-mode play', flush=True)
    original = (data / 'config.yml').read_bytes()
    try:
        (data / 'schema-check/documents').mkdir(parents=True)
        (data / 'schema-check/documents/agreement.yml').write_text(PROBE_DOCUMENT)
        with sqlite3.connect(data / 'schema-check/probe.db') as database:
            database.execute('CREATE TABLE cg_schema_history(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL)')
            database.execute("INSERT INTO cg_schema_history VALUES(99,'2026-09-13T00:00:00Z')")
        (data / 'config.yml').write_text(PROBE_CONFIG.format(fixture='schema-check'))
        with running('invalid-schema') as log:
            assert 'Database schema 99 is newer than supported schema 1' in log.read_text()
            client('unavailable', 'InstallInvalid')
        print('PASS: enabled startup with a newer schema denies admission', flush=True)
    finally:
        (data / 'config.yml').write_bytes(original)
    # The child bounds its own operations and owns server shutdown and config restoration.
    # Killing that Python process on a parent deadline would bypass its cleanup.
    result = subprocess.run([os.sys.executable, str(project / 'tools/run_paper_probe.py'), '--directory', str(directory),
        '--modules', str(args.modules.resolve()), '--port', str(args.port), '--version', args.version, '--java', args.java],
        cwd=project, capture_output=True, text=True, creationflags=flags)
    (directory / 'admission.log').write_text(result.stdout + result.stderr)
    print(result.stdout + result.stderr, flush=True)
    if result.returncode:
        raise RuntimeError('Packaged admission failed; inspect ' + str(directory / 'admission.log'))
    print('PASS: clean packaged Paper installation and admission flow; retained ' + str(directory), flush=True)


if __name__ == '__main__':
    main()
