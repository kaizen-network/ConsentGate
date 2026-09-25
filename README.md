# ConsentGate

Configurable in-game agreements for Minecraft servers and networks.

ConsentGate lets administrators present rules, policies, and other documents before players are admitted. Documents live in local files with custom titles, full text, and independent versions. No website or additional hosting is required.

Fresh installations include inactive Terms of Service and Privacy Policy starter templates. Administrators must review and adapt them before use. Both platforms offer optional native Bedrock forms through a local Geyser installation, with separate document buttons and agreement toggles.

## Status

Core features are implemented on Paper and Velocity. Automated checks cover admission, administration, SQLite, both remote databases, outages, and recovery. Native Bedrock acceptance and reconnect passed on the documented Paper test installation. Version `0.1.0` packages are available from [GitHub Releases](https://github.com/kaizen-network/ConsentGate/releases/tag/v0.1.0) to users with repository access. See the [release progress](docs/16-release-progress.md).

## Requirements

Validate the package on a test installation first. Use Java 21 or newer, or the newer Java version required by your server/proxy. Building from source requires JDK 25. Java dialogs require a 1.21.6-or-newer client; this is a feature minimum, not a guarantee for every newer version. See the [tested version matrix](docs/05-development.md#validation-matrix).

| Installation | Plugin JAR | Additional requirement |
| --- | --- | --- |
| Velocity | `ConsentGate-Velocity-0.1.0.jar` | PacketEvents 2.13.0 for Velocity, installed on the same proxy |
| Paper | `ConsentGate-Paper-0.1.0.jar` | No separate plugin dependency for Java dialogs; stock Paper 1.21.7 build 32 passed headless admission checks |
| Native Bedrock forms | Matching platform JAR | Geyser on the same proxy/server; optional and disabled by default. See the [verified setups and client results](docs/19-final-client-check.md) |

SQLite is the default, with its driver bundled. Optional MySQL/MariaDB storage and a local SQLite cache are implemented. Both database products passed repository, TLS, socket-failure, and headless admission and outage checks on both platforms. An initial 30-second workload across two instances also passed. No website or GrimAC is required. BungeeCord and plain Spigot are not implemented yet. [Dependency details](docs/13-installation.md#requirements-and-dependencies) and [remote storage setup](docs/15-remote-storage.md).

## Quick start

These steps describe the current source build. The `v0.1.0` tag predates automatic database setup and enabling through reload. If using that release, follow its packaged instructions instead.

1. [Build the current source](docs/05-development.md#build) and choose the JAR for your platform.
2. Stop the test proxy/server. Put that JAR in `plugins/`, along with its required dependency above. Use Velocity for network consent, Paper for server consent, or both for separate requirements as explained below.
3. Start once to generate files. The gate starts disabled. Its folder is `plugins/consentgate/` on Velocity or `plugins/ConsentGate/` on Paper.
4. Adapt `documents/terms.yml.example` and `documents/privacy.yml.example`, including all translations and bracketed placeholders. Choose document versions and rename the selected files to end in `.yml`.
5. Keep SQLite for local storage, or enter your MySQL/MariaDB details using the steps in `config.yml`. An empty remote database gets its tables automatically. Set `enabled: true` and keep `bedrock.native-forms: false` for the initial Java check.
6. In the console, run `consentgate validate`, then `consentgate reload`. Test first acceptance, Leave, and accepted rejoin. Use [offline reset](docs/07-admin-commands.md#reset-for-testing-or-administration) to repeat testing without deleting the database. Add `/` before commands when running them in game; see [permissions](docs/07-admin-commands.md).

See the [installation guide](docs/13-installation.md) for platform setup, Bedrock, backups, and troubleshooting. The starter documents need administrator review; they are not a guarantee of legal compliance.

## Shared or separate consent

`scope` names a consent group for the whole plugin instance. It does not select a Velocity backend by name.

| Goal | Example setup |
| --- | --- |
| Share consent across proxies | Use `scope: network` on each proxy, the same MySQL/MariaDB database, matching document IDs, versions and text, and the same player UUIDs. |
| Separate network and Survival consent | Use `scope: network` on Velocity and `scope: survival` on the Survival Paper server. Players can be asked once at each gate, even with a shared database. |

Scope names allow 1–64 characters: lowercase letters, numbers, `_`, `-`, and `.`. Start with a letter or number. Changing scope does not move existing consent records. Keep document files in sync yourself when sharing consent; the database does not copy them between servers. Separate SQLite files do not share consent.

## Editing and updating

- Keep each document's `id` stable. Change its quoted `version` when editing active or previously saved document text, including titles, summaries, labels, page text, or formatting inside those fields. Players accept the new version on their next connection. Changing global `appearance` colors does not need a document version bump.
- `required: false` removes a document from the consent screen. Optional checkboxes and informational-only login documents are not supported. At least one required document is needed to enable the gate.
- Reload can enable a disabled gate and apply document, message, language, color, and timeout edits. Disabling an active gate, changing its scope or storage, or changing `max-pending` requires a restart. Reload refuses while sessions or database jobs are active; retry after they finish.
- Updates preserve existing config, messages, and templates. New settings and comments are not added automatically. Back up your folder and compare with the [current default config](presentation/src/main/resources/config.yml), copying needed settings without replacing your credentials or documents. Replacing the JAR requires a restart.

For setting limits and document rules, see the [configuration guide](docs/02-product-and-config.md#setting-limits). Moving from SQLite to MySQL/MariaDB does not transfer old consent records; players must accept again. See [database setup](docs/15-remote-storage.md).

## Implemented features

- Read full documents in game and explicitly accept required agreements.
- Configure names, versions, order, wording, colors, safe text formatting, and translations.
- Request acceptance again when required documents change.
- Store acceptance in SQLite, MySQL, or MariaDB.
- Use a local persistent cache with remote storage.
- Inspect active documents and preview the login flow without saving consent.
- Support Java dialogs and Bedrock through Geyser, with optional Cumulus forms.

## Platforms

| Platform | Target |
| --- | --- |
| Velocity | Initial release: acceptance before any backend connection |
| Paper | Initial release: acceptance before world entry |
| BungeeCord / Spigot | Later platform support |

Connection handling passed the documented automated checks. See the [tested version matrix](docs/05-development.md#validation-matrix) for exact recorded builds and compatibility limits.

## Documentation

- [0.1.0 release notes](docs/20-release-notes-0.1.0.md)
- [Installation and dependencies](docs/13-installation.md)
- [Build and test the plugin](docs/05-development.md)
- [Platform feasibility](docs/01-feasibility.md)
- [Player flow and configuration](docs/02-product-and-config.md)
- [Storage and database upgrades](docs/03-storage-and-migration.md)
- [MySQL, MariaDB, and cache setup](docs/15-remote-storage.md)
- [Backups and recovery](docs/17-backups-and-recovery.md)
- [Release progress](docs/16-release-progress.md)
- [Local packages, fixed build inputs, and sources](docs/18-local-distribution.md)
- [Administrator commands](docs/07-admin-commands.md)
- [Paper implementation progress](docs/10-paper-progress.md)
- [Paper anticheat compatibility](docs/12-paper-anticheat-compatibility.md)
- [Architecture and roadmap](docs/04-implementation-plan.md)

## License

GNU General Public License v3.0 only (`GPL-3.0-only`). See [LICENSE](LICENSE). Dependencies retain their respective licenses.
