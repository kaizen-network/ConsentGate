# Dialog response encoding findings

Investigation date: 2026-09-12. The reproduced mismatch is in the installed bot library's protocol definition. No ConsentGate decoder change is needed for this finding. Full visual/client compatibility remains a separate validation task.

## Independent reference

The official Mojang Java 1.21.6 and 1.21.8 client JARs were downloaded using the official version manifest and verified against its SHA-1 values. Their client mappings identify `ServerboundCustomClickActionPacket` as `aav` and `ByteBufCodecs` as `zk`. Inspection used `javap -c -p`, not the bot library or the local probe.

| Artifact | SHA-1 |
| --- | --- |
| [Java 1.21.6 client](https://piston-data.mojang.com/v1/objects/740a125b83dd3447feaa3c5e891ead7fbb21ae28/client.jar) | `740a125b83dd3447feaa3c5e891ead7fbb21ae28` |
| [Java 1.21.6 client mappings](https://piston-data.mojang.com/v1/objects/848855615bc81e3db1c85e69b6afb150807a1261/client.txt) | `848855615bc81e3db1c85e69b6afb150807a1261` |
| [Java 1.21.8 client](https://piston-data.mojang.com/v1/objects/a19d9badbea944a4369fd0059e53bf7286597576/client.jar) | `a19d9badbea944a4369fd0059e53bf7286597576` |
| [Java 1.21.8 client mappings](https://piston-data.mojang.com/v1/objects/bdeb624c3aefba11d9d40f34bc96176350b549b6/client.txt) | `bdeb624c3aefba11d9d40f34bc96176350b549b6` |

Both packet initializers compose the resource identifier codec with an optional-tag codec wrapped in a 65,536-byte length limit. The 1.21.8 mapping and bytecode trace is:

1. `aav` initializes the payload codec using `zk.a(Supplier)` and `zk.d(int)` with 65,536.
2. Mappings identify those methods as `optionalTagCodec` and `lengthPrefixed`.
3. `zk$7` writes the optional tag through the nullable NBT writer. It does not write a boolean presence flag.
4. `zk$20` writes the encoded payload's byte length as a VarInt, then the payload bytes.
5. The nullable NBT writer uses a single `TAG_END` byte for an absent tag.

The packet initializer has the same codec construction in both inspected releases. There is no framing change between the minimum target and the reproduced 1.21.8 case.

## First differing byte

After the action identifier, a compound containing the checked `document_0` checkbox has these bytes. The packet ID and identifier are omitted here.

| Encoder | Payload bytes | Meaning |
| --- | --- | --- |
| Mojang format | `10 0a 01 00 0a 64 6f 63 75 6d 65 6e 74 5f 30 01 00` | Length 16, then an anonymous compound |
| Installed bot definition | `01 0a 01 00 0a 64 6f 63 75 6d 65 6e 74 5f 30 01 00` | Presence flag 1, then the same compound without its length |

PacketEvents reads the bot's `01` as a length of one byte. The resulting one-byte NBT buffer contains only the compound tag marker and cannot contain a complete compound. This accounts for the end-of-data error without involving a backend or protocol translator.

An absent payload is length one plus `TAG_END`, or `01 00`. An empty compound is length two plus compound and end markers, or `02 0a 00`. These are different values; neither uses an extra boolean flag.

## Compared implementations

The installed test stack uses `minecraft-protocol` 1.68.0, `minecraft-data` 3.115.0, and `prismarine-nbt` 2.8.0. Its 1.21.8 custom-click definition uses `option<anonymousNbt>`. That adds the incorrect boolean and omits the required byte length.

PacketEvents 2.13.0 reads and writes a length-prefixed NBT payload. Its installed bytecode agrees with its [tagged source](https://github.com/retrooper/packetevents/blob/v2.13.0/api/src/main/java/com/github/retrooper/packetevents/wrapper/common/client/WrapperCommonClientCustomClickAction.java). ConsentGate uses this wrapper; there is no alternate format-selection branch in that packet reader.

The bot project's [existing fix proposal](https://github.com/PrismarineJS/minecraft-data/pull/1196) and [1.21.8 issue](https://github.com/PrismarineJS/minecraft-data/issues/1222) describe the same mismatch. They support the finding, but the independent reference here is the official client bytecode. No upstream report was created by this investigation.

## Decision and regression coverage

Keep the existing plugin decoder. Do not add fallback parsing, relax acceptance checks, or update runtime dependencies to compensate for a test-library defect. The probe's existing length-prefixed encoding is correct for the two inspected releases; its source comment now points to this evidence.

The local runner accepts `--protocol 771` for 1.21.6 or `--protocol 772` for 1.21.8. The suite includes explicit rejection tests for the bot's incorrect presence flag, truncated NBT, and an oversized payload length. Each malformed case must disconnect without creating an acceptance event or contacting the backend.

Local test artifacts: Velocity JAR SHA-256 `fe53021f3168322cb6cb68f78699866fd098df3c306e4359847a10b0d02689ef`; PacketEvents JAR SHA-256 `e797f84abc349c137396e511ce4f0d7b85e385727a2e82e2ffb6bed0d2fe5c05`.

## Remaining limits

Verification completed on 2026-09-12: all 14 local proxy checks passed independently with protocol 771 and protocol 772. All four Python golden framing tests passed. No Java implementation, deployed plugin, shared bot dependency, or live acceptance record was changed during this investigation.

Official bytecode inspection is not a captured click from a running vanilla client. Synthetic tests establish packet handling and admission behavior, not visible layout or every newer release. Keep real-client checks at the minimum boundary and the previously tested newer release on the release checklist. The earlier native Bedrock checks remain separate evidence.

For future bot tests, use a verified fixed library release or an explicitly documented test-only encoder. Do not silently modify a shared bot installation, represent the workaround as an unmodified client, or copy official JARs into the repository.
