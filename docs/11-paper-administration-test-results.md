# Paper administration test results

Date: 2026-09-12. These are prototype checks, not a production support claim.

## Environments

- Full plugin stack: Paper-compatible 26.2 server, ViaVersion 5.11.1 snapshot, ViaBackwards 5.11.1 snapshot, and GrimAC 2.3.74 revision `63a684d`.
- Minimal loopback server: Paper-compatible 26.2 server with only ConsentGate and the same protocol translation plugins.
- Synthetic Java 26.1 client with corrected custom-click framing, automatic keepalive replies, and explicit ping replies. It did not move, build, or run gameplay commands.

The two server builds in this initial administration run differ. A later [same-build anticheat comparison](12-paper-anticheat-compatibility.md) reproduced the configuration timeout with Grim present and successful prolonged waiting without it. Neither run establishes compatibility with every plugin stack.

## Results

| Check | Observed result |
| --- | --- |
| Console status and validation | Current document status and validation result returned |
| Reset during a consent dialog or while online | Refused with the target UUID |
| Offline reset and reconnect | Dialog shown again; acceptance then allowed play login |
| Accepted reconnect | Play login without another dialog |
| Reload during a held dialog | Refused without interrupting the dialog |
| Changed text without a version bump | Rejected; status still showed the running revision |
| Valid version bump | Reload succeeded; next connection required the new version |
| Native Bedrock forms enabled on Paper | Rejected; running configuration retained |
| In-game commands on the minimal server | Permission denial before authorization; status and validation replies after authorization |
| Long wait on the minimal server | Keepalives continued beyond 90 seconds; acceptance then allowed play login |
| Configured timeout on the minimal server | Disconnected after the configured 120 seconds without play login or acceptance records |
| Long wait on the full stack | GrimAC logged `disconnect.timeout` about 60 seconds after login success, before play login |

The full stack's existing command filter intercepted player commands before ConsentGate. Its rules were left unchanged. Player-side command delivery was tested on the minimal server instead. Temporary test permissions were removed afterward.

SQLite inspection after shutdown confirmed granted and withdrawn history for the test UUID, a later grant for the reloaded version, and a final grant for the restored original version. Reset did not erase earlier acceptance events. The timed-out local test UUID had no acceptance events.

## Packaging fixes found by runtime testing

The original Paper artifact bundled Adventure NBT without its Examination runtime dependencies. On the full stack another plugin made those classes available, hiding the missing dependency. The minimal server exposed `NoClassDefFoundError` when parsing acceptance.

Paper now bundles and relocates Adventure NBT and Examination. Paper's `net.kyori.adventure.nbt.api` types remain untouched because they are part of the platform API. An initial relocation also changed a platform method signature; the runtime check caught that and a bytecode regression check now guards the boundary.

The isolated artifact test loads the shaded parser with only the Java platform class loader as its parent. It checks valid and invalid checkbox values, absence of the original bundled parser package, required license notices, and preservation of Paper API parameter types. The missing-dependency test failed before the fix and passes afterward.

## Remaining checks

- Review and validate the [candidate Grim configuration-stage fix](12-paper-anticheat-compatibility.md). The same-build comparison is complete. Do not disable anticheat globally or reduce reading time to conceal the problem.
- Paper-specific full-queue, locked-storage, and save/reset/disconnect race tests, including the interval between configuration completion and world join.
- Native Bedrock forms on Paper and unmodified Java client checks at the supported version boundaries.

Final tested Paper artifact SHA-256: `b6007cf1031bc2db85dc9137bcb4d0b9fffd999a23befbd35121ca7f43bbd7ff`.

All 93 JVM tests passed. After the final packaging correction, the full-stack server passed acceptance, accepted reconnect, console status, and validation again. The deployed JAR was downloaded and its SHA-256 matched the local artifact.

Both test servers were stopped afterward. The remote server's original documents and disabled test configuration were restored and checked. Acceptance history and recoverable JAR backups were retained. No anticheat settings were changed.
