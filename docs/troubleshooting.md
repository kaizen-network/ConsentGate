---
title: Troubleshooting
description: Common problems and what to check.
order: 11
---

# Troubleshooting

| Problem | What to check |
| --- | --- |
| No consent screen appears | Is `enabled: true`? Did startup and reload succeed? Do your documents end in `.yml` and have `required: true`? Has this player already accepted the current versions? |
| Velocity says PacketEvents is missing | Install the full PacketEvents plugin for Velocity on the proxy, then restart. |
| Paper fails with a missing method | Check your Paper build against [compatibility](compatibility.md). Building against an API does not prove every runtime build works. |
| Every login fails with "ConsentGate is not set up for this server" | The JAR does not match the server. Read the error in the log: use the Paper JAR on Paper 1.21.7 or newer, and the Spigot JAR on Spigot 1.21.8 or newer. See [wrong JAR or old server](compatibility.md#wrong-jar-or-old-server). |
| On Spigot, a player sees the world behind the consent screen | Expected in [Spigot hold mode](compatibility.md#spigot-hold-mode). The player stays frozen and hidden until they accept. |
| `validate` fails after editing a document | Read the reported locale, field, and version. Text changes need a new version; see [versions](documents.md#versions). |
| Reload says it is busy | Players are on the consent screen or database work is running. Try again shortly. |
| Players are disconnected after about 60 seconds on the screen | If GrimAC is installed, check the exact build; see [compatibility](compatibility.md#other-plugins). Do not turn off the anticheat or shorten reading time to hide it. |
| Native forms fail on Paper or Spigot | Check that Geyser-Spigot is enabled on the same server and read the log. |
| Bedrock players get "End of stream" after accepting | Read the server log for the reason. A `LinkageError` at nLogin's `FloodgatePlayer.sendForm` is a separate nLogin problem; see [compatibility](compatibility.md#other-plugins). |
| Backend is unreachable after accepting | Check the proxy route, forced hosts, forwarding settings, and whether the backend is up. |
| Players cannot join while the database is down | Expected for players without a fresh cache entry. See [cache](remote-storage.md#cache). |
| Startup stops with a schema error | See [failed installation or upgrade](backups.md#failed-installation-or-upgrade). |

Treat any startup error with the gate enabled as a failed installation. After fixing it, check that a new test player cannot get in without accepting.
