"""Golden framing checks based on the Mojang codec trace in docs/09-protocol-compatibility-findings.md."""

import unittest

from probe_velocity import Client


class DialogFramingTest(unittest.TestCase):
    def encoded_payload(self, payload):
        client = Client.__new__(Client)
        client.token = "fixture"
        sent = []
        client.send = lambda packet, body: sent.append((packet, body))
        client.click_nbt(payload)
        packet, body = sent[0]
        self.assertEqual(8, packet)
        expected_id = b"consentgate:accept/fixture"
        self.assertEqual(bytes([len(expected_id)]) + expected_id, body[:len(expected_id) + 1])
        return body[len(expected_id) + 1:]

    def test_absent_tag_has_length_one_and_tag_end(self):
        self.assertEqual(bytes.fromhex("01 00"), self.encoded_payload(b"\x00"))

    def test_empty_compound_has_length_two_without_presence_flag(self):
        self.assertEqual(bytes.fromhex("02 0a 00"), self.encoded_payload(b"\x0a\x00"))

    def test_checkbox_compound_has_length_sixteen(self):
        compound = bytes.fromhex("0a 01 00 0a 64 6f 63 75 6d 65 6e 74 5f 30 01 00")
        self.assertEqual(bytes.fromhex("10") + compound, self.encoded_payload(compound))
        self.assertNotEqual(b"\x01" + compound, self.encoded_payload(compound))

    def test_length_is_varint_not_single_byte(self):
        # An anonymous string tag: type + unsigned-short length + 125 ASCII bytes.
        tag = b"\x08\x00\x7d" + b"x" * 125
        self.assertEqual(b"\x80\x01" + tag, self.encoded_payload(tag))


if __name__ == "__main__":
    unittest.main()
