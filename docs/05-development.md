# Development

Velocity now uses the shared configuration, document, admission, and SQLite components. It supports summary checkboxes, optional language selection, full multi-page reading, restricted MiniMessage formatting, durable acceptance before backend release, and accepted reconnects. Optional native Cumulus forms are implemented for Geyser on the same proxy. Real-client testing confirmed the native menu, acceptance, formatting, and return from the agreement form. Broader client and failure testing remains open. Paper remains a connection prototype. Cached acceptance, remote databases, and administrator commands are not implemented. Do not install either artifact on a production server or proxy.

## Build

Use JDK 25 to run the checked-in Gradle wrapper. Project bytecode targets Java 21; the selected server or proxy may require a newer runtime.

```powershell
.\gradlew.bat build
```

On Linux or macOS, run `./gradlew build`. The wrapper verifies its Gradle distribution checksum. Dependencies are fetched during the build; no build scans or release uploads are configured.

Outputs:

- `platform-paper/build/libs/ConsentGate-Paper-0.1.0-prototype.jar`
- `platform-velocity/build/libs/ConsentGate-Velocity-0.1.0-prototype.jar`

The shaded platform JARs contain the shared core, relocated SnakeYAML, SQLite JDBC, and its native libraries. Paper uses its native API. Velocity currently requires PacketEvents 2.13.0 installed separately. Separate artifacts keep platform dependencies clear; combined packaging remains a later decision.

## Current behavior

Velocity is disabled on a fresh installation. It creates `config.yml` and styled, inactive `documents/terms.yml.example` and `documents/privacy.yml.example` starter templates. Administrators must replace bracketed values, remove sections that do not apply, review the final text, rename the files to `.yml`, and set `enabled: true`. Current acceptance then bypasses the dialog. Otherwise, players can read every page, return with checkbox state preserved, and continue only after every required box is checked and SQLite commits the acceptance. Leave, invalid input, timeout, storage failure, and shutdown do not admit the player.

Velocity timeout and pending-session limits come from configuration. Database checks and writes use two workers and a bounded queue. Paper still uses fixed five-minute and 128-session prototype limits. Shutdown, disconnect, and errors end pending sessions without admission.

Velocity checks admission again on every backend connection request. It maintains keepalives while held and consumes its own delayed heartbeat responses. Paper holds only its asynchronous configuration event, never the server's main thread.

## Automated checks

Native Bedrock form tests exercise Cumulus response parsing, menu and page navigation, unchecked defaults, partial selections, malformed payloads, stale responses, close behavior, and failed delivery. The Java wire probe runs without Geyser installed to check that the optional integration does not break Java admission.

`core:test` checks unchecked and foreign actions, timeout, concurrent repeated clicks, shutdown, runtime bootstrap, configuration paths and bounds, document loading and hashing, locale selection, multi-document state, SQLite transactions, repeated requests, changed content under an unchanged version, and withdrawal ordering.

The Python wire probe stages the current shaded JAR with an enabled local test document and exercises Java protocol 772 (1.21.8) against a real local Velocity process. It opens an instrumented backend listener and checks:

- No backend connection during 35 seconds of dialog waiting and keepalives.
- The configured pending limit rejects overflow without backend contact.
- Unchecked, malformed, extra-field, wrong-type, and foreign-token acceptance cannot release the connection.
- Document IDs containing hyphens are mapped to protocol-safe checkbox input names.
- Restricted MiniMessage tags render as components instead of leaking markup to the client.
- Full document navigation returns to the active request.
- Valid acceptance releases the initial backend handshake.
- An accepted reconnect skips the dialog.
- A locked SQLite write fails without backend contact.
- Leave disconnects without backend contact.
- An unsolicited configuration-completion packet cannot bypass the gate.

This is a protocol test, not visual verification or proof of complete gameplay routing.

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
| Shared session core | Five passing unit tests |
| Configuration, documents, admission, and SQLite | Thirty-three passing unit tests |
| Velocity restricted formatting | Three passing unit tests |
| Paper 1.21.7 API | Compiles; runtime and real-client checks pending |
| Velocity 3.4.0 build 563, PacketEvents 2.13.0 | All nine local wire checks passed on 2026-09-10 |
| Velocity 4.1.0 snapshot, PacketEvents 2.13.0, Minecraft 26.2 | Styled two-document acceptance and accepted reconnect reached a backend on 2026-09-10 |
| Geyser-translated dialogs | Real Bedrock checks pending |
| Native Cumulus | Not implemented |

PacketEvents test artifact SHA-256: `e797f84abc349c137396e511ce4f0d7b85e385727a2e82e2ffb6bed0d2fe5c05`.

Paper and Velocity API snapshot coordinates are provisional. Pin resolved dependency artifacts before preparing a release.

## Before expanding the feature set

Finish runtime and visual checks on Paper and Velocity with real Java and Bedrock clients. Confirm text, checkbox responses, closing behavior, timeout, clean world entry, and normal backend routing. Do not mark the connection milestone complete from compilation or a synthetic client alone.

Next, apply the shared runtime and full navigation flow to Paper, with safer connection-pressure handling. Follow with real Java and Bedrock validation, remote storage, and optional native Bedrock forms. The roadmap tracks the remaining work.
