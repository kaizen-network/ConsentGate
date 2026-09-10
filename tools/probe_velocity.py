"""Local-only wire checks for the prototype using Minecraft Java protocol 772.

Requires a running offline test proxy on 127.0.0.1:25590 with compression off
and its initial backend set to 127.0.0.1:25591. No real account is used.
"""

import re
import select
import socket
import sqlite3
import struct
import time
import uuid


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def string(value):
    encoded = value.encode("utf-8")
    return varint(len(encoded)) + encoded


def exact(sock, size):
    result = bytearray()
    while len(result) < size:
        part = sock.recv(size - len(result))
        if not part:
            raise EOFError("Connection closed")
        result.extend(part)
    return bytes(result)


def read_varint(sock):
    result = 0
    for shift in range(0, 35, 7):
        value = exact(sock, 1)[0]
        result |= (value & 127) << shift
        if not value & 128:
            return result
    raise ValueError("Oversized VarInt")


class Client:
    def __init__(self, name=None, player_id=None, expect_dialog=True):
        self.name = name or "Probe_" + uuid.uuid4().hex[:8]
        self.player_id = player_id or uuid.uuid4()
        self.socket = socket.create_connection(("127.0.0.1", 25590), timeout=5)
        self.send(0, varint(772) + string("localhost") + struct.pack(">H", 25590) + varint(2))
        self.send(0, string(self.name) + self.player_id.bytes)
        packet, data = self.receive()
        assert packet == 2, ("Expected login success", packet, data)
        self.send(3)
        self.token = self.dialog() if expect_dialog else None

    def send(self, packet, payload=b""):
        body = varint(packet) + payload
        self.socket.sendall(varint(len(body)) + body)

    def receive(self):
        size = read_varint(self.socket)
        assert 0 < size <= 1048576
        body = exact(self.socket, size)
        # The packets inspected here have single-byte IDs.
        assert body[0] < 128
        if body[0] == 4:
            self.send(4, body[1:])
        return body[0], body[1:]

    def dialog(self, action="accept"):
        for _ in range(20):
            packet, body = self.receive()
            if packet == 18:
                if action == "accept":
                    assert b"document_0" in body, "Dialog did not use a protocol-safe input name"
                    assert b"test-agreement" not in body, "Document ID leaked into the dialog input name"
                    assert b"<gold>" not in body, "MiniMessage markup leaked into the dialog"
                    assert b"gold" in body and b"yellow" in body, "Formatted colors were not encoded"
                pattern = rb"consentgate:" + action.encode() + rb"/([0-9a-f-]{36})"
                match = re.search(pattern, body)
                assert match, "Missing session action in dialog"
                return match.group(1).decode()
            assert packet != 2, ("Disconnected before dialog", body)
        raise AssertionError("No dialog received")

    def click(self, action="accept", checked=True, token=None):
        nbt = b"\x0a\x01\x00\x0adocument_0" + bytes([int(checked)]) + b"\x00"
        self.click_nbt(nbt, action, token)

    def click_nbt(self, nbt, action="accept", token=None):
        self.send(8, string("consentgate:" + action + "/" + (token or self.token)) + varint(len(nbt)) + nbt)

    def close(self):
        self.socket.close()


def no_backend(backend, client, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        ready, _, _ = select.select([backend, client.socket], [], [], min(0.2, deadline - time.monotonic()))
        assert backend not in ready, "Backend contacted before acceptance"
        if client.socket in ready:
            packet, body = client.receive()
            assert packet != 2, ("Unexpected disconnect", body)


def disconnected_without_backend(backend, client, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        ready, _, _ = select.select([backend, client.socket], [], [], min(0.2, deadline - time.monotonic()))
        assert backend not in ready, "Backend contacted after a failed acceptance save"
        if client.socket in ready:
            packet, _ = client.receive()
            if packet == 2:
                return
    raise AssertionError("Client was not disconnected after a failed acceptance save")


def main(database=None):
    with socket.socket() as backend:
        backend.bind(("127.0.0.1", 25591))
        backend.listen()
        backend.settimeout(5)
        client = Client()
        try:
            overflow = Client(expect_dialog=False)
            try:
                disconnected_without_backend(backend, overflow, 5)
                print("PASS: configured pending capacity rejects overflow", flush=True)
            finally:
                overflow.close()
            no_backend(backend, client, 35)
            print("PASS: dialog and keepalives without backend contact for 35 seconds", flush=True)
            client.click(checked=False)
            assert client.dialog() == client.token
            time.sleep(0.3)
            client.click_nbt(b"\x0a\x00")
            assert client.dialog() == client.token
            time.sleep(0.3)
            wrong_type = b"\x0a\x08\x00\x0adocument_0\x00\x04true\x00"
            client.click_nbt(wrong_type)
            assert client.dialog() == client.token
            time.sleep(0.3)
            extra_key = b"\x0a\x01\x00\x0adocument_0\x01\x01\x00\x05other\x01\x00"
            client.click_nbt(extra_key)
            assert client.dialog() == client.token
            no_backend(backend, client, 0.5)
            client.click(token=str(uuid.uuid4()))
            no_backend(backend, client, 0.5)
            print("PASS: unchecked, malformed, and forged acceptance remain blocked", flush=True)
            client.click(action="read/0", checked=True)
            assert client.dialog("next/0/0") == client.token
            client.click(action="next/0/0")
            assert client.dialog("previous/0/1") == client.token
            client.click(action="previous/0/1")
            assert client.dialog("back") == client.token
            client.click(action="back")
            assert client.dialog() == client.token
            print("PASS: full document navigation returns to the active request", flush=True)
            client.click()
            connection, _ = backend.accept()
            with connection:
                connection.settimeout(5)
                size = read_varint(connection)
                handshake = exact(connection, size)
                assert handshake[0] == 0, "Expected backend handshake"
            print("PASS: valid acceptance releases initial backend handshake", flush=True)
            accepted_name = client.name
            accepted_id = client.player_id
        finally:
            client.close()
        time.sleep(0.5)
        client = Client(accepted_name, accepted_id, expect_dialog=False)
        try:
            connection, _ = backend.accept()
            with connection:
                connection.settimeout(5)
                size = read_varint(connection)
                handshake = exact(connection, size)
                assert handshake[0] == 0, "Expected backend handshake for accepted reconnect"
            print("PASS: accepted reconnect skips the dialog", flush=True)
        finally:
            client.close()
        assert database is not None
        locked = sqlite3.connect(database, isolation_level=None)
        try:
            locked.execute("BEGIN IMMEDIATE")
            client = Client()
            try:
                client.click()
                disconnected_without_backend(backend, client, 10)
                print("PASS: failed SQLite save does not release the backend connection", flush=True)
            finally:
                client.close()
                locked.execute("ROLLBACK")
        finally:
            locked.close()
        client = Client()
        try:
            client.click(action="leave")
            packet, _ = client.receive()
            assert packet == 2
            assert not select.select([backend], [], [], 0.5)[0]
            print("PASS: leave disconnects without backend contact", flush=True)
        finally:
            client.close()
        client = Client()
        try:
            client.send(3)
            packet, _ = client.receive()
            assert packet == 2
            assert not select.select([backend], [], [], 0.5)[0]
            print("PASS: unsolicited configuration completion cannot bypass gate", flush=True)
        finally:
            client.close()


if __name__ == "__main__":
    main()
