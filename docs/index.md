---
title: Overview
description: ConsentGate shows your rules and policies to players before they join, and records their acceptance.
order: 1
---

# ConsentGate

ConsentGate shows rules, policies, and other documents to players before they are admitted, and only lets them in after they accept. Documents live in local files, so no website or extra hosting is needed.

## Where it runs

| Platform | When players see the documents |
| --- | --- |
| Velocity | On the proxy, before any backend server connection |
| Paper | On the server, before the player enters the world |

Install it on the proxy for network-wide agreements, on a standalone Paper server, or on both for separate requirements. See [choosing where to install](installation.md#choose-where-to-install).

## Features

- Full documents readable in game, with pages, formatting, and translations.
- Explicit acceptance: every required document has its own checkbox.
- Document versions: players accept again when a document changes.
- Java dialogs for Java players and native forms for Bedrock players through Geyser.
- SQLite storage by default, or MySQL/MariaDB with a local cache for sharing consent across servers.
- Acceptance history that is kept on reset, with player UUIDs and timestamps only.
- Admin commands to check a player's status, reset consent, preview the login flow, and reload files without a restart.

## How a player gets in

1. The player connects. ConsentGate checks whether they already accepted the current versions of all required documents.
2. If they did, they continue right away.
3. If not, they see a summary with a checkbox and a Read button for each required document.
4. They check every box and press Continue. ConsentGate saves the acceptance.
5. Only after the save succeeds is the player let through. Leaving, timing out, or a failed save never lets them in.

See [player experience](player-experience.md) for what the screens look like on Java and Bedrock.

## Start here

- [Installation](installation.md)
- [Configuration](configuration.md)
- [Writing documents](documents.md)
- [Commands and permissions](commands.md)
- [Troubleshooting](troubleshooting.md)

The bundled Terms of Service and Privacy Policy templates are starting points only. Review and adapt them before use. ConsentGate records what players accepted; it does not make your documents legally compliant.
