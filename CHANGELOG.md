# Changelog

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
