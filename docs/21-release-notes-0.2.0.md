# ConsentGate 0.2.0

This release makes first setup easier: MySQL/MariaDB tables are created automatically, and a disabled gate can be enabled with `/consentgate reload`.

## Downloads

Get the JAR for your platform from the [0.2.0 release](https://github.com/kaizen-network/ConsentGate/releases/tag/v0.2.0):

- `ConsentGate-Velocity-0.2.0.jar`, with PacketEvents installed separately on the proxy.
- `ConsentGate-Paper-0.2.0.jar`, with no separate PacketEvents requirement.

The release also includes the complete package with documentation, project and dependency sources, notices, and checksums. `SHA256SUMS` on the release covers both standalone JARs and the complete package. The JARs are the same files included in that package. Repository access is required for downloads.

## Changes

- Empty MySQL/MariaDB databases get their tables automatically. Existing tables are checked and never overwritten to repair or upgrade a schema. First setup needs `CREATE`, `REFERENCES`, `SELECT`, `INSERT`, and `UPDATE` on the dedicated database.
- After a successful disabled startup, set `enabled: true` and run `/consentgate reload`. Validation also works while disabled without opening storage. Failed reloads keep the previous state.
- Generated config and document templates explain setup, scopes, version changes, and setting limits. Admin guides now match the implemented database and reload behavior.
- Separate Velocity and Paper scopes, shared consent requirements, and preserved config files are explained in the README and installation guide.

## Install or upgrade

For a fresh installation, follow the [installation guide](13-installation.md). Download the matching JAR directly; building from source or unpacking the full package is optional.

To upgrade from `0.1.0`, back up the plugin folder, stop the proxy/server, and replace its old ConsentGate JAR with the matching `0.2.0` JAR. Keep only one ConsentGate JAR per installation. Keep the data folder, reviewed documents, and existing consent records, then start again. Existing config, message files, and templates are preserved; copy new settings or comments from the current examples if needed.

Document versions and database schema versions are unchanged by this plugin update. Moving from SQLite to MySQL/MariaDB does not transfer consent records automatically. Disabling an active gate, changing its scope or storage/cache settings, and changing `gate.max-pending` still require a restart. Reload does not kick players already online.

## Validation

Release checks run the JVM, Python, and Node tests, including the Bedrock provider tests against both packaged JARs. The package records its exact commit, source tree, and JVM test count in `BUILD.json`. Publication follows a successful GitHub Actions run for that commit.

During development, local MySQL and MariaDB each passed 19 database tests, including automatic setup and preservation of incomplete or newer schemas, followed by dump/restore checks. The local Velocity connection probe passed disabled startup, failed activation, validation without storage writes, enabling through reload, and the existing admission checks. These checks are separate from the release build; no new live-server or real-client compatibility claim is made by this version bump. Earlier client evidence remains in the [recorded results](19-final-client-check.md).
