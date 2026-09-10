# Architecture and roadmap

Status: early implementation. Strict admission still needs real-client proof before support can be advertised.

Implementation status: Velocity uses validated configuration, local documents, multi-page navigation, bounded database work, and transactional SQLite storage before backend admission. Paper remains a connection prototype. Shared tests and the local Velocity wire checks pass. Paper runtime and real Java/Bedrock validation remain open. See [development notes](05-development.md). Milestones 1 and 2 are not complete yet.

## Design decisions

| Area | Decision |
| --- | --- |
| Initial platforms | Velocity and Paper |
| Later platforms | BungeeCord and plain Spigot |
| Bedrock | Geyser-translated dialogs initially; optional native Cumulus forms |
| Storage | SQLite default, MySQL/MariaDB, local remote cache enabled |
| H2 | Deferred |
| Documents | Independent names and versions from the start |
| External imports | Outside the initial release |
| License | GPL-3.0-only |

## Small shared core

```text
ConsentGate/
  core/                 Documents, sessions, admission rules, configuration
  storage/              SQLite and remote SQL
  platform-velocity/    Connection lifecycle and dialog transport
  platform-paper/       Connection lifecycle and native dialog conversion
  integration-bedrock/  Optional Cumulus presentation and API integration
  distribution/         Combined plugin JAR, if platform loading allows it
  docs/                 Setup, configuration, storage, troubleshooting
```

Use Java and Gradle Kotlin DSL. Start by evaluating Java 21 as the shared bytecode baseline, with platform-specific build/runtime requirements documented separately. Do not force support for obsolete platform builds merely to retain that baseline.

Add BungeeCord and Spigot modules when their development starts. Java and Bedrock renderers share one document model and session controller; optional integrations must not prevent Java-only installations from loading.

Keep platform types out of core logic. Define small interfaces for connection hold/release, dialog display, identity, scheduling, and persistence. Prefer native platform APIs; isolate any packet or internal-platform access behind the relevant adapter.

Aim for one JAR with platform descriptors and isolated class loading. If incompatible dependencies make this unreliable, ship clearly named platform JARs from the same source tree. Avoid runtime dependency downloads where practical. Measure final size before deciding whether drivers should be bundled.

Use one tested SQL implementation per dialect, without an ORM, web server, Redis requirement, or general-purpose workflow engine. Keep metrics and update checks absent initially.

## Admission state

```text
Authenticated -> Checking -> Showing documents -> Saving -> Admitted
                     |              |                |
                     +--------------+----------------+-> Disconnected
```

A confirmed current acceptance can go directly from Checking to Admitted. Only Admitted releases the held connection.

Bind every action to the connection, player, active document revision, and a random short-lived session token. Validate input types and required selections server-side. Ignore expired, duplicated, forged, and cross-player callbacks. Make save requests idempotent and release at most once.

Disconnect, timeout, plugin shutdown, failed storage, and failed configuration must release resources without admitting pending players. Keep connection tasks separate from database tasks. Never block a game thread or network event loop. Paper's synchronous event completion may require bounded waiting on its documented async configuration thread; prove that callbacks can still finish.

Bound pending sessions, queued database work, document payload sizes, callback frequency, and retry counts. Prevent initial-route and fallback-route bypasses. A backend firewall remains necessary to prevent clients from directly avoiding a proxy-only gate.

## Milestones

| Milestone | Work | Completion evidence |
| --- | --- | --- |
| 1. Connection prototypes | Velocity and Paper, including Geyser translation | Dialog, callback, long wait, disconnect, and safe continuation on pinned builds |
| 2. Core flow | Local documents, navigation, versions, SQLite, basic commands | First join, accepted rejoin, changed version, invalid input, and write failure behave correctly |
| 3. Shared storage | MySQL/MariaDB, local cache, concurrency, audit transactions | Outage and recovery checks against both actual database products |
| 4. Bedrock presentation | Evaluate optional Cumulus forms on the admission hooks | Full text and explicit acceptance work before admission; fallback is verified |
| 5. Packaging and docs | Platform loading, configuration reference, setup, troubleshooting | Clean install and upgrade from packaged JARs |
| 6. Release preparation | License text, dependency notices, source package, release notes | Local release artifacts ready |
| Later platforms | BungeeCord and plain Spigot prototypes | Equivalent admission guarantees before support is advertised |

For milestone 1, strict proxy success means the backend receives no connection or handshake before acceptance. Check with a local instrumented backend and connection logs, not merely the absence of PlayerJoinEvent. Confirm the dialog still works while the backend is unavailable, then verify normal routing once available. For standalone, check world/player creation timing as well as visible UI.

Prioritize Velocity and Paper. Native Bedrock forms are an enhancement, not a prerequisite when translated dialogs meet the requirements. Test both paths at the admission stage. If neither works, report the compatibility limit rather than weaken the gate. Assess additional dependencies and invasive internals before adoption. Later platforms do not block the initial release.

## Verification that matters

- Real Java clients at the feature boundary and supported newer versions, with low resolution and large GUI scale for long text.
- Pinned proxy/server builds, normal routing, forced hosts, fallback servers, server switches, and repeated configuration.
- Timeout and keepalive behavior beyond the normal login timeout; resource cleanup after disconnect and shutdown.
- Unchecked acceptance, replayed clicks, malformed payloads, concurrent joins, and reload during save.
- SQLite disk-full/locked cases; remote timeout before and after commit; missing or corrupt cache; stale withdrawal on another proxy.
- Latest withdrawal wins, schema upgrades preserve history, and interrupted migrations have a documented recovery path.
- Geyser translation and optional native forms: touch/controller navigation, toggles, scrolling, closure, invalid responses, and formatting.
- Missing optional integrations must not bypass acceptance or select an untested renderer. Verify ViaVersion/ViaBackwards and authentication/limbo plugins within the supported matrix.

Use focused core tests and real database integration checks. H2 is not a substitute for testing MySQL or MariaDB behavior. Add regression tests when a protocol or migration failure is discovered.

## Documentation deliverables

Keep a short README and separate guides for proxy installation, standalone installation, local documents, storage/cache behavior, permissions, database upgrades, supported versions, and troubleshooting. Explain where the gate runs and exactly what it prevents.

Document backup/restore, retention, withdrawal, identity changes, unavailable databases, and reload behavior. Store only generic examples. Acceptance records describe what the software observed; do not advertise the plugin as establishing legal compliance or proving a document was read.

## License and distribution

The project uses GPL-3.0-only. Include the full license text before distributing source or binaries. Check dependency compatibility and include required notices and corresponding source. Replace or review incompatible dependencies without silently changing the project's license. [GPLv3 text](https://www.gnu.org/licenses/gpl-3.0.html)

Modrinth is a planned distribution option. Publish metadata only for tested platforms and versions. Check the project name before publication and produce checksummed local artifacts. [Modrinth loader documentation](https://docs.modrinth.com/api/operations/loaderlist/)

Keep documentation, examples, and commit messages neutral and relevant to the plugin. Exclude private deployment notes and source material from version control. Repository hosting, pushes, artifact uploads, and releases are separate approved operations.
