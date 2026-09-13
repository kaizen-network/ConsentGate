# ConsentGate

Configurable in-game agreements for Minecraft servers and networks.

ConsentGate lets administrators present rules, policies, and other documents before players are admitted. Documents live in local files with custom titles, full text, and independent versions. No website or additional hosting is required.

Fresh installations include inactive Terms of Service and Privacy Policy starter templates. Administrators must review and adapt them before use. Velocity also offers optional native Bedrock forms through a local Geyser installation, with separate document buttons and agreement toggles.

## Status

Early development. Both platforms support local documents, translations, SQLite acceptance, and administrator commands. Native Bedrock forms share one renderer, with real-client checks on Velocity and initial integration on Paper. Paper Bedrock runtime checks, broader failure testing, and real-client version coverage remain open. Neither artifact is a production release. See the [release progress](docs/16-release-progress.md).

## Requirements

Test installations only. Use Java 21 or newer, or the newer Java version required by your server/proxy. Building from source requires JDK 25. Java dialogs require a 1.21.6-or-newer client; this is a feature minimum, not a guarantee for every newer version. See the [tested version matrix](docs/05-development.md#validation-matrix).

| Installation | Plugin JAR | Additional requirement |
| --- | --- | --- |
| Velocity | `ConsentGate-Velocity-0.1.0-prototype.jar` | PacketEvents 2.13.0 for Velocity, installed on the same proxy |
| Paper | `ConsentGate-Paper-0.1.0-prototype.jar` | No separate plugin dependency for Java dialogs; Paper API 1.21.7 is the current compile target |
| Native Bedrock forms | Matching platform JAR | Geyser on the same proxy/server; optional and disabled by default. Paper runtime verification remains open |

SQLite is the default, with its driver bundled. Optional MySQL/MariaDB storage and a local SQLite cache are implemented. Both database products passed repository, TLS, socket-failure, and headless admission and outage checks on both platforms. Sustained load testing remains open. No website or GrimAC is required. BungeeCord and plain Spigot are not implemented yet. [Dependency details](docs/13-installation.md#requirements-and-dependencies) and [remote storage setup](docs/15-remote-storage.md).

## Quick start

1. [Build the prototype](docs/05-development.md#build) and choose the JAR for your platform. No public release is available yet.
2. Stop the test proxy/server. Put that JAR in `plugins/`, along with its required dependency above. Install the gate on the proxy or the standalone server, not both for the same requirement.
3. Start once to generate files, then stop again. The gate starts disabled. Its folder is `plugins/consentgate/` on Velocity or `plugins/ConsentGate/` on Paper.
4. Adapt `documents/terms.yml.example` and `documents/privacy.yml.example`, including all translations and bracketed placeholders. Choose document versions and rename the selected files to end in `.yml`.
5. Set `enabled: true` in the generated `config.yml`, then start. Confirm ConsentGate enabled successfully. On Paper, keep `bedrock.native-forms: false`.
6. In the console, run `consentgate validate`, then test first acceptance, Leave, and accepted rejoin. Use [offline reset](docs/07-admin-commands.md#reset-for-testing-or-administration) to repeat testing without deleting the database.

See the [installation guide](docs/13-installation.md) for platform setup, Bedrock, backups, and troubleshooting. The starter documents need administrator review; they are not a guarantee of legal compliance.

## Planned features

- Read full documents in game and explicitly accept required agreements.
- Configure names, versions, order, wording, colors, safe text formatting, and translations.
- Request acceptance again when required documents change.
- Store acceptance in SQLite, MySQL, or MariaDB.
- Use a local persistent cache with remote storage.
- Support Java dialogs and Bedrock through Geyser, with optional Cumulus forms.

## Platforms

| Platform | Target |
| --- | --- |
| Velocity | Initial release: acceptance before any backend connection |
| Paper | Initial release: acceptance before world entry |
| BungeeCord / Spigot | Later platform support |

Connection handling must pass prototype tests before these guarantees are advertised as supported. Exact platform builds and client requirements will accompany the first release.

## Documentation

- [Installation and dependencies](docs/13-installation.md)
- [Build and test the prototypes](docs/05-development.md)
- [Platform feasibility](docs/01-feasibility.md)
- [Player flow and configuration](docs/02-product-and-config.md)
- [Storage and database upgrades](docs/03-storage-and-migration.md)
- [MySQL, MariaDB, and cache setup](docs/15-remote-storage.md)
- [Administrator commands](docs/07-admin-commands.md)
- [Paper implementation progress](docs/10-paper-progress.md)
- [Paper anticheat compatibility](docs/12-paper-anticheat-compatibility.md)
- [Architecture and roadmap](docs/04-implementation-plan.md)

## License

GNU General Public License v3.0 only (`GPL-3.0-only`). See [LICENSE](LICENSE). Dependencies retain their respective licenses.
