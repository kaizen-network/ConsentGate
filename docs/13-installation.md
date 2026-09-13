# Installation and setup

ConsentGate is a prototype, not a production release. Use a disposable test server or proxy first. There is no public release download yet; [build from source](05-development.md#build) to produce the platform JARs.

## Requirements and dependencies

| Item | What to install |
| --- | --- |
| Java runtime | Java 21 or newer for ConsentGate bytecode; use the newer Java version required by the selected server/proxy. Current 26.2 test servers run Java 25. |
| Build tools | JDK 25 and the included Gradle wrapper. Not needed on a server when a built JAR is supplied. |
| Java client | Dialog feature minimum: Minecraft Java 1.21.6. Tested versions are listed in the [validation matrix](05-development.md#validation-matrix), not a blanket promise of all newer versions. |
| Velocity | A compatible Velocity build and the Velocity distribution of PacketEvents 2.13.0 on the same proxy. ConsentGate compiles against Velocity 3.4.0. |
| Paper | A Paper-compatible build with the native dialog and asynchronous configuration APIs. Current compile target and plugin API declaration are 1.21.7; the oldest supported runtime is not yet verified. No separate PacketEvents plugin is required by ConsentGate on Paper. |
| SQLite | Included in both JARs, including JDBC and native libraries. The plugin data folder must be writable. |
| Geyser | Optional for Bedrock access. Native consent forms require Geyser on the same proxy/server, using Geyser-Spigot for Paper. Configure its authentication, forwarding, and Bedrock UDP listener separately. Paper Bedrock runtime verification remains open. |

Use the complete server/proxy distribution of a dependency, not a thin Maven API JAR. Get dependencies through their projects' official channels: [PacketEvents](https://github.com/retrooper/packetevents), [Velocity](https://papermc.io/software/velocity), [Paper](https://papermc.io/software/paper), and [Geyser](https://geysermc.org/download).

ConsentGate does not require GrimAC, a separate Cumulus plugin, a website, or an external database service. Cumulus is accessed through the optional Geyser integration. Floodgate is not a direct ConsentGate dependency; whether it is needed depends on your Geyser authentication setup. Follow [Geyser's setup guide](https://geysermc.org/wiki/geyser/setup/) for that configuration.

Optional MySQL/MariaDB storage and a local SQLite cache are implemented. MariaDB 11.8.6 has repository integration tests; MySQL server verification and live remote-storage admission remain open. Both artifacts bundle MariaDB Connector/J, so no separate driver plugin is needed. Follow the [remote storage guide](15-remote-storage.md) before selecting that provider. BungeeCord and plain Spigot are not implemented.

## Choose the admission point

- With Velocity, install ConsentGate and PacketEvents on the proxy. ConsentGate handles acceptance before opening a backend connection. The backend needs its normal forwarding and access restrictions, not another ConsentGate installation for the same requirement.
- Without a proxy, install the Paper artifact on the standalone server. It holds the configuration stage before play entry. Wider plugin-stack and world-entry checks remain on the [Paper checklist](10-paper-progress.md).

Do not copy Velocity's PacketEvents JAR into Paper or the Paper ConsentGate JAR into Velocity. Do not change authentication or turn off forwarding security just to make the dialog work. A proxy gate cannot protect a backend that players can reach directly.

## First installation

1. Back up your test installation. Stop the proxy/server before adding or replacing JARs.
2. Copy one matching ConsentGate artifact into its `plugins/` directory. For Velocity, also install PacketEvents for Velocity there.
3. Start once. ConsentGate creates an inactive configuration, example documents, and message files. Check for missing-dependency or startup errors, then stop again.
4. Open the generated folder: `plugins/consentgate/` on Velocity, or `plugins/ConsentGate/` on Paper. Paths and capitalization matter on Linux.
5. Edit the example documents in `documents/`. Replace every bracketed placeholder, remove sections that do not apply, and review each translation. Set your own document IDs, titles, versions, and content before first use. Rename each selected `.yml.example` file to `.yml`; inactive examples are not loaded as agreements.
6. In the existing `config.yml`, set `enabled: true`. Keep `storage.type: sqlite`. Choose a stable `scope` and allow enough reading time through `gate.timeout-seconds`. Keep `bedrock.native-forms: false` for the initial Java check.
7. Start the proxy/server. Confirm the log says ConsentGate is enabled with `sqlite` storage. In the console, run `consentgate validate` without a leading slash. A failed or disabled startup must be corrected in the files and restarted; admin validation is not available on a disabled gate.
8. Connect with a supported test client and no previous acceptance. Read each document and test both Leave and acceptance. Accepted rejoin should skip the dialog. On Velocity, also confirm the player reaches the intended backend only after acceptance.

The bundled examples use `draft-2` as a placeholder version. It is not a plugin release number. You can choose any valid document version before activating it. After a revision has been registered, changing its text or translation requires a new version. Do not edit a saved revision under the same version to avoid re-consent.

## Language and Bedrock

The default language is `en-US`. Matching the client's locale is enabled by default; the optional language selector is not. Configure `language.selector.enabled`, its title, prompt, and language labels in `config.yml`. Keep document translations and `messages/<locale>.properties` complete for the languages you offer. See [configuration and player flow](02-product-and-config.md).

For native Bedrock forms, first configure Geyser on the same proxy/server and verify normal Bedrock connectivity. Then set `bedrock.native-forms: true`, validate, and reload when no consent sessions or database jobs are pending. Geyser handles Cumulus delivery; no additional web service is needed. A separate Geyser instance on another server is not the current native integration path.

Paper now uses the shared native renderer through Geyser-Spigot, declared as an optional dependency. Native delivery and Geyser-translated dialogs still need separate Paper Bedrock testing. Java tests do not establish Paper Bedrock compatibility.

## Administration and backups

Use `consentgate status <online-player|uuid>` to inspect current acceptance. For another test, disconnect the player and use `consentgate reset <uuid>`. Reset preserves history. Do not delete the database to request consent again.

Use `consentgate validate` followed by `consentgate reload` for supported edits. Reload refuses while sessions or database work are active. Changing `enabled`, `scope`, any storage/cache setting, or the pending-session limit requires a restart. See [commands and permissions](07-admin-commands.md).

Stop the proxy/server before making a file-based backup. Preserve the entire ConsentGate data folder, including documents, messages, configuration, and the SQLite database at the configured path. Do not copy only the main SQLite file while it is being written. Keep backups private because acceptance records contain player identifiers and decisions.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No dialog | Is the gate enabled? Did startup succeed? Are active documents named `.yml`? Has this UUID already accepted the current required versions? |
| Velocity reports PacketEvents missing | Install the full Velocity PacketEvents plugin on the proxy, then restart. |
| Paper fails with a missing API method | Verify the exact Paper build against the tested matrix. An API compile target does not prove runtime compatibility. |
| Native forms fail on Paper | Check that Geyser-Spigot is enabled on the same server and inspect the log. Paper Bedrock runtime verification is still in progress. |
| Edited documents fail validation | Check the reported locale, field, formatting, and version. Existing revisions cannot silently change text. |
| Disconnect around 60 seconds while reading | If GrimAC is installed, check the exact build revision. Older builds have a configuration-timeout bug; see [Grim compatibility findings](12-paper-anticheat-compatibility.md). Do not disable anticheat or shorten reading time to conceal it. |
| Backend is unreachable after accepting | Check the proxy route, forced hostname, forwarding settings, and backend availability separately. |

Treat any enabled-gate startup error as a failed installation. Check logs and verify that a fresh, unaccepted test identity cannot enter before trusting the setup. Keep the [test matrix and open checks](05-development.md#validation-matrix) in mind: a successful synthetic-client test is not a visual or full gameplay compatibility check.
