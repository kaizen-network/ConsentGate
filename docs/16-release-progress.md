# Release progress

Status: core implementation is complete. Final real-client checks remain. Neither artifact is a production release.

## Current implementation

- Core documents, translations, versioned acceptance, SQLite history, and administrator commands are implemented.
- Login preview and active-document viewing are implemented on both platforms. Preview uses the actual admission renderer but never saves consent; the shared storage entry point rejects preview requests.
- Both packaged Java paths passed preview for new and accepted players, preserved-history checks, cancellation, document viewing, and refusal to queue previews for connected players. Paper also passed live capacity rejection and busy reset/reload checks while a player reviewed consent.
- Velocity supports Java dialogs and native Bedrock forms. Its 15 local protocol check groups include preview and document viewing.
- Paper uses the shared native Bedrock renderer through optional local Geyser-Spigot. Its Java path remains independent of Geyser and PacketEvents.
- Paper admission now has direct lifecycle tests for delayed saves, cancellation, reset ordering, locked SQLite, shutdown, timeout, and presentation failures. A renderer linkage failure cannot release a successful admission.
- A native-response/timeout lock regression was reproduced and fixed. Core decision callbacks now run outside the session lock, while the terminal decision and selections remain frozen.
- Remote MySQL/MariaDB storage and persistent cache are implemented. Each database passed all 17 checks including TLS verification, actual socket interruptions around commit, reset-result verification, and a 30-second workload across two instances.
- SQLite now rejects older and replayed saves after withdrawal, including resets before first acceptance. Both stores reject resets that leave a newer grant active. Five failing SQLite cases were reproduced before the fix. Existing version-1 history and a restored closed-file backup pass regression checks.
- MySQL and MariaDB dumps were each restored into a separate empty database, with every row compared across all six tables. Recovery procedures are documented in the [backup guide](17-backups-and-recovery.md).
- Both packaged platforms passed MySQL and MariaDB admission, status, reset history, fresh-cache outage, expired-cache denial, failed-save denial, and recovery through a local TLS relay.
- Stock Paper 1.21.7 build 32 with Java 21 passed clean disabled startup, unsupported-schema denial, and the full packaged admission flow. Its older Adventure API exposed an unnecessary dialog-close call, which was removed and retested.
- A fresh Velocity installation passed disabled-mode world entry and invalid-schema denial. Two real Paper backends passed normal entry, server switching, accepted rejoin, forced-host routing, and fallback. Timestamped relays confirmed no backend connection before acceptance.
- A real restored-primary regression verifies that a replacement cache reads the restored decisions. A still-fresh old cache can retain decisions made after the backup, so recovery requires new cache paths.
- Exact tested API builds, transitive dependency versions, and verification hashes are pinned. The local package builder includes both JARs, source materials, dependency notices, documentation, and checksums. See [local distribution](18-local-distribution.md).
- Paper passed a 75-second initial hold and three reconfiguration cycles with official Grim `2.3.74-8eb5f28`. Reconfiguration preserves the accepted connection; changed document versions request consent on the next login, which also passed.

## Remaining release work

Complete the [final real-client check](19-final-client-check.md): Java version boundaries and visible layout, plus Paper native Bedrock forms and Geyser-translated dialogs. Record those results before declaring a release. The local package builder verifies required notices, dependency sources, the source archive, and distribution checksums.

Later BungeeCord and plain Spigot support, external imports, and provider migration tools remain outside the initial release scope. Publishing requires separate approval.

## Local validation commands

On 2026-09-13, the full build passed 139 JVM tests with no failures or skips. The Python framing suite passed four tests, and the Node helper suite passed eleven. All 15 Velocity protocol-771 check groups passed after the storage fix; protocol 772 passed after the preview change. The packaged Paper JAR passed preview, document viewing, capacity and busy-command checks, acceptance, accepted rejoin, reset history, invalid and valid reload, Leave, and the gate's own timeout. Both platforms passed the updated remote admission checks. Test processes stopped afterward and original configurations were restored.

```powershell
.\gradlew.bat build
python -m unittest discover -s tools -p "test_*.py"
node --test tools/test_paper_probe.cjs
python tools/run_velocity_probe.py
python tools/run_paper_probe.py --modules C:/path/to/node_modules
```

The Paper runner requires the prepared loopback-only server described in the [development guide](05-development.md). It stages a unique SQLite fixture, restores the original configuration after shutdown, and keeps fixture records for inspection. It tests acceptance, accepted rejoin, reset history, version reload, Leave, and timeout with the packaged JAR. The Node dependencies are supplied locally; the runner does not download server software or dependencies.
