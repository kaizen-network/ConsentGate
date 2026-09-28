# Protocol notes

## Dialog response framing

The Java client sends dialog button responses as `ServerboundCustomClickActionPacket`. Its payload is an optional NBT tag wrapped in a length prefix (limit 65,536 bytes):

1. A VarInt with the byte length of the encoded payload.
2. The payload: an anonymous NBT tag, or a single `TAG_END` (`00`) byte when there is no tag.

There is no separate boolean "present" flag. This was confirmed from the official Mojang 1.21.6 and 1.21.8 client JARs and their mappings: the packet codec combines `optionalTagCodec` with `lengthPrefixed(65536)`. The codec is the same in both versions.

Example, a compound containing the checked `document_0` checkbox (after the action identifier):

| Encoder | Payload bytes |
| --- | --- |
| Correct (Mojang) | `10 0a 01 00 0a 64 6f 63 75 6d 65 6e 74 5f 30 01 00` (length 16, then the compound) |
| Wrong (presence flag) | `01 0a 01 00 0a 64 6f 63 75 6d 65 6e 74 5f 30 01 00` |

Other values: no payload is `01 00`, and an empty compound is `02 0a 00`.

PacketEvents 2.13.0 reads and writes the length-prefixed form ([`WrapperCommonClientCustomClickAction`](https://github.com/retrooper/packetevents/blob/v2.13.0/api/src/main/java/com/github/retrooper/packetevents/wrapper/common/client/WrapperCommonClientCustomClickAction.java)), and ConsentGate uses that wrapper.

## Bot library bug

`minecraft-protocol` 1.68.0 with `minecraft-data` 3.115.0 defines this payload as `option<anonymousNbt>`: a boolean flag followed by the NBT, without the length. PacketEvents reads the flag byte `01` as a length of one, which causes an end-of-data error. Upstream tracks it in [minecraft-data#1222](https://github.com/PrismarineJS/minecraft-data/issues/1222) and [minecraft-data#1196](https://github.com/PrismarineJS/minecraft-data/pull/1196).

So the probes use their own length-prefixed encoder. Rules:

- Keep the plugin decoder strict. Do not add fallback parsing for other layouts.
- `tools/test_dialog_framing.py` checks the probe encoder against the fixed bytes above.
- The Velocity probe includes the wrong presence-flag layout as a rejection test.
- For bot tests, use a fixed library version or a documented test-only encoder. Do not describe bot results as an unmodified client.
