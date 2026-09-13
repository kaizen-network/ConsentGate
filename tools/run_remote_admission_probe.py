"""Verify a packaged plugin against a disposable loopback SQL database and connection relay."""

import argparse
from contextlib import closing, ExitStack
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
import tomllib
import uuid

import probe_velocity
from probe_storage import RemoteFixture, TcpRelay
from probe_process import stop_server
from build_artifacts import platform_artifact
from run_paper_probe import offline_uuid
from run_velocity_probe import PROBE_DOCUMENT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=('paper', 'velocity'), required=True)
    parser.add_argument('--modules', type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    paper = args.platform == 'paper'
    if paper and args.modules is None:
        parser.error('Paper requires --modules with local Node dependencies')
    directory = project / '.run' / ('paper-minimal' if paper else 'velocity')
    port = 25592 if paper else 25590
    if paper:
        properties = dict(line.split('=', 1) for line in (directory / 'server.properties').read_text().splitlines()
                          if '=' in line and not line.startswith('#'))
        assert properties.get('server-ip') == '127.0.0.1' and properties.get('server-port') == str(port)
        assert properties.get('online-mode') == 'false'
        assert properties.get('enable-rcon') == 'false' and properties.get('enable-query') == 'false'
    else:
        config = tomllib.loads((directory / 'velocity.toml').read_text())
        assert config['bind'] == '127.0.0.1:25590' and config['online-mode'] is False
        assert config['servers']['backend'] == '127.0.0.1:25591' and config['servers']['try'] == ['backend']
        assert config['advanced']['compression-threshold'] == -1
    with socket.socket() as check:
        assert check.connect_ex(('127.0.0.1', port)) != 0, 'Test port already occupied'
    artifact = platform_artifact(project, args.platform)
    data = directory / 'plugins' / ('ConsentGate' if paper else 'consentgate')
    fixture_name = 'remote-probe-fixtures/' + uuid.uuid4().hex
    fixture = data / fixture_name
    (fixture / 'documents').mkdir(parents=True)
    (fixture / 'documents/agreement.yml').write_text(PROBE_DOCUMENT, encoding='utf-8')
    config_file = data / 'config.yml'
    original = config_file.read_bytes() if config_file.exists() else None
    relay = TcpRelay(os.environ['CG_TEST_DB_HOST'], int(os.environ['CG_TEST_DB_PORT']))
    process = None
    output = None
    backend = None
    log_file = fixture / 'server.log'

    def log():
        return log_file.read_text(encoding='utf-8', errors='replace')

    def command(value, expected):
        offset = len(log())
        process.stdin.write(value + '\n')
        process.stdin.flush()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if expected in log()[offset:]:
                return
            if process.poll() is not None:
                raise RuntimeError('Test process exited')
            time.sleep(0.1)
        raise AssertionError('Missing console reply: ' + value + ': ' + expected)

    def paper_probe(name, expected, on_dialog=None):
        args_list = ['node', str(project / 'tools/probe_paper.cjs'), '--modules', str(args.modules.resolve()),
                     '--name', name, '--expect', expected, '--hold', '1' if on_dialog else '0', '--play-seconds', '0']
        child = subprocess.Popen(args_list, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        timer = threading.Timer(60, child.kill)
        reports = []
        lines = []
        timer.start()
        try:
            for line in child.stdout:
                lines.append(line)
                try:
                    report = json.loads(line)
                except ValueError:
                    continue
                reports.append(report)
                if report.get('event') == 'dialog' and on_dialog is not None:
                    on_dialog()
                    on_dialog = None
            child.wait(timeout=5)
        finally:
            timer.cancel()
            child.stdout.close()
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=5)
        if child.returncode or not any(item.get('event') == 'result' and item.get('passed') for item in reports):
            raise AssertionError('Paper remote probe failed:\n' + ''.join(lines))

    def velocity_probe(name, expected, on_dialog=None):
        with closing(probe_velocity.Client(name, expect_dialog=expected in ('accepted', 'save-failed'))) as client:
            if expected in ('accepted', 'save-failed'):
                probe_velocity.no_backend(backend, client, 0.2)
                if on_dialog is not None:
                    on_dialog()
                client.click()
            if expected in ('accepted', 'rejoin'):
                connection, _ = backend.accept()
                with connection:
                    connection.settimeout(5)
                    handshake = probe_velocity.exact(connection, probe_velocity.read_varint(connection))
                    assert handshake[0] == 0, 'Expected initial backend handshake'
            else:
                probe_velocity.disconnected_without_backend(backend, client, 8)
        time.sleep(0.2)

    probe = paper_probe if paper else velocity_probe
    try:
        storage = RemoteFixture(fixture, relay, 'platform_' + uuid.uuid4().hex[:20])
        assert storage.query('SELECT 1;', through_relay=True) == [['1']], 'TLS connection through the relay must work before startup'
        config_file.write_text(storage.config(fixture_name), encoding='utf-8')
        shutil.copyfile(artifact, directory / 'plugins/ConsentGate.jar')
        if not paper:
            backend = socket.socket()
            backend.bind(('127.0.0.1', 25591))
            backend.listen()
            backend.settimeout(8)
        output = log_file.open('wb')
        process_args = ['java', '-Xms128m', '-Xmx768m' if paper else '-Xmx256m',
                        '-Dterminal.jline=false', '-Dterminal.ansi=false', '-jar', 'server.jar' if paper else 'velocity.jar']
        if paper:
            process_args.append('--nogui')
        process = subprocess.Popen(process_args, cwd=directory, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Test process exited; inspect ' + str(log_file))
            if 'ConsentGate could not start.' in log():
                raise RuntimeError('ConsentGate startup failed; inspect ' + str(log_file))
            with socket.socket() as check:
                if check.connect_ex(('127.0.0.1', port)) == 0 and ('enabled with ' + storage.engine + ' storage.') in log():
                    if not paper or 'Done (' in log():
                        break
            time.sleep(0.2)
        else:
            raise TimeoutError('Plugin did not start with remote storage; inspect ' + str(log_file))

        def decisions(name):
            rows = storage.query("SELECT decision FROM cg_acceptance_events WHERE scope='" + storage.scope
                                 + "' AND player_uuid='" + offline_uuid(name) + "' ORDER BY decided_at;")
            return [row[0] for row in rows]

        player = offline_uuid('ConsentRemote')
        command('consentgate validate', 'Validation passed.')
        probe('ConsentRemote', 'accepted')
        assert decisions('ConsentRemote') == ['granted']
        probe('ConsentRemote', 'rejoin')
        assert decisions('ConsentRemote') == ['granted']
        command('consentgate status ' + player, 'test-agreement (probe-1): accepted')
        command('consentgate reset ' + player, 'History was kept.')
        assert decisions('ConsentRemote') == ['granted', 'withdrawn']
        probe('ConsentRemote', 'accepted')
        assert decisions('ConsentRemote') == ['granted', 'withdrawn', 'granted']
        print('PASS: ' + args.platform + ' remote acceptance, rejoin, status, and reset history', flush=True)

        # The rejoin performs a confirmed positive read and seeds the local cache.
        probe('ConsentRemote', 'rejoin')
        # Client startup may take seconds; expiry must be measured after the confirmed check.
        verified = time.monotonic()
        def cached_time():
            with closing(sqlite3.connect('file:' + (fixture / 'cache.db').as_posix() + '?mode=ro', uri=True)) as cache:
                return cache.execute('SELECT MAX(verified_at) FROM cg_cached_checks WHERE player=? AND scope=?',
                                     (player, storage.scope)).fetchone()[0]
        cached_verification = cached_time()
        assert cached_verification is not None, 'The confirmed rejoin must create a positive cache entry'
        relay.set_available(False)
        probe('ConsentRemote', 'rejoin')
        assert cached_time() == cached_verification, 'An outage cache hit must not extend freshness'
        probe('ConsentUnknown', 'unavailable')
        command('consentgate status ' + player, 'Consent records could not be processed.')
        remaining = verified + 11 - time.monotonic()
        if remaining > 0:
            time.sleep(remaining)
        assert time.time() * 1000 - cached_verification >= 10_000, 'Stored verification must be expired before denial check'
        probe('ConsentRemote', 'unavailable')
        relay.set_available(True)
        probe('ConsentRemote', 'rejoin')
        assert decisions('ConsentUnknown') == []
        print('PASS: ' + args.platform + ' fresh-cache outage, unknown player denial, expiry, and recovery', flush=True)

        probe('ConsentSaveFail', 'save-failed', lambda: relay.set_available(False))
        relay.set_available(True)
        assert decisions('ConsentSaveFail') == []
        probe('ConsentSaveFail', 'accepted')
        assert decisions('ConsentSaveFail') == ['granted']
        print('PASS: ' + args.platform + ' failed remote save blocks admission and later recovery succeeds', flush=True)
    finally:
        # Stop the server before closing its resources, and restore config even if shutdown fails.
        with ExitStack() as cleanup:
            if original is None:
                cleanup.callback(config_file.unlink, missing_ok=True)
            else:
                cleanup.callback(config_file.write_bytes, original)
            cleanup.callback(relay.close)
            if backend is not None:
                cleanup.callback(backend.close)
            if output is not None:
                cleanup.callback(output.close)
            cleanup.callback(stop_server, process, 'stop' if paper else 'shutdown', 25)
            relay.set_available(True)
        print(args.platform + ' stopped; configuration restored. Fixture: ' + str(fixture), flush=True)


if __name__ == '__main__':
    main()
