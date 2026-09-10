# Development

The platform adapters are connection prototypes. The shared core validates configuration and local documents, plans admission, preserves multi-document checkbox state, and has transactional SQLite acceptance storage. These pieces are not wired into the adapters yet. Cached acceptance, remote databases, native Cumulus forms, and administrator commands are not implemented. Do not install the adapters on a production server or proxy.

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

## Prototype behavior

Every connection sees clearly labeled test text and one unchecked box. Continue only admits a checked response belonging to that connection. Leave disconnects. No policy agreement is implied and no permanent record is written.

Pending connections have a five-minute limit. Each platform allows at most 128 pending sessions. Shutdown, disconnect, and errors end pending sessions without an accepted decision. These limits are fixed during prototyping.

Velocity checks admission again on every backend connection request. It maintains keepalives while held and consumes its own delayed heartbeat responses. Paper holds only its asynchronous configuration event, never the server's main thread.

## Automated checks

`core:test` checks unchecked and foreign actions, timeout, concurrent repeated clicks, shutdown, configuration paths and bounds, document loading and hashing, locale selection, multi-document state, SQLite transactions, repeated requests, changed content under an unchanged version, and withdrawal ordering.

The Python wire probe exercises Java protocol 772 (1.21.8) against a real local Velocity process. It opens an instrumented backend listener and checks:

- No backend connection during 35 seconds of dialog waiting and keepalives.
- Unchecked and foreign-token acceptance cannot release the connection.
- Valid acceptance releases the initial backend handshake.
- Leave disconnects without backend contact.
- An unsolicited configuration-completion packet cannot bypass the gate.

This is a protocol test, not visual verification or proof of complete gameplay routing.

## Local Velocity test setup

Use a disposable `.run/velocity` directory, excluded from version control. Put Velocity at `velocity.jar`, PacketEvents in `plugins/packetevents.jar`, and the built Velocity prototype in `plugins/ConsentGate.jar`.

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

The runner verifies the local configuration, starts the prepared proxy, runs the probe, and stops its process. Neither runner downloads server software. Inspect `.run/velocity/logs/latest.log` if startup fails. The backend probe intentionally stops after receiving a handshake, so backend connection errors after that assertion are expected.

## Validation matrix

| Environment | Current evidence |
| --- | --- |
| Shared session core | Five passing unit tests |
| Configuration, documents, admission, and SQLite | Twenty-eight passing unit tests |
| Paper 1.21.7 API | Compiles; runtime and real-client checks pending |
| Velocity 3.4.0 build 563, PacketEvents 2.13.0 | All five local wire checks passed on 2026-09-10 |
| Geyser-translated dialogs | Real Bedrock checks pending |
| Native Cumulus | Not implemented |

Velocity test artifact SHA-256: `fe53021f3168322cb6cb68f78699866fd098df3c306e4359847a10b0d02689ef`.

PacketEvents test artifact SHA-256: `e797f84abc349c137396e511ce4f0d7b85e385727a2e82e2ffb6bed0d2fe5c05`.

Paper and Velocity API snapshot coordinates are provisional. Pin resolved dependency artifacts before preparing a release.

## Before expanding the feature set

Finish runtime and visual checks on Paper and Velocity with real Java and Bedrock clients. Confirm text, checkbox responses, closing behavior, timeout, clean world entry, and normal backend routing. Do not mark the connection milestone complete from compilation or a synthetic client alone.

Then wire configuration, local documents, and SQLite into both platform adapters, including full document navigation. Follow with remote storage and optional native Bedrock forms. The roadmap tracks the remaining work.
