# ConsentGate 0.1.0

First release for Paper and Velocity. Download the complete package and its `.sha256` file from [GitHub Releases](https://github.com/kaizen-network/ConsentGate/releases/tag/v0.1.0). Release access follows repository visibility.

## Included

- Paper and Velocity admission with required documents and explicit acceptance before world or backend entry.
- Java dialogs, optional native Bedrock forms through local Geyser, translations, and configurable document versions.
- SQLite, MySQL, and MariaDB acceptance history, with an optional persistent cache for remote storage outages.
- Administrator preview, document viewing, status, reset, validation, and reload commands.
- Failure handling that denies admission when required acceptance cannot be saved.
- GitHub Actions checks for JVM, Python, and Node tests, with complete downloadable packages and manual release publication.

## Installation

Use `ConsentGate-Paper-0.1.0.jar` on Paper or `ConsentGate-Velocity-0.1.0.jar` on Velocity. Install the gate once for each admission requirement, on the standalone server or proxy. Velocity also requires PacketEvents 2.13.0. Java 21 is the bytecode minimum; follow the selected server's Java requirement. Building requires JDK 25.

Start with a test installation and follow the [installation guide](13-installation.md). Fresh installations are disabled. Review and adapt the inactive example documents before enabling the gate. Back up the plugin data folder before replacing a prototype JAR, keep existing document revisions intact, and verify acceptance and reconnect after the change.

## Validation

The September 23 `0.1.0` build passed 154 JVM test executions, 15 Python tests, and 11 Node tests. Earlier dedicated checks cover both remote databases, TLS, outages, recovery, and platform routing; those were not rerun for release preparation.

Each package records its source tree and JVM test count in `BUILD.json`, includes dependency notices and source materials, and supplies SHA-256 checksums. A working-tree package identifies its base commit separately from its uncommitted source snapshot.

## Compatibility notes

- Native Bedrock forms require Geyser on the same server or proxy. ConsentGate does not bundle Geyser, Floodgate, or Cumulus.
- BungeeCord, plain Spigot, external imports, and provider migration tools are outside this release.
- Compatibility evidence applies to the recorded setups, not every newer client, platform, or plugin combination.
