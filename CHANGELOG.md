# Changelog

## 1.1.0 (2026-11-06)

A Spigot JAR with a hold mode, and a documented BungeeCord setup.

### Added

- `ConsentGate-Spigot`, a separate JAR for Spigot 1.21.8 and newer. Spigot has no way to hold players before they enter the world, so this is a hold mode with a weaker guarantee: players enter the world but stay frozen and hidden until they accept. They see the same documents and checkboxes as on Paper, and their join message is shown only after they accept. Other plugins still see the join. Use Paper for the full guarantee. See [Spigot hold mode](docs/compatibility.md#spigot-hold-mode). Like the Paper JAR, it sends anonymous usage statistics to bStats; see [turning it off](docs/storage.md#turning-it-off).
- BungeeCord setup: there is no BungeeCord JAR. Install the Paper JAR (full guarantee) or the Spigot JAR (hold mode) on every backend, with the same MySQL/MariaDB database, so one acceptance counts everywhere. See [BungeeCord](docs/installation.md#bungeecord).

### Changed

- Where the Paper JAR refuses every login (Spigot, and Paper 1.21 to 1.21.6), players now see "ConsentGate is not set up for this server. Ask the server owner to install the right ConsentGate file." On Spigot, its log now points to the Spigot JAR. The Spigot JAR refuses every login on Paper, and on Spigot without dialogs (before 1.21.6), with the same message to players.

### Upgrading from 1.0.0

Paper and Velocity: back up the plugin folder, stop the server or proxy, replace the JAR, and start again. Nothing else changes.

Spigot: remove the Paper JAR if you installed it, add `ConsentGate-Spigot-1.1.0.jar`, and start again. The plugin folder and files are the same as on Paper, so moving to Paper later only needs a JAR swap. Read [Spigot hold mode](docs/compatibility.md#spigot-hold-mode) first. To keep usage statistics off from the first start, turn bStats off before starting; see [turning it off](docs/storage.md#turning-it-off).

## 1.0.0 (2026-11-05)

Anonymous usage statistics, and a safe stop on servers that are not Paper.

### Added

- Paper and Velocity send anonymous, server-level usage statistics to bStats: player count, server software and version, Java and operating system, ConsentGate version, and a random server ID. No player data, documents, or consent records are sent. See [usage statistics](docs/storage.md#usage-statistics-bstats) for the full list and how to turn it off.

### Fixed

- On Spigot 1.21 or newer, and on Paper 1.21 to 1.21.6, the Paper JAR now loads, logs an error, and refuses every login. Before, these servers did not load ConsentGate and let players in without the gate. Servers older than 1.21 still do not load it.
- On Velocity, a player who leaves while their consent screen is closing no longer causes a "Could not disconnect a denied connection" warning. In rare cases this also logged "Consent save failed" after the acceptance had been saved.

### Upgrading from 0.3.0

Back up the plugin folder, stop the server or proxy, replace the JAR, and start again. To keep usage statistics off from the first start, turn bStats off before starting; see [turning it off](docs/storage.md#turning-it-off).

## 0.3.0 (2026-10-09)

Translatable error messages and automatic config updates.

### Added

- Kick messages for errors, such as a busy gate, a timeout, or a storage failure, can be changed and translated with the new `error-*` keys in the message files.
- Missing settings are added to `config.yml`, and missing keys to the bundled message files, when the plugin starts. Your values and comments are kept, and the server log lists what was added. If a file cannot be updated safely, it is left unchanged, the log shows a warning, and startup continues. Make a file read-only to keep it as it is.

### Changed

- Paper and Velocity now use the same wording for the same error. A few messages changed slightly, for example a Paper timeout now shows "Consent request timed out."

### Upgrading from 0.2.0

Back up the plugin folder, stop the server or proxy, replace the JAR, and start again. The new message keys are added to `en-US.properties` and `id-ID.properties` automatically.

## 0.2.0 (2026-09-25)

Easier first setup.

### Added

- Empty MySQL/MariaDB databases get their tables automatically. No SQL import is needed. First setup needs `CREATE`, `REFERENCES`, `SELECT`, `INSERT`, and `UPDATE` on the dedicated database.
- A disabled gate can be enabled with `consentgate reload` after setting `enabled: true`. `consentgate validate` also works while disabled, without opening storage.

### Changed

- The generated config and starter documents explain setup, scopes, version changes, and setting limits.
- Existing tables are checked and never overwritten to repair or upgrade a schema.

### Upgrading from 0.1.0

Back up the plugin folder, stop the server or proxy, replace the JAR, and start again. Existing config, messages, documents, and consent records are kept, and document versions do not need to change. Copy new settings or comments from the [default config](presentation/src/main/resources/config.yml) if you want them.

## 0.1.0 (2026-09-23)

First release for Paper and Velocity.

### Added

- Required documents and explicit acceptance before backend connection (Velocity) or world entry (Paper).
- Java dialogs with pages, formatting, translations, and an optional language selector.
- Optional native Bedrock forms through Geyser on the same proxy or server.
- Document versions: players accept again when a document changes.
- SQLite, MySQL, and MariaDB storage, with a local cache for short database outages.
- Acceptance history kept on reset.
- Admin commands: status, reset, preview, document viewing, validate, and reload.
