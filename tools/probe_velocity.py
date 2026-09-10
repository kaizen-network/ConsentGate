"""Local-only wire checks for the prototype using Minecraft Java protocol 772.

Requires a running offline test proxy on 127.0.0.1:25590 with compression off
and its initial backend set to 127.0.0.1:25591. No real account is used.
"""

import re
import select
import socket
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
    def __init__(self):
        self.socket = socket.create_connection(("127.0.0.1", 25590), timeout=5)
        self.send(0, varint(772) + string("localhost") + struct.pack(">H", 25590) + varint(2))
        self.send(0, string("Probe_" + uuid.uuid4().hex[:8]) + uuid.uuid4().bytes)
        packet, data = self.receive()
        assert packet == 2, ("Expected login success", packet, data)
        self.send(3)
        self.token = self.dialog()

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

    def dialog(self):
        for _ in range(20):
            packet, body = self.receive()
            if packet == 18:
                match = re.search(rb"consentgate:accept/([0-9a-f-]{36})", body)
                assert match, "Missing session action in dialog"
                return match.group(1).decode()
            assert packet != 2, ("Disconnected before dialog", body)
        raise AssertionError("No dialog received")

    def click(self, action="accept", checked=True, token=None):
        nbt = b"\x0a\x01\x00\x05agree" + bytes([int(checked)]) + b"\x00"
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


def main():
    with socket.socket() as backend:
        backend.bind(("127.0.0.1", 25591))
        backend.listen()
        backend.settimeout(5)
        client = Client()
        try:
            no_backend(backend, client, 35)
            print("PASS: dialog and keepalives without backend contact for 35 seconds", flush=True)
            client.click(checked=False)
            assert client.dialog() == client.token
            no_backend(backend, client, 0.5)
            client.click(token=str(uuid.uuid4()))
            no_backend(backend, client, 0.5)
            print("PASS: unchecked and forged acceptance remain blocked", flush=True)
            client.click()
            connection, _ = backend.accept()
            with connection:
                connection.settimeout(5)
                size = read_varint(connection)
                handshake = exact(connection, size)
                assert handshake[0] == 0, "Expected backend handshake"
            print("PASS: valid acceptance releases initial backend handshake", flush=True)
        finally:
            client.close()
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
