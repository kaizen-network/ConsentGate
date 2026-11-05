# Development

## Build

Use JDK 25 to run the included Gradle wrapper. The bytecode targets Java 21.

```powershell
.\gradlew.bat build
```

On Linux or macOS, run `./gradlew build`. A network connection is needed (see [releasing](releasing.md#build-inputs) for why). Outputs:

- `platform-paper/build/libs/ConsentGate-Paper-<version>.jar`
- `platform-spigot/build/libs/ConsentGate-Spigot-<version>.jar`
- `platform-velocity/build/libs/ConsentGate-Velocity-<version>.jar`

The version is set in `build.gradle.kts`.

## Project layout

```text
core/                 Documents, sessions, admission rules, configuration, SQLite and remote SQL storage
presentation/         Safe text formatting, interface messages, and bundled defaults (config, starter documents)
integration-bedrock/  Optional native Bedrock forms through Geyser's Cumulus
platform-velocity/    Velocity connection handling and dialog transport through PacketEvents
platform-paper/       Paper connection handling and native dialogs
platform-spigot/      Spigot hold mode: pre-login check, freeze listener, and dialogs through Spigot's BungeeCord dialog API
tools/                Local test runners, probes, and the release packager
docs/                 Admin docs; docs/contributing/ for contributors
```

Each platform JAR is shaded with the shared core, relocated SnakeYAML, MariaDB Connector/J, and bStats, SQLite JDBC with its native libraries, the remote schema, and license notices. Paper also bundles and relocates Adventure NBT and Examination for strict dialog response parsing. Spigot has no Adventure, so the Spigot JAR bundles and relocates Adventure (API, key, MiniMessage, and the Gson text serializer), Option, and Examination under `io.github.consentgate.internal.kyori`. Platform APIs, Gson, PacketEvents, and Geyser stay external. The plugin never downloads anything at runtime.

## Code guidelines

- Keep platform types out of `core`. Platforms plug in through small interfaces for holding and releasing a connection, showing dialogs, identity, scheduling, and storage.
- Prefer native platform APIs. Keep packet-level or internal access inside the platform adapter.
- Never block a game thread or network event loop. Database work runs on the bounded worker queue. On Spigot, the pre-login thread waits for the acceptance check with a short bound, and Bukkit calls go back to the main thread.
- Never weaken a check (tokens, checkbox validation, connection hold) to make a test pass.
- Add a regression test when a protocol, packaging, or storage failure is found.
- Keep docs, examples, and commit messages neutral. Do not commit private server details, credentials, or deployment notes.

See [architecture](architecture.md) for the reasoning behind these rules.

## GitHub Actions

The [build workflow](../../.github/workflows/build.yml) runs on pushes to `main`, pull requests to `main`, and manual runs. It uses Ubuntu 24.04, Temurin JDK 25, Python 3.13, and Node 24, and:

1. Checks the docs (`tools/check_docs.py`), then runs the Python tool tests and the Node probe helper tests.
2. Runs the Gradle build, including the Bedrock tests against all three packaged JARs.
3. Builds the complete package with verified dependency sources.

Successful runs keep the package and its SHA-256 file for 14 days. Test and dependency-verification reports are kept for 7 days, also after a failed build. Actions are pinned to full commit hashes, permissions are read-only, and checkout does not keep credentials. The workflow does not tag or publish anything; see [releasing](releasing.md).
