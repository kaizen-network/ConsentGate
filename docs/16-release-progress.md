# Release progress

Status: core implementation is complete. Final real-client checks remain. Neither artifact is a production release.

## Current implementation

- Core documents, translations, versioned acceptance, SQLite history, and administrator commands are implemented.
- Login preview and active-document viewing are implemented on both platforms. Preview uses the actual admission renderer but never saves consent; the shared storage entry point rejects preview requests.
- Both packaged Java paths passed preview for new and accepted players, preserved-history checks, cancellation, document viewing, and refusal to queue previews for connected players. Paper also passed live capacity rejection and busy reset/reload checks while a player reviewed consent.
- Velocity supports Java dialogs and native Bedrock forms. Its 15 local protocol check groups include preview and document viewing.
- Paper uses the shared native Bedrock renderer through optional local Geyser-Spigot. A real Bedrock client passed acceptance, world entry, and reconnect on a Paper-compatible 26.2 server after fixing the Cumulus provider conflict. Its Java path remains independent of Geyser and PacketEvents.
- Paper admission now has direct lifecycle tests for delayed saves, cancellation, reset ordering, locked SQLite, shutdown, timeout, and presentation failures. A renderer linkage failure cannot release a successful admission.
- A native-response/timeout lock regression was reproduced and fixed. Core decision callbacks now run outside the session lock, while the terminal decision and selections remain frozen.
- Remote MySQL/MariaDB storage and persistent cache are implemented. Each database passed all 18 checks including bounded translation batches, TLS verification, actual socket interruptions around commit, reset-result verification, and a 30-second workload across two instances.
- SQLite now rejects older and replayed saves after withdrawal, including resets before first acceptance. Both stores reject resets that leave a newer grant active. Five failing SQLite cases were reproduced before the fix. Existing version-1 history and a restored closed-file backup pass regression checks.
- MySQL and MariaDB dumps were each restored into a separate empty database, with every row compared across all six tables. Recovery procedures are documented in the [backup guide](17-backups-and-recovery.md).
- Both packaged platforms passed MySQL and MariaDB admission, status, reset history, fresh-cache outage, expired-cache denial, failed-save denial, and recovery through a local TLS relay.
- Stock Paper 1.21.7 build 32 with Java 21 passed clean disabled startup, unsupported-schema denial, and the full packaged admission flow. Its older Adventure API exposed an unnecessary dialog-close call, which was removed and retested.
- A fresh Velocity installation passed disabled-mode world entry and invalid-schema denial. Two real Paper backends passed normal entry, server switching, accepted rejoin, forced-host routing, and fallback. Timestamped relays confirmed no backend connection before acceptance.
- A real restored-primary regression verifies that a replacement cache reads the restored decisions. A still-fresh old cache can retain decisions made after the backup, so recovery requires new cache paths.
- Exact tested API builds, transitive dependency versions, and verification hashes are pinned. The local package builder includes both JARs, source materials, dependency notices, documentation, and checksums. See [local distribution](18-local-distribution.md).
- Review consolidated presentation validation across both platforms, rejected oversized locale tags before storage use, and removed the unused prototype session controller. Shared presentation tests now live beside the shared implementation.
- Admission and status now batch required documents and translation alternatives into one database read. Only complete positive checks enter the cache; authoritative status and exact language checks keep their existing behavior.
- Local test runners restore configuration after startup and shutdown errors, stop their owned server processes, and launch hidden on Windows. Artifact paths follow the configured project version.
- Paper passed a 75-second initial hold and three reconfiguration cycles with official Grim `2.3.74-8eb5f28`. Reconfiguration preserves the accepted connection; changed document versions request consent on the next login, which also passed.

## Remaining release work

Complete the [final real-client check](19-final-client-check.md): Java version boundaries and visible layout, detailed Paper native Bedrock layout/close checks, and Geyser-translated dialogs. Record the remaining results before declaring a release. The local package builder verifies required notices, dependency sources, the source archive, and distribution checksums.

Later BungeeCord and plain Spigot support, external imports, and provider migration tools remain outside the initial release scope. Publishing requires separate approval.

## Local validation commands

On 2026-09-13, the full build after the Bedrock fixes passed 150 JVM tests plus four executions of the provider regression against the two packaged JARs, with no failures or skips. The Python suite passed 12 tests, and the Node helper suite passed eleven. The local package builder now requires both packaged Bedrock reports and includes their results in its validation count.

Earlier checks passed all 15 Velocity protocol-771 groups after the storage fix; protocol 772 passed after the preview change. The packaged Paper JAR passed preview, document viewing, capacity and busy-command checks, acceptance, accepted rejoin, reset history, invalid and valid reload, Leave, and the gate's own timeout. Both platforms passed the updated remote admission checks. Those local test processes stopped afterward and original configurations were restored. The two remote client-test servers remain available with the verified fixes.

The review rerun also verified live Velocity startup rejection for missing, blank, and markup-only required labels. Each invalid configuration denied admission without contacting the backend. Batch-read regressions cover 32 required documents with 32 translations each, a single SQL query, partial results, and cached reconnects after a client-language change.

```powershell
.\gradlew.bat build
python -m unittest discover -s tools -p "test_*.py"
node --test tools/test_paper_probe.cjs
python tools/run_velocity_probe.py
python tools/run_paper_probe.py --modules C:/path/to/node_modules
```

The Paper runner requires the prepared loopback-only server described in the [development guide](05-development.md). It stages a unique SQLite fixture, restores the original configuration after shutdown, and keeps fixture records for inspection. It tests acceptance, accepted rejoin, reset history, version reload, Leave, and timeout with the packaged JAR. The Node dependencies are supplied locally; the runner does not download server software or dependencies.
