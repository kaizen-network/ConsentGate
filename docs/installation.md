---
title: Installation
description: Install ConsentGate on Velocity or Paper, set up your documents, and enable the gate.
order: 2
---

# Installation

Try ConsentGate on a test server or proxy first.

## Requirements

| Item | Requirement |
| --- | --- |
| Java | Java 21 or newer, or the newer version your server or proxy needs |
| Java client | Minecraft Java 1.21.6 or newer (dialogs were added in 1.21.6). See [compatibility](compatibility.md) for tested versions. |
| Velocity | A compatible Velocity build, plus the Velocity build of [PacketEvents](https://github.com/retrooper/packetevents) 2.13.0 on the same proxy |
| Paper | A Paper build with the dialog and async configuration APIs (1.21.7 or newer). No extra plugins needed. |
| Bedrock (optional) | [Geyser](https://geysermc.org/download) on the same proxy or server. On Paper, use Geyser-Spigot. |
| Storage | Nothing extra. SQLite is built in, and MySQL/MariaDB support is bundled. The plugin folder must be writable. |

Use the full plugin download of each dependency, not a thin API JAR from Maven.

ConsentGate does not need GrimAC, a separate Cumulus plugin, a website, or a database server. Floodgate is not a direct dependency; whether you need it depends on your Geyser authentication setup ([Geyser setup guide](https://geysermc.org/wiki/geyser/setup/)).

## Choose where to install

- **Velocity:** install ConsentGate and PacketEvents on the proxy. Players accept before any backend connection. The backends need their normal forwarding and access restrictions, not another ConsentGate for the same requirement.
- **Paper without a proxy:** install the Paper JAR on the server. Players accept before entering the world.
- **Both:** for separate network and server agreements, for example `scope: network` on Velocity and `scope: survival` on the Survival server. Players can be asked at both. See [scopes](configuration.md#scopes-and-multiple-servers).

A proxy gate cannot protect a backend that players can reach directly. Keep your backends firewalled or restricted to the proxy.

Do not put Velocity's PacketEvents JAR on Paper, or the Paper ConsentGate JAR on Velocity. Do not change authentication or turn off forwarding security to make the dialog work.

## First installation

1. Back up your server or proxy, then stop it.
2. Download the JAR for your platform from the [releases page](https://github.com/kaizen-network/ConsentGate/releases), or [build it from source](https://github.com/kaizen-network/ConsentGate/blob/main/docs/contributing/development.md). Put it in `plugins/`, with only one ConsentGate JAR there. On Velocity, also add PacketEvents for Velocity.
3. Start once. ConsentGate creates its config, example documents, and message files, and starts disabled. Check the log for startup errors. It can keep running while you edit.
4. Open the plugin folder: `plugins/consentgate/` on Velocity, or `plugins/ConsentGate/` on Paper. Capitalization matters on Linux.
5. Edit `documents/terms.yml.example` and `documents/privacy.yml.example`. Replace every bracketed placeholder, remove sections that do not apply, review each translation, and choose your own document versions. Rename each file you want to use so it ends in `.yml`. Files ending in `.example` are not loaded. See [writing documents](documents.md).
6. In `config.yml`, set `enabled: true`. Keep SQLite, or fill in MySQL/MariaDB settings using the [remote storage guide](remote-storage.md). Choose a `scope`, and give players enough reading time with `gate.timeout-seconds`. Keep `bedrock.native-forms: false` for now.
7. In the console, run `consentgate validate`, then `consentgate reload`. Reload opens storage and enables the gate. A failed reload keeps it disabled.
8. Connect with a test player that has not accepted yet. Read each document, try Leave, then accept. Rejoin: the dialog should not appear again. On Velocity, check that the player only reaches the backend after accepting.

To repeat the test, use [offline reset](commands.md#reset-a-player) instead of deleting the database.

## Updating

1. Back up the plugin folder and stop the server or proxy.
2. Replace the old ConsentGate JAR with the new one. Remove the old file if its name is different.
3. Start again.

Your config, messages, and documents are kept. On start, missing settings are added to `config.yml` and missing messages are added to the bundled `en-US` and `id-ID` message files, using their default values. The server log lists what was added. Check the [changelog](https://github.com/kaizen-network/ConsentGate/blob/main/CHANGELOG.md) for anything that needs action.

## Bedrock

1. Set up Geyser on the same proxy or server and check that Bedrock players can connect normally.
2. Set `bedrock.native-forms: true`, then run `consentgate validate` and `consentgate reload`.
3. Test reading, closing forms, acceptance, and reconnect with your own Geyser and login setup.

Without native forms, Bedrock players get Geyser's translated version of the Java dialogs. A Geyser Standalone instance on another machine is not supported for native forms. See [player experience](player-experience.md#bedrock).
