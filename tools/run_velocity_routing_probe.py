"""Check a clean Velocity install and actual routing through two disposable Paper backends."""

import argparse
from contextlib import ExitStack, contextmanager
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import time
import uuid

from probe_storage import TcpRelay
from run_velocity_probe import PROBE_CONFIG, PROBE_DOCUMENT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--paper-from', type=Path, required=True, help='Prepared stock Paper 1.21.7 fixture in .run')
    parser.add_argument('--velocity-from', type=Path, required=True, help='Prepared Velocity fixture in .run')
    parser.add_argument('--modules', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--paper-java', default='java')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    for path in (args.paper_from, args.velocity_from):
        if not path.resolve().is_relative_to((project / '.run').resolve()):
            raise ValueError('Server sources must be disposable .run fixtures')
    assert 'eula=true' in (args.paper_from / 'eula.txt').read_text()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    fixture = project / '.run' / ('velocity-routing-' + uuid.uuid4().hex)
    fixture.mkdir()
    print('Routing fixture: ' + str(fixture), flush=True)

    def free_port():
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            return listener.getsockname()[1]

    @contextmanager
    def server(directory, java, jar, marker):
        with (directory / 'console.log').open('wb') as output:
            process = subprocess.Popen([java, '-Xms128m', '-Xmx640m', '-Dterminal.jline=false',
                '-Dterminal.ansi=false', '-jar', jar, *(['--nogui'] if jar == 'server.jar' else [])],
                cwd=directory, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                text=True, creationflags=flags)
            try:
                deadline = time.monotonic() + 180
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Server exited: ' + str(directory / 'console.log'))
                    if marker in (directory / 'console.log').read_text(encoding='utf-8', errors='replace'):
                        break
                    time.sleep(0.2)
                else:
                    raise TimeoutError('Server startup timed out: ' + str(directory))
                yield process
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(('stop' if jar == 'server.jar' else 'shutdown') + '\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                    except (BrokenPipeError, subprocess.TimeoutExpired):
                        process.terminate()
                        process.wait(timeout=10)
                process.stdin.close()

    class RecordingRelay(TcpRelay):
        def __init__(self, port):
            self.connections = []
            super().__init__('127.0.0.1', port)

        def forward(self, client):
            self.connections.append(time.time())
            super().forward(client)

    with ExitStack() as stack:
        backends = []
        relays = []
        for name in ('primary', 'secondary'):
            directory = fixture / name
            directory.mkdir()
            for file in ('server.jar', 'eula.txt'):
                shutil.copyfile(args.paper_from / file, directory / file)
            for name_to_copy in ('libraries', 'cache', 'world', 'world_nether', 'world_the_end'):
                source = args.paper_from / name_to_copy
                if source.is_dir():
                    shutil.copytree(source, directory / name_to_copy)
            port = free_port()
            (directory / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
                'enforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nview-distance=2\nsimulation-distance=2\n')
            (directory / 'config').mkdir()
            (directory / 'config/paper-global.yml').write_text('spark:\n  enabled: false\n  enable-immediately: false\n')
            stack.enter_context(server(directory, args.paper_java, 'server.jar', 'Done ('))
            backends.append(directory)
            relay = RecordingRelay(port)
            stack.callback(relay.close)
            relays.append(relay)
        proxy = fixture / 'proxy'
        (proxy / 'plugins/bStats').mkdir(parents=True)
        (proxy / 'plugins/bStats/config.txt').write_text('enabled=false\n')
        shutil.copyfile(args.velocity_from / 'velocity.jar', proxy / 'velocity.jar')
        shutil.copyfile(args.velocity_from / 'plugins/packetevents.jar', proxy / 'plugins/packetevents.jar')
        shutil.copyfile(project / 'platform-velocity/build/libs/ConsentGate-Velocity-0.1.0-prototype.jar', proxy / 'plugins/ConsentGate.jar')
        port = free_port()
        (proxy / 'velocity.toml').write_text(f'''config-version = "2.7"
bind = "127.0.0.1:{port}"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
ping-passthrough = "DISABLED"
enable-player-address-logging = false
[servers]
primary = "127.0.0.1:{relays[0].port}"
secondary = "127.0.0.1:{relays[1].port}"
try = ["primary", "secondary"]
[forced-hosts]
"forced.test" = ["secondary"]
[advanced]
compression-threshold = -1
login-ratelimit = 0
read-timeout = 30000
[query]
enabled = false
''')
        data = proxy / 'plugins/consentgate'

        def client(name, expected='accepted', host='127.0.0.1', switch=False):
            options = dict(modules=str(args.modules.resolve()), port=port, name=name, expected=expected, host=host, switch=switch)
            offsets = [len(relay.connections) for relay in relays]
            process = subprocess.Popen(['node', str(project / 'tools/probe_velocity_route.cjs'), json.dumps(options)],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, creationflags=flags)
            lines = []
            reports = []
            try:
                for line in process.stdout:
                    lines.append(line)
                    try:
                        report = json.loads(line)
                    except ValueError:
                        continue
                    reports.append(report)
                    if report.get('event') == 'dialog':
                        time.sleep(3)
                        assert [len(relay.connections) for relay in relays] == offsets, 'Backend contact before acceptance'
                process.wait(timeout=10)
                assert process.returncode == 0 and any(r.get('event') == 'result' and r.get('passed') for r in reports), ''.join(lines)
                if expected == 'accepted':
                    accepted_at = next(r['time'] / 1000 for r in reports if r.get('event') == 'accepted')
                    assert all(connected >= accepted_at for relay, offset in zip(relays, offsets)
                               for connected in relay.connections[offset:]), 'Backend connection preceded acceptance'
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=10)
                process.stdout.close()
                (fixture / (name + '-' + expected + '.jsonl')).write_text(''.join(lines))
            return [len(relay.connections) - offset for relay, offset in zip(relays, offsets)]

        with server(proxy, args.java, 'velocity.jar', 'Done ('):
            assert 'ConsentGate is disabled' in (proxy / 'console.log').read_text()
            assert (data / 'documents/terms.yml.example').is_file()
            assert (data / 'documents/privacy.yml.example').is_file()
            assert not (data / 'data/consent.db').exists()
            assert client('RouteDisabled', 'rejoin') == [1, 0]
        print('PASS: fresh disabled Velocity installation reaches a real backend', flush=True)
        (data / 'invalid/documents').mkdir(parents=True)
        (data / 'invalid/documents/agreement.yml').write_text(PROBE_DOCUMENT)
        with sqlite3.connect(data / 'invalid/probe.db') as database:
            database.execute('CREATE TABLE cg_schema_history(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL)')
            database.execute("INSERT INTO cg_schema_history VALUES(99,'2026-09-13T00:00:00Z')")
        (data / 'config.yml').write_text(PROBE_CONFIG.format(fixture='invalid'))
        with server(proxy, args.java, 'velocity.jar', 'Done ('):
            assert 'Database schema 99 is newer than supported schema 1' in (proxy / 'console.log').read_text()
            assert client('RouteInvalid', 'unavailable') == [0, 0]
            assert 'has disconnected: ConsentGate configuration is invalid.' in (proxy / 'console.log').read_text()
        print('PASS: enabled unsupported-schema startup denies all backend contact', flush=True)
        (data / 'routing/documents').mkdir(parents=True)
        (data / 'routing/documents/agreement.yml').write_text(PROBE_DOCUMENT)
        (data / 'config.yml').write_text(PROBE_CONFIG.format(fixture='routing'))
        with server(proxy, args.java, 'velocity.jar', 'Done ('):
            assert client('RouteSwitch', switch=True) == [1, 1]
            for directory in backends:
                assert 'RouteSwitch joined the game' in (directory / 'console.log').read_text()
            assert client('RouteSwitch', 'rejoin') == [1, 0]
            assert client('RouteForced', host='forced.test') == [0, 1]
            relays[0].set_available(False)
            assert client('RouteFallback') == [0, 1]
            relays[0].set_available(True)
            with sqlite3.connect(data / 'routing/probe.db') as database:
                assert database.execute("SELECT COUNT(*) FROM cg_acceptance_events WHERE decision='granted'").fetchone()[0] == 3
            print('PASS: world entry, server switch, accepted rejoin, forced host, and unavailable-primary fallback', flush=True)
    print('All test servers and relays stopped; retained ' + str(fixture), flush=True)


if __name__ == '__main__':
    main()
