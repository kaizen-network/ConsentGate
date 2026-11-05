"""Run the Spigot hold checks on a prepared loopback-only Spigot server, using bots."""

import argparse
from contextlib import closing
import hashlib
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

from run_velocity_probe import PROBE_CONFIG, PROBE_DOCUMENT
from probe_process import stop_server
from build_artifacts import platform_artifact

KICK = 'ConsentGate is not set up for this server. Ask the server owner to install the right ConsentGate file.'


def offline_uuid(name):
    digest = hashlib.md5(('OfflinePlayer:' + name).encode()).digest()
    return str(uuid.UUID(bytes=digest, version=3))


class Bot:
    """One bot process. Events are kept in order; `mark()` and `since` limit a wait to newer events."""

    def __init__(self, project, modules, port, name, version):
        self.name = name
        self.process = subprocess.Popen(['node', str(project / 'tools/probe_spigot.cjs'), str(modules), str(port), name, version],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        self.events, self.lines, self.changed = [], [], threading.Condition()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.process.stdout:
            with self.changed:
                self.lines.append(line)
                try:
                    self.events.append(json.loads(line))
                except ValueError:
                    pass
                self.changed.notify_all()

    def mark(self):
        with self.changed:
            return len(self.events)

    def find(self, kind, test=lambda event: True, since=0):
        with self.changed:
            return [event for event in self.events[since:] if event['event'] == kind and test(event)]

    def wait(self, kind, test=lambda event: True, since=0, timeout=15):
        deadline = time.monotonic() + timeout
        with self.changed:
            while True:
                found = [event for event in self.events[since:] if event['event'] == kind and test(event)]
                if found:
                    return found[0]
                if time.monotonic() > deadline:
                    raise AssertionError(f'{self.name}: no {kind} within {timeout}s:\n' + ''.join(self.lines[-40:]))
                self.changed.wait(0.2)

    def absent(self, kind, test=lambda event: True, since=0, seconds=3):
        time.sleep(seconds)
        found = self.find(kind, test, since)
        assert not found, f'{self.name}: unexpected {kind}: {found}'

    def send(self, **command):
        self.process.stdin.write(json.dumps(command) + '\n')
        self.process.stdin.flush()

    def ended(self, timeout=15):
        return self.wait('end', timeout=timeout)

    def close(self):
        if self.process.poll() is None:
            try:
                self.send(do='quit')
                self.process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', required=True, type=Path)
    parser.add_argument('--directory', type=Path, help='Prepared server directory inside this project\'s .run folder')
    parser.add_argument('--port', type=int, default=25720)
    parser.add_argument('--version', choices=('1.21.8', '26.1'), default='1.21.8')
    parser.add_argument('--java', default='java', help='Java executable for the prepared server')
    parser.add_argument('--refusal', action='store_true', help='Install the Paper JAR instead and check that it refuses every login')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    directory = (args.directory or project / '.run' / ('spigot-' + args.version)).resolve()
    assert directory.is_relative_to((project / '.run').resolve()), 'The server must be a local .run fixture'
    properties = dict(line.split('=', 1) for line in (directory / 'server.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    assert properties.get('server-ip') == '127.0.0.1', 'Only the loopback test server is allowed'
    assert properties.get('server-port') == str(args.port)
    assert properties.get('online-mode') == 'false'
    assert properties.get('enable-rcon') == 'false'
    assert properties.get('enable-query') == 'false'
    assert 'enabled: false' in (directory / 'plugins/bStats/config.yml').read_text(), 'Turn bStats off before the first start'
    with socket.socket() as check:
        assert check.connect_ex(('127.0.0.1', args.port)) != 0, 'Test port already occupied'
    modules = args.modules.resolve()
    assert (modules / 'minecraft-protocol').is_dir() and (modules / 'prismarine-nbt').is_dir()
    artifact = platform_artifact(project, 'paper' if args.refusal else 'spigot')
    data = directory / 'plugins/ConsentGate'
    data.mkdir(parents=True, exist_ok=True)
    config_file = data / 'config.yml'
    original_config = config_file.read_bytes() if config_file.exists() else None
    fixture_name = 'probe-fixtures/' + uuid.uuid4().hex
    fixture = data / fixture_name
    documents = fixture / 'documents'
    documents.mkdir(parents=True)
    database = fixture / 'probe.db'
    config = PROBE_CONFIG.format(fixture=fixture_name).replace('timeout-seconds: 300', 'timeout-seconds: 30') \
        .replace('max-pending: 1', 'max-pending: 2')
    (documents / 'agreement.yml').write_text(PROBE_DOCUMENT, encoding='utf-8')
    log_file = directory / 'logs/latest.log'
    process = None
    bots = []
    # The world keeps player data between runs, so every run uses new players.
    run = uuid.uuid4().hex[:4]
    N = {name: name[:12] + run for name in ['Watcher', 'HeldBot', 'LeaveBot', 'DupBot', 'PreviewBot', 'SlowBot', 'ReloadBot', 'LateBot', 'RefusedProbe']}

    def log():
        return log_file.read_text(encoding='utf-8', errors='replace') if log_file.exists() else ''

    def command(value, expected, timeout=10):
        offset = len(log())
        process.stdin.write(value + '\n')
        process.stdin.flush()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if expected in log()[offset:]:
                return log()[offset:]
            if process.poll() is not None:
                raise RuntimeError('Test server exited')
            time.sleep(0.1)
        raise AssertionError('Missing console reply for ' + value + ': ' + expected)

    def bot(name):
        created = Bot(project, modules, args.port, name, args.version)
        bots.append(created)
        return created

    def decisions(name):
        with closing(sqlite3.connect(database)) as connection:
            return [row[0] for row in connection.execute(
                'SELECT decision FROM cg_acceptance_events WHERE player_uuid=? ORDER BY decided_at', (offline_uuid(name),))]

    def joined(name):
        return lambda event: name + ' joined the game' in event['text']

    def start():
        nonlocal process
        offset = len(log())
        process = subprocess.Popen([args.java, '-Xms256m', '-Xmx1024m', '-jar', 'server.jar', '--nogui'],
                                   cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.STDOUT, text=True,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Spigot exited; inspect ' + str(log_file))
            with socket.socket() as check:
                if check.connect_ex(('127.0.0.1', args.port)) == 0 and 'Done (' in log():
                    return
            time.sleep(0.2)
        raise TimeoutError('Spigot startup exceeded 180 seconds')

    def held_join(name, watcher):
        """Joins a bot that must be held: hidden from the watcher, no join message, and a dialog in play."""
        since = watcher.mark()
        held = bot(name)
        held.wait('play')
        dialog = held.wait('dialog')
        assert dialog['state'] == 'play' and dialog['waits'] and dialog['inputs'] == ['document_0'], dialog
        watcher.absent('player_info', lambda event: event['name'] == name, since, seconds=1)
        assert not watcher.find('chat', joined(name), since), 'Join message shown while held'
        assert not watcher.find('spawn_entity', lambda event: event['uuid'] == offline_uuid(name), since)
        return held, dialog

    try:
        shutil.copyfile(artifact, directory / 'plugins/ConsentGate.jar')
        config_file.write_text(config, encoding='utf-8')
        start()
        if args.refusal:
            assert ('ConsentGate-Paper needs Paper 1.21.7 or newer. This server lacks' in log()
                    and 'On Spigot, use ConsentGate-Spigot (hold mode) instead, or switch to Paper for the full gate.' in log()), \
                'Missing refusal log'
            refused = bot(N["RefusedProbe"])
            assert KICK in refused.wait('kick')['reason']
            assert not refused.find('play')
            print('PASS: Paper JAR on Spigot ' + args.version + ' logs the Spigot JAR hint and refuses logins', flush=True)
            return
        assert 'ConsentGate is running in Spigot hold mode. Players enter the world first and stay frozen and hidden until they accept.' in log()
        assert 'ConsentGate is enabled with sqlite storage.' in log()
        for value in ('gamerule doDaylightCycle false', 'difficulty peaceful'):
            process.stdin.write(value + '\n')
        process.stdin.flush()

        # A first player is held like anyone else, then becomes the observer.
        watcher = bot(N["Watcher"])
        dialog = watcher.wait('dialog')
        assert dialog['state'] == 'play' and dialog['waits'], dialog
        since = watcher.mark()
        watcher.send(do='click', action='accept')
        watcher.wait('clear_dialog', since=since)
        watcher.wait('chat', joined(N["Watcher"]), since)
        assert decisions(N["Watcher"]) == ['granted']
        print('PASS: first player held in play, dialog waits for the server, accepted, released, join message shown', flush=True)

        held, dialog = held_join(N["HeldBot"], watcher)
        start_position = held.wait('position')
        command('consentgate reset ' + offline_uuid(N["HeldBot"]), 'Disconnect the player first')

        # Movement goes back.
        since = held.mark()
        held.send(do='move', dx=3)
        corrected = held.wait('position', since=since, timeout=5)
        assert abs(corrected['x'] - start_position['x']) < 0.01, corrected
        # Chat and typed commands never reach anyone.
        since = watcher.mark()
        held.send(do='chat', text='held-chat-probe')
        held.send(do='chat', text='/me held-command-probe')
        watcher.absent('chat', lambda event: 'held-' in event['text'], since, seconds=2)
        # Spigot logs every typed command before plugins see it; the /me broadcast itself must not appear.
        assert 'held-chat-probe' not in log() and f'* {N["HeldBot"]} held-command-probe' not in log()
        # No damage.
        since = held.mark()
        command(f'damage {N["HeldBot"]} 4', 'invulnerable')
        held.absent('health', lambda event: event['health'] < 20, since, seconds=2)
        # No block breaking, even in creative.
        command(f'gamemode creative {N["HeldBot"]}', 'Creative Mode')
        since = held.mark()
        held.send(do='dig')
        restored = held.wait('block_change', since=since, timeout=5)
        assert restored['type'] != 0, restored
        # No dropping items.
        command(f'give {N["HeldBot"]} minecraft:stone 1', 'Gave 1')
        held.send(do='drop')
        time.sleep(1)
        command(f'clear {N["HeldBot"]} minecraft:stone 0', 'Found 1 matching item')
        print('PASS: held player hidden, no join message, movement reverted, chat and commands blocked, no damage, '
              'no block breaking, no item drop', flush=True)

        # The screen comes back when a held player acts as if no screen were open.
        since = held.mark()
        time.sleep(3)
        held.send(do='chat', text='/help')
        held.wait('dialog', since=since, timeout=5)
        print('PASS: a blocked command brings the dialog back', flush=True)

        # Only exact checked bytes with the session token release the player.
        for values, token in (('unchecked', None), ('string', None), ('int', None), ('none', None), ('byte', str(uuid.uuid4()))):
            since = held.mark()
            held.send(do='click', action='accept', values=values, token=token)
            held.absent('clear_dialog', since=since, seconds=1.5)
            assert decisions(N["HeldBot"]) == [], values
        assert held.find('dialog', lambda event: 'Every checkbox is required.' in event['text']), 'Missing redisplay with error'
        since, watched = held.mark(), watcher.mark()
        held.send(do='click', action='accept')
        held.wait('clear_dialog', since=since)
        watcher.wait('player_info', lambda event: event['name'] == N["HeldBot"], watched)
        watcher.wait('chat', joined(N["HeldBot"]), watched)
        assert decisions(N["HeldBot"]) == ['granted']
        print('PASS: unchecked, string, int, empty, and foreign-token payloads do not release; valid acceptance does', flush=True)

        # Released: everything works again.
        since = held.mark()
        held.send(do='move', dx=1)
        held.absent('position', since=since, seconds=2)
        watched = watcher.mark()
        held.send(do='chat', text='free-chat-probe')
        watcher.wait('chat', lambda event: 'free-chat-probe' in event['text'], watched)
        command(f'gamemode survival {N["HeldBot"]}', 'Survival Mode')
        since = held.mark()
        command(f'damage {N["HeldBot"]} 1', 'Applied 1')
        held.wait('health', lambda event: event['health'] < 20, since)
        command(f'gamemode creative {N["HeldBot"]}', 'Creative Mode')
        since = held.mark()
        held.send(do='dig')
        assert held.wait('block_change', since=since, timeout=5)['type'] == 0
        assert len(watcher.find('chat', joined(N["HeldBot"]))) == 1, 'Join message must be shown exactly once'
        print('PASS: released player can move, chat, take damage, and break blocks; join message shown once', flush=True)

        # An accepted rejoin is never held.
        held.close()
        time.sleep(1)
        watched = watcher.mark()
        again = bot(N["HeldBot"])
        again.wait('play')
        watcher.wait('chat', joined(N["HeldBot"]), watched, timeout=5)
        again.absent('dialog', seconds=3)
        since = again.mark()
        again.send(do='move', dx=1)
        again.absent('position', since=since, seconds=2)
        again.close()
        print('PASS: accepted rejoin is not held', flush=True)

        # Leave kicks with the decline message and saves nothing; no join or quit message.
        watched = watcher.mark()
        leaving, _ = held_join(N["LeaveBot"], watcher)
        leaving.send(do='click', action='leave')
        assert 'Consent was not accepted.' in leaving.wait('kick')['reason']
        leaving.ended()
        time.sleep(1)
        assert not watcher.find('chat', lambda event: N["LeaveBot"] in event['text'], watched), 'Join or quit message shown'
        assert decisions(N["LeaveBot"]) == []
        print('PASS: Leave kicks with the decline message, saves nothing, and shows no join or quit message', flush=True)

        # A second login for a held player is refused.
        first, _ = held_join(N["DupBot"], watcher)
        second = bot(N["DupBot"])
        assert 'ConsentGate is busy.' in second.wait('kick')['reason']
        assert not second.find('play')
        first.wait('dialog')
        assert not first.find('end')
        first.close()
        print('PASS: a second login with a held UUID is refused', flush=True)

        # Preview never saves.
        command('consentgate preview ' + offline_uuid(N["PreviewBot"]) + ' en-US', 'Preview queued')
        preview, _ = held_join(N["PreviewBot"], watcher)
        preview.send(do='click', action='accept')
        assert 'Preview complete. No acceptance was saved.' in preview.wait('kick')['reason']
        assert decisions(N["PreviewBot"]) == []
        print('PASS: preview shows the screens in play and saves nothing', flush=True)

        # Timeout kicks without saving.
        watched = watcher.mark()
        slow, _ = held_join(N["SlowBot"], watcher)
        assert 'Consent request timed out.' in slow.wait('kick', timeout=45)['reason']
        time.sleep(1)
        assert not watcher.find('chat', lambda event: N["SlowBot"] in event['text'], watched)
        assert decisions(N["SlowBot"]) == []
        print('PASS: timeout kicks without saving and shows no join or quit message', flush=True)

        # /reload kicks held players; an online player without acceptance is held after the plugin enables again.
        stopping, _ = held_join(N["ReloadBot"], watcher)
        command('reload confirm', 'Reload complete', timeout=60)
        assert 'ConsentGate is stopping.' in stopping.wait('kick')['reason']
        config_file.write_text(config.replace('enabled: true', 'enabled: false'), encoding='utf-8')
        command('reload confirm', 'Reload complete', timeout=60)
        late = bot(N["LateBot"])
        late.wait('play')
        late.absent('dialog', seconds=3)
        config_file.write_text(config, encoding='utf-8')
        since, watched = late.mark(), watcher.mark()
        command('reload confirm', 'Reload complete', timeout=60)
        late.wait('dialog', since=since)
        watcher.absent('dialog', since=watched, seconds=2)
        late.send(do='click', action='accept')
        late.wait('clear_dialog', since=since)
        assert decisions(N["LateBot"]) == ['granted']
        print('PASS: /reload kicks held players; after enabling, an online player without acceptance is held, '
              'and an accepted one passes', flush=True)
        print('PASS: Spigot ' + args.version + ' hold mode', flush=True)
    finally:
        for created in bots:
            created.close()
        try:
            stop_server(process, 'stop', 30)
        finally:
            if original_config is None:
                config_file.unlink(missing_ok=True)
            else:
                config_file.write_bytes(original_config)
        print('Spigot stopped; original configuration restored. Fixture: ' + str(fixture), flush=True)


if __name__ == '__main__':
    main()
