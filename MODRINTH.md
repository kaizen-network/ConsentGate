![ConsentGate: in-game agreements for Minecraft servers and networks](https://kaizenmc.id/api/software/assets/consentgate/docs/images/banner.webp)

Show your rules, terms, and privacy policy to players **before** they join, and only let them in after they accept. Documents live in local files, so no website or extra hosting is needed.

- **Velocity:** players accept before any backend connection.
- **Paper:** players accept before entering the world.

![Required agreements with Read and Continue buttons](https://kaizenmc.id/api/software/assets/consentgate/docs/images/required-agreements.png)

## Features

- Full documents readable in game, with pages, formatting, and translations.
- One checkbox per required document. Nothing is accepted by closing the screen or timing out.
- Document versions: players accept again when a document changes.
- Java dialogs, plus native Bedrock forms through Geyser.
- SQLite by default, or MySQL/MariaDB to share consent across servers, with a cache for short database outages.
- Acceptance history is kept on reset, with player UUIDs and timestamps only.
- Admin commands to check a player, reset consent, preview the login flow, and reload files without a restart.
- English and Indonesian included; add any language with a file.

![Reading a document, page one of two](https://kaizenmc.id/api/software/assets/consentgate/docs/images/document-reader.png)

## Requirements

| Platform | Needs |
| --- | --- |
| Velocity | Java 21+, Minecraft Java 1.21.6+ clients, and [PacketEvents](https://modrinth.com/plugin/packetevents) 2.13.0 on the same proxy |
| Paper | Java 21+, Paper 1.21.7+, Minecraft Java 1.21.6+ clients |
| Bedrock (optional) | [Geyser](https://geysermc.org/download) on the same proxy or server |

## Quick start

1. Put the JAR for your platform in `plugins/` (with PacketEvents on Velocity) and start once.
2. Edit the two starter documents in `documents/`, then rename them to end in `.yml`.
3. Set `enabled: true` in `config.yml`.
4. Run `consentgate validate`, then `consentgate reload`.

The starter Terms of Service and Privacy Policy are templates. Review and adapt them before use.

## Links

- [Documentation](https://kaizenmc.id/software/consentgate)
- [Changelog](https://kaizenmc.id/software/consentgate/changelog)
- [Source code](https://github.com/kaizen-network/ConsentGate)

Made by [Kaizen Network](https://kaizenmc.id). Licensed under GPL-3.0-only.
