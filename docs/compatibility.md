---
title: Compatibility
description: Tested platform, client, database, and plugin versions, plus known conflicts.
order: 10
---

# Compatibility

These are the setups ConsentGate has been tested with. Newer versions often work, but test them on your own setup first.

## Platforms

| Platform | Tested |
| --- | --- |
| Velocity | 3.4.0 build 563 and a 4.1.0 snapshot, with PacketEvents 2.13.0 |
| Paper | Paper 1.21.7 build 32 (Java 21), and a Paper-compatible 26.2 server |
| BungeeCord, Spigot | Not supported yet |

On Velocity, tests covered normal routing, server switches, forced hosts, fallback when the main backend is down, and accepted rejoin, with no backend connection before acceptance.

## Clients

| Client | Status |
| --- | --- |
| Java 1.21.6 and newer | Supported. Tested with 1.21.6, 1.21.7, 1.21.8, and 26.1. |
| Java before 1.21.6 | Not supported. Dialogs do not exist before 1.21.6. |
| Bedrock, native forms | Tested with Geyser 2.11.2 build 1235 and Floodgate 2.2.5 build 138, on Velocity and Paper |
| Bedrock, translated dialogs | Tested through Geyser |

## Databases

| Database | Tested |
| --- | --- |
| SQLite | Bundled |
| MySQL | 8.4.8 |
| MariaDB | 11.8.6 |

Both use the bundled MariaDB Connector/J 3.5.10, with TLS, connection loss, outage, and backup/restore checks.

## Other plugins

| Plugin | Notes |
| --- | --- |
| ViaVersion / ViaBackwards | Tested with 5.11.1 snapshots on Paper 26.2 |
| GrimAC | Use a build that includes Grim's fix [`29d8fb4`](https://github.com/GrimAnticheat/Grim/commit/29d8fb4fd0ca6c9d69b7a2a4f6a733669d3be0e3), such as `2.3.74-8eb5f28`. Older builds (like revision `63a684d`) disconnect players with `disconnect.timeout` after about 60 seconds on the consent screen ([Grim issue #2844](https://github.com/GrimAnticheat/Grim/issues/2844)). Check the exact build, not just the `2.3.74` label. |
| nLogin | Known conflict: nLogin 2.0.18 can fail to open its Bedrock password form with a `LinkageError` at `FloodgatePlayer.sendForm` when Geyser and Floodgate are both on the Velocity proxy. This is an nLogin issue, not a ConsentGate one; resetting consent does not fix it. Players with a saved nLogin session are not affected. |

Plugins that hold a player in the configuration stage for a long time after they join may need their own testing.

## Paper notes

Paper 1.21.7 and newer have the dialog and configuration APIs ConsentGate needs. ConsentGate builds against the 1.21.7 API. Older builds are not supported. If Paper reports a missing method, check your exact build against the table above.
