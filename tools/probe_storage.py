"""Loopback-only storage fixtures and connection faults for platform integration tests."""

import json
import os
from pathlib import Path
import select
import shutil
import socket
import subprocess
import threading

from run_velocity_probe import PROBE_CONFIG


class TcpRelay:
    def __init__(self, host, port):
        if host not in ('localhost', '127.0.0.1'):
            raise ValueError('Platform storage fault tests require a loopback database')
        self.target = (host, port)
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        self.stopped = threading.Event()
        self.lock = threading.Lock()
        self.sockets = set()
        self.available = True
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        while not self.stopped.is_set():
            try:
                client, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self.lock:
                if not self.available:
                    client.close()
                    continue
                self.sockets.add(client)
            threading.Thread(target=self.forward, args=(client,), daemon=True).start()

    def forward(self, client):
        server = None
        try:
            server = socket.create_connection(self.target, timeout=3)
            with self.lock:
                if not self.available or self.stopped.is_set():
                    return
                self.sockets.add(server)
            while not self.stopped.is_set():
                readable, _, _ = select.select([client, server], [], [], 0.2)
                for source in readable:
                    data = source.recv(65536)
                    if not data:
                        return
                    (server if source is client else client).sendall(data)
        except (OSError, ValueError):
            pass
        finally:
            for connection in (client, server):
                if connection is not None:
                    connection.close()
                    with self.lock:
                        self.sockets.discard(connection)

    def set_available(self, available):
        with self.lock:
            self.available = available
            connections = list(self.sockets) if not available else []
        for connection in connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()

    def close(self):
        self.stopped.set()
        self.set_available(False)
        self.listener.close()
        self.thread.join(timeout=3)
        if self.thread.is_alive():
            raise RuntimeError('Storage relay did not stop')


class RemoteFixture:
    def __init__(self, directory, relay, scope):
        if os.environ.get('CG_TEST_DB_ALLOW_WRITES') != 'true':
            raise ValueError('Dedicated database write opt-in is required')
        self.database = os.environ['CG_TEST_DB_DATABASE']
        if not self.database.startswith('consentgate_test_'):
            raise ValueError('A dedicated test database is required')
        self.host = os.environ['CG_TEST_DB_HOST']
        self.port = int(os.environ['CG_TEST_DB_PORT'])
        self.username = os.environ['CG_TEST_DB_USERNAME']
        self.password = os.environ['CG_TEST_DB_PASSWORD']
        self.engine = os.environ.get('CG_TEST_DB_TYPE', 'mysql')
        if self.engine not in ('mysql', 'mariadb'):
            raise ValueError('Unsupported test database type')
        self.scope = scope
        self.directory = Path(directory)
        self.relay = relay
        self.ca = Path(os.environ['CG_TEST_DB_SERVER_CERTIFICATE'])
        shutil.copyfile(self.ca, self.directory / 'ca.pem')
        self.client = Path(os.environ['CG_TEST_DB_CLIENT'])
        self.client_file = self.directory / 'sql-client.ini'
        # The portable runner supplies generated hexadecimal passwords, avoiding option-file quoting ambiguity.
        if not self.password or any(character not in '0123456789abcdef' for character in self.password):
            raise ValueError('Platform fixture requires a generated hexadecimal test password')
        self.client_file.write_text(f'[client]\nhost={self.host}\nport={self.port}\nuser={self.username}\n'
                                   f'password={self.password}\ndatabase={self.database}\nssl-mode=VERIFY_IDENTITY\n'
                                   f'ssl-ca={self.ca.as_posix()}\n', encoding='utf-8')

    def config(self, fixture_name):
        config = PROBE_CONFIG.format(fixture=fixture_name).replace('scope: probe', 'scope: ' + self.scope)
        config = config.replace('type: sqlite', 'type: ' + self.engine).replace('timeout-seconds: 300', 'timeout-seconds: 30')
        return config + ('  remote:\n'
                         f'    host: {json.dumps(self.host)}\n    port: {self.relay.port}\n'
                         f'    database: {json.dumps(self.database)}\n    username: {json.dumps(self.username)}\n'
                         f'    password: {json.dumps(self.password)}\n    ssl-mode: verify-full\n'
                         f'    server-certificate: {json.dumps(fixture_name + "/ca.pem")}\n'
                         '    connect-timeout-millis: 3000\n    socket-timeout-millis: 5000\n'
                         f'  cache:\n    enabled: true\n    file: {fixture_name}/cache.db\n'
                         '    freshness-seconds: 10\n    max-entries: 100\n')

    def query(self, sql, through_relay=False):
        command = [str(self.client), '--defaults-file=' + str(self.client_file), '--batch', '--raw', '--skip-column-names']
        if through_relay:
            command.append('--port=' + str(self.relay.port))
        result = subprocess.run(command, input=sql, capture_output=True,
                                text=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if result.returncode:
            raise RuntimeError('Test database inspection failed: ' + result.stderr)
        return [line.split('\t') for line in result.stdout.splitlines()]
