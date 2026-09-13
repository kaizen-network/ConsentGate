"""Run repeated configuration with a disposable helper on a prepared loopback Paper server."""

import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
import uuid
import zipfile

from run_velocity_probe import PROBE_CONFIG, PROBE_DOCUMENT
from probe_process import stop_server
from build_artifacts import platform_artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--modules', type=Path, required=True)
    parser.add_argument('--version', choices=('1.21.8', '26.1'), required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--hold', type=int, default=75, help='Initial consent wait before testing reconfiguration')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    artifact = platform_artifact(project, 'paper')
    directory = args.directory.resolve()
    assert directory.is_relative_to((project / '.run').resolve())
    assert 0 <= args.hold <= 300
    properties = dict(line.split('=', 1) for line in (directory / 'server.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    assert properties['server-ip'] == '127.0.0.1' and properties['online-mode'] == 'false'
    assert properties['enable-rcon'] == 'false' and properties['enable-query'] == 'false'
    port = int(properties['server-port'])
    with socket.socket() as listener:
        assert listener.connect_ex(('127.0.0.1', port)) != 0
    data = directory / 'plugins/ConsentGate'
    config = data / 'config.yml'
    original = config.read_bytes()
    fixture_name = 'probe-fixtures/' + uuid.uuid4().hex
    fixture = data / fixture_name
    (fixture / 'documents').mkdir(parents=True)
    document = fixture / 'documents/agreement.yml'
    document.write_text(PROBE_DOCUMENT)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    classes = fixture / 'helper-classes'
    classes.mkdir()
    classpath = os.pathsep.join(str(path) for path in (directory / 'libraries').rglob('*.jar'))
    javac_args = fixture / 'javac.args'
    javac_args.write_text('\n'.join(['--release', '21', '-proc:none', '-classpath', '"' + classpath.replace('\\', '/') + '"',
        '-d', '"' + classes.as_posix() + '"', '"' + (project / 'tools/paper_reconfiguration/StageProbe.java').as_posix() + '"']))
    subprocess.run([args.javac, '@' + str(javac_args)], check=True, creationflags=flags)
    helper = directory / 'plugins/ConsentGateStageProbe.jar'
    assert not helper.exists(), 'A helper is already present; inspect it before repeating this check'
    process = None
    log = fixture / 'console.log'

    def command(text, expected):
        offset = len(log.read_text(encoding='utf-8', errors='replace'))
        process.stdin.write(text + '\n')
        process.stdin.flush()
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            if expected in log.read_text(encoding='utf-8', errors='replace')[offset:]:
                return
            time.sleep(0.1)
        raise AssertionError('Missing reply: ' + text)

    try:
        with zipfile.ZipFile(helper, 'x') as jar:
            for path in classes.rglob('*.class'):
                jar.write(path, path.relative_to(classes).as_posix())
            jar.write(project / 'tools/paper_reconfiguration/plugin.yml', 'plugin.yml')
        config.write_text(PROBE_CONFIG.format(fixture=fixture_name))
        shutil.copyfile(artifact, directory / 'plugins/ConsentGate.jar')
        with log.open('wb') as output:
            process = subprocess.Popen([args.java, '-Xms256m', '-Xmx768m', '-Dterminal.jline=false', '-Dterminal.ansi=false',
                '-jar', 'server.jar', '--nogui'], cwd=directory, stdin=subprocess.PIPE,
                stdout=output, stderr=subprocess.STDOUT, text=True, creationflags=flags)
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Paper exited: ' + str(log))
                if 'Done (' in log.read_text(encoding='utf-8', errors='replace'):
                    break
                time.sleep(0.2)
            else:
                raise TimeoutError('Paper startup timed out')
            options = dict(modules=str(args.modules.resolve()), port=port, version=args.version, hold=args.hold)
            child = subprocess.Popen(['node', str(project / 'tools/probe_paper_reconfiguration.cjs'), json.dumps(options)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, creationflags=flags)
            timer = threading.Timer(args.hold + 120, child.kill)
            timer.start()
            lines = []
            reports = []
            try:
                for line in child.stdout:
                    lines.append(line)
                    try:
                        report = json.loads(line)
                    except ValueError:
                        continue
                    reports.append(report)
                    if report.get('event') == 'playing' and report['plays'] < 4:
                        if report['plays'] in (1, 3):
                            version = 'probe-2' if report['plays'] == 1 else 'probe-3'
                            document.write_text(PROBE_DOCUMENT.replace('probe-1', version))
                            command('consentgate reload', 'ConsentGate reloaded.')
                        command('stageprobe ReconfigureProbe', 'Requested test reconfiguration.')
                child.wait(timeout=10)
                assert child.returncode == 0 and any(r.get('event') == 'result' and r.get('passed') for r in reports), ''.join(lines)
            finally:
                timer.cancel()
                if child.poll() is None:
                    child.kill()
                    child.wait(timeout=10)
                child.stdout.close()
                (fixture / 'client.jsonl').write_text(''.join(lines))
            with sqlite3.connect(fixture / 'probe.db') as database:
                assert database.execute("SELECT COUNT(*) FROM cg_acceptance_events WHERE decision='granted'").fetchone()[0] == 1
            reconnect = subprocess.run(['node', str(project / 'tools/probe_paper.cjs'), '--modules', str(args.modules.resolve()),
                '--name', 'ReconfigureProbe', '--version', args.version, '--port', str(port), '--hold', '0',
                '--play-seconds', '1', '--expect', 'accepted'], capture_output=True, text=True, timeout=60, creationflags=flags)
            (fixture / 'reconnect.jsonl').write_text(reconnect.stdout + reconnect.stderr)
            assert reconnect.returncode == 0, reconnect.stdout + reconnect.stderr
            with sqlite3.connect(fixture / 'probe.db') as database:
                assert database.execute("SELECT COUNT(*) FROM cg_acceptance_events WHERE decision='granted'").fetchone()[0] == 2
            print('PASS: initial long hold, repeated reconfiguration without duplicate consent, and new-version consent on reconnect', flush=True)
    finally:
        with ExitStack() as cleanup:
            cleanup.callback(helper.unlink, missing_ok=True)
            cleanup.callback(config.write_bytes, original)
            cleanup.callback(stop_server, process, 'stop', 30)
        print('Paper stopped; configuration restored and helper removed. Fixture: ' + str(fixture), flush=True)


if __name__ == '__main__':
    main()
