# Development

Both platforms use shared configuration, documents, admission, and storage. They support checkboxes, language selection, reading pages, formatting, durable acceptance, and administrator commands. Native Cumulus forms share one renderer, with real-client checks on Velocity and initial integration on Paper. Remote SQL and a local cache have MariaDB and MySQL repository tests. Paper Bedrock delivery and broader runtime checks remain open. See [release progress](16-release-progress.md). Do not install either artifact on a production server or proxy.

## Build

Use JDK 25 to run the checked-in Gradle wrapper. Project bytecode targets Java 21; the selected server or proxy may require a newer runtime.

```powershell
.\gradlew.bat build
```

On Linux or macOS, run `./gradlew build`. The wrapper verifies its Gradle distribution checksum. Dependencies are fetched during the build; no build scans or release uploads are configured.

Outputs:

- `platform-paper/build/libs/ConsentGate-Paper-0.1.0-prototype.jar`
- `platform-velocity/build/libs/ConsentGate-Velocity-0.1.0-prototype.jar`

The shaded platform JARs contain the shared core, relocated SnakeYAML and MariaDB Connector/J, SQLite JDBC, and SQLite native libraries. The remote schema and driver license notices are bundled. Paper uses its native API. Velocity currently requires PacketEvents 2.13.0 installed separately. Separate artifacts keep platform dependencies clear; combined packaging remains a later decision.

## Current behavior

Velocity is disabled on a fresh installation. It creates `config.yml` and styled, inactive `documents/terms.yml.example` and `documents/privacy.yml.example` starter templates. Administrators must replace bracketed values, remove sections that do not apply, review the final text, rename the files to `.yml`, and set `enabled: true`. Current acceptance then bypasses the dialog. Otherwise, players can read every page, return with checkbox state preserved, and continue only after every required box is checked and SQLite commits the acceptance. Leave, invalid input, timeout, storage failure, and shutdown do not admit the player.

Both platforms use configured timeout and pending-session limits. Database checks and writes use two workers and a bounded queue. Paper admission tests cover queued cancellation, save/reset ordering, locked SQLite, shutdown, timeout, and rendering failures. Broader runtime checks remain pending. Shutdown, disconnect, and errors end pending sessions without admission.

Velocity checks admission again on every backend connection request. It maintains keepalives while held and consumes its own delayed heartbeat responses. Paper holds only its asynchronous configuration event, never the server's main thread.

## Automated checks

`build` never contacts a remote database. The opt-in `:core:remoteDatabaseTest` task requires a dedicated test database and explicit environment settings; see [integration instructions](15-remote-storage.md#integration-tests). MySQL and MariaDB require separate runs. Local tests cover cache expiry, persistence, reset, capacity, corrupt files, failed invalidation, clock movement, and bounded stalled connection setup.

Native Bedrock form tests exercise Cumulus response parsing, menu and page navigation, unchecked defaults, partial selections, malformed payloads, stale responses, close behavior, and failed delivery. The Java wire probe runs without Geyser installed to check that the optional integration does not break Java admission.

Run `python -m unittest discover -s tools -p "test_*.py"` for the dialog payload framing fixtures. These use fixed expected bytes backed by the official client codec inspection, including absent tags, empty compounds, checkbox data, and multi-byte lengths.

`core:test` checks unchecked and foreign actions, timeout, concurrent repeated clicks, shutdown, runtime bootstrap, configuration paths and bounds, document loading and hashing, locale selection, multi-document state, SQLite transactions, repeated requests, changed content under an unchanged version, and withdrawal ordering.

The Python wire probe stages the current shaded JAR with an enabled local test document and exercises Java protocol 771 (1.21.6) or 772 (1.21.8) against a real local Velocity process. Select one with `python tools/run_velocity_probe.py --protocol 771` or `--protocol 772`; the default is 772. It opens an instrumented backend listener and checks:

- No backend connection during 35 seconds of dialog waiting and keepalives.
- The configured pending limit rejects overflow without backend contact.
- Unchecked, malformed, extra-field, wrong-type, and foreign-token acceptance cannot release the connection.
- Incorrect bot presence-flag framing, truncated NBT, and oversized payload lengths disconnect without acceptance records or backend contact.
- Document IDs containing hyphens are mapped to protocol-safe checkbox input names.
- Restricted MiniMessage tags render as components instead of leaking markup to the client.
- Full document navigation returns to the active request.
- Valid acceptance releases the initial backend handshake.
- An accepted reconnect skips the dialog.
- Console status/reset preserves history, requires acceptance again, and refuses a connected target.
- Validation leaves active settings unchanged; reload refuses active sessions and invalid changes, then applies a valid new version.
- A locked SQLite write fails without backend contact.
- Leave disconnects without backend contact.
- An unsolicited configuration-completion packet cannot bypass the gate.

This is a protocol test, not visual verification or proof of complete gameplay routing.

The loopback-only [Paper probe](12-paper-anticheat-compatibility.md#headless-reproduction) checks prolonged configuration holds, acceptance, and play-stage timeout behavior on a separately prepared server. Run its dependency-free helper tests with `node --test tools/test_paper_probe.cjs`. It does not open a graphical client or produce rendered screenshots. Paper visual checks remain manual.

After preparing `.run/paper-minimal` with that server and local Node dependencies, run `python tools/run_paper_probe.py --modules C:/path/to/node_modules`. It tests the packaged JAR's Java admission, accepted rejoin, reset history, version reload, Leave, and timeout. The server must be stopped, offline, bound to `127.0.0.1:25592`, and have RCON, query, and metrics disabled. The runner stops its process, restores the original ConsentGate configuration, and retains its unique test fixture. It does not launch a desktop application.

## Local Velocity test setup

Use a disposable `.run/velocity` directory, excluded from version control. Put Velocity at `velocity.jar` and PacketEvents in `plugins/packetevents.jar`. Build the Velocity artifact before running the probe; the runner copies it and writes the enabled consent fixture.

Create `velocity.toml` with these test settings before starting the proxy:

```toml
config-version = "2.7"
bind = "127.0.0.1:25590"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
ping-passthrough = "DISABLED"
enable-player-address-logging = false

[servers]
backend = "127.0.0.1:25591"
try = ["backend"]

[forced-hosts]

[advanced]
compression-threshold = -1
login-ratelimit = 0
read-timeout = 30000

[query]
enabled = false
```

Disable test-process metrics in `plugins/bStats/config.txt` with `enabled=false`. Keep this offline test proxy bound to loopback. These settings are not a production configuration.

With Python 3.11 or newer and Java on PATH:

```powershell
python tools/run_velocity_probe.py
```

The runner verifies the local proxy configuration, stages the current plugin artifact and a fresh SQLite fixture, starts the prepared proxy, runs the probe, and stops its process. It does not download server software. Inspect `.run/velocity/logs/latest.log` if startup fails. The backend probe intentionally stops after receiving a handshake, so backend connection errors after that assertion are expected.

## Validation matrix

| Environment | Current evidence |
| --- | --- |
| Shared session core | Automated session lifecycle checks |
| Configuration, documents, admission, and SQLite | Automated loading, acceptance, withdrawal, and history checks |
| MariaDB 11.8.6, Connector/J 3.5.10 | Dedicated transactional repository, concurrency, cache outage/recovery, replay, and schema-version checks pass; live remote-storage admission remains unverified |
| MySQL 8.4.8, Connector/J 3.5.10 | All 15 repository checks pass, including verified TLS, certificate rejection, and actual socket interruption before commit and before its reply reaches the plugin |
| Velocity formatting and administration | Automated formatting, command permissions, target parsing, and save/reset ordering checks |
| Paper 1.21.7 API | Compiles; strict response parsing, startup/reload presentation, and isolated shaded-JAR tests pass |
| Paper-compatible 26.2 server with Java 26.1 bot | Language, navigation, acceptance, reconnect, Leave, and administrator checks pass; official Grim `2.3.74-8eb5f28` passes initial-login long-wait and timeout checks; see the [Grim test report](12-paper-anticheat-compatibility.md) |
| Velocity 3.4.0 build 563, PacketEvents 2.13.0 | Local wire/console checks cover admission and administrator status/reset/validation/reload |
| Java protocols 771 and 772 | All 14 wire/console checks passed separately for each protocol on 2026-09-12; four golden framing tests also passed |
| Velocity 4.1.0 snapshot, PacketEvents 2.13.0, Minecraft 26.2 | Styled two-document acceptance and accepted reconnect reached a backend on 2026-09-10 |
| Geyser-translated dialogs | Real Bedrock acceptance tested; translated action dropdown prompted native form support |
| Native Cumulus with local Geyser | Real Bedrock menu, reading, acceptance, close/back behavior, and formatting tested; automated response tests pass |
| Velocity 4.1.0 snapshot, live proxy bot check | Reload refused an active dialog; a temporary new document version prompted consent, saved acceptance, and bypassed the gate on accepted reconnect; original documents were restored |

PacketEvents test artifact SHA-256: `e797f84abc349c137396e511ce4f0d7b85e385727a2e82e2ffb6bed0d2fe5c05`.

Paper and Velocity API snapshot coordinates are provisional. Pin resolved dependency artifacts before preparing a release.

The live proxy bot used a custom dialog response encoder because the installed bot library's response encoding was rejected by the packet layer. [Independent official-client bytecode inspection](09-protocol-compatibility-findings.md) confirmed the length-prefixed format and identified the installed bot definition as incorrect. This resolves the reproduced framing mismatch, not visual layout or all client compatibility. Unmodified clients at the advertised version boundary still need testing before release.

## Before expanding the feature set

Finish runtime and visual checks on Paper and Velocity with real Java and Bedrock clients. Confirm text, checkbox responses, closing behavior, timeout, clean world entry, and normal backend routing. Do not mark the connection milestone complete from compilation or a synthetic client alone.

The response encoding mismatch is resolved as a bot-library defect. Paper command testing covers console operations and in-game replies, and the official Grim build passes initial-login timeout checks. Native Paper Bedrock integration and remote storage are implemented. Their remaining runtime checks, connection-pressure tests, and unmodified-client validation are tracked in [release progress](16-release-progress.md).
