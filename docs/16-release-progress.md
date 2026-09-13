# Release progress

Status: implementation and verification in progress. Neither artifact is a production release.

## Current implementation

- Core documents, translations, versioned acceptance, SQLite history, and administrator commands are implemented.
- Velocity supports Java dialogs and native Bedrock forms. Its 14 local protocol checks pass after sharing the Bedrock renderer.
- Paper uses the shared native Bedrock renderer through optional local Geyser-Spigot. Its Java path remains independent of Geyser and PacketEvents.
- Paper admission now has direct lifecycle tests for delayed saves, cancellation, reset ordering, locked SQLite, shutdown, timeout, and presentation failures. A renderer linkage failure cannot release a successful admission.
- A native-response/timeout lock regression was reproduced and fixed. Core decision callbacks now run outside the session lock, while the terminal decision and selections remain frozen.
- Remote MySQL/MariaDB storage and persistent cache are implemented. Earlier MariaDB repository checks pass; remaining database checks are listed below.

## Remaining release work

1. Finish Paper runtime admission, administrator, connection-pressure, and reconfiguration checks. Verify native Bedrock form delivery and fallback at the held configuration stage.
2. Run repository checks against MySQL, then test remote storage through both live platform adapters. Cover TLS, actual interrupted connections, cache expiry, and multi-instance reset timing.
3. Verify supported Java client and platform boundaries, long waits, routing, GUI layout, and Bedrock navigation. Synthetic clients do not replace final unmodified-client checks.
4. Test clean installs and upgrades, backups and schema recovery, then reconcile older planning documents with verified behavior.
5. Pin build dependencies and prepare local release artifacts, checksums, dependency notices, corresponding sources, and release notes.

Later BungeeCord and plain Spigot support, external imports, and provider migration tools remain outside the initial release scope. Publishing requires separate approval.

## Local validation commands

On 2026-09-13, the full build passed 125 JVM tests with no failures or skips. The Python framing suite passed four tests, and the Node helper suite passed eight. After the lifecycle fix, all 14 Velocity protocol-772 checks passed again. The packaged Paper JAR passed acceptance, accepted rejoin, reset history, invalid and valid reload, Leave, and the gate's own timeout. Both headless test processes stopped afterward.

```powershell
.\gradlew.bat --offline build
python -m unittest discover -s tools -p "test_*.py"
node --test tools/test_paper_probe.cjs
python tools/run_velocity_probe.py
python tools/run_paper_probe.py --modules C:/path/to/node_modules
```

The Paper runner requires the prepared loopback-only server described in the [development guide](05-development.md). It stages a unique SQLite fixture, restores the original configuration after shutdown, and keeps fixture records for inspection. It tests acceptance, accepted rejoin, reset history, version reload, Leave, and timeout with the packaged JAR. The Node dependencies are supplied locally; the runner does not download server software or dependencies.
