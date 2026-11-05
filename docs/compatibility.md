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
| Spigot (hold mode) | Spigot 1.21.8 and 26.1.2, built with BuildTools (Java 25) |
| BungeeCord | No proxy JAR. BungeeCord build 2111 with a Paper 1.21.7 backend (Paper JAR) and a Spigot 1.21.8 backend (Spigot JAR) sharing one MariaDB database |

On Velocity, tests covered normal routing, server switches, forced hosts, fallback when the main backend is down, and accepted rejoin, with no backend connection before acceptance.

Behind BungeeCord, a player accepted once on either backend, then switched to the other and back without being asked again. The Paper backend held the player before world entry, and the Spigot backend held them in the world until they accepted. The proxy sees the player before they accept, so proxy plugins such as `/glist` or a chat bridge already list them. On a Spigot backend, proxy commands such as `/msg` and `/server` still work while the player is held, because they never reach the backend.

## Clients

| Client | Status |
| --- | --- |
| Java 1.21.6 and newer | Supported. Tested with 1.21.6, 1.21.7, 1.21.8, and 26.1. On Spigot, tested so far with bot clients for 1.21.8 and 26.1, not with the game itself. |
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

## Spigot hold mode

Spigot has no step before world entry where a plugin can hold a player, so the Spigot JAR uses a hold mode instead:

- Players who already accepted join normally and are never held.
- Other players join the world, then see the same screens as on Paper. Until they accept, they cannot move, chat, type commands, interact, break or place blocks, use inventories, drop or pick up items, take or deal damage, lose hunger or air, or be targeted by mobs. Other players cannot see them in the world or in the tab list.
- Their join message is hidden and shown after they accept. If they leave or are kicked first, no join or quit message is shown.
- Plugin teleports still work, so spawn plugins can move a held player.
- A held player is taken off any vehicle they joined in. The vehicle stays in the world.
- Leave, a timeout, or a failed save kicks the player. Nothing is saved, and the next join holds them again.
- Stopping or reloading the server kicks held players. After `/reload`, every online player is held and hidden for about a second while their acceptance is checked again. Players who already accepted are then released; anyone else stays held.

What this mode cannot do:

- Other plugins see the join, and their join handlers run before acceptance: welcome messages they send themselves, kits, spawn teleports, Discord join notices, and MOTDs.
- Plugins that ignore cancelled events, or undo the cancel afterwards, can still act on a held player. Vanish, staff, and tab-list plugins may show held players.
- The player count in the server list and in `/list` includes held players. Experience orbs and harmless potion effects are not blocked.
- Plugin messages from a held client still reach plugins, because Spigot has no way to cancel them. For example, a voice chat plugin can still complete its handshake.
- Clients older than 1.21.6 (through ViaBackwards) get no dialog and stay frozen until the timeout.

The Spigot JAR logs this warning at every start: "ConsentGate is running in Spigot hold mode. Players enter the world first and stay frozen and hidden until they accept. This is weaker than Paper, where players accept before entering the world. Other plugins still see each join. Use Paper for the full guarantee."

Spigot 1.21.6 and 1.21.7 have the same dialog API but were not tested. Bedrock players through Geyser-Spigot have not been tested on Spigot yet.

## Wrong JAR or old server

Each JAR refuses every login where it cannot protect players, and the log says why. Players see: "ConsentGate is not set up for this server. Ask the server owner to install the right ConsentGate file."

| Server | Paper JAR | Spigot JAR |
| --- | --- | --- |
| Paper 1.21.7 and newer | Full gate | Refuses every login. The log says to use the Paper JAR. |
| Spigot 1.21.6 and newer | Refuses every login. The log points to the Spigot JAR. | Hold mode |
| Spigot 1.21 to 1.21.5, Paper 1.21 to 1.21.6 | Refuses every login | Refuses every login |
| Older than 1.21 | Does not load | Does not load |

Servers older than 1.21 do not load ConsentGate at all, so it cannot block logins there.
