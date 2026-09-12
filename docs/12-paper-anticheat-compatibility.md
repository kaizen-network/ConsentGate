# Paper configuration timeout investigation

Date: 2026-09-12. Local prototype investigation, not a production compatibility claim.

## Current status: an upstream fix already exists

An upstream check on 2026-09-12 found [issue #2844](https://github.com/GrimAnticheat/Grim/issues/2844), which reports the same initial configuration timeout during required resource-pack loading. It was closed on 2026-08-26. The matching [upstream fix, `29d8fb4`](https://github.com/GrimAnticheat/Grim/commit/29d8fb4fd0ca6c9d69b7a2a4f6a733669d3be0e3), keeps the transaction clock current until the first transaction is sent and refreshes it at that first send.

The tested revision `63a684d` predates that fix. The inspected `2.0` branch head, `8eb5f2809591c891deb4958bb2927844871e0600`, includes it. This is source verification, not a runtime test of the newer build. Do not infer inclusion from the `2.3.74` version label alone; verify the exact revision.

The upstream check happened after the temporary patch had been built and tested. The patch and results below are retained as evidence for the older revision, not as a proposed replacement for upstream's solution. ConsentGate itself needed no production code change. The test deployment still uses the temporary candidate; this documentation update did not replace it.

Next, test an upstream build containing `29d8fb4` with the same long-wait, play-timeout, consent-timeout, and reconnect checks. If it passes, replace the temporary build through a separately approved test deployment and retire the local workaround. Reconfiguration after a previous PLAY session still needs a separate runtime check; neither these initial-login results nor source inspection proves that case.

Do not open a duplicate issue or submit the old patch as a new fix. If the problem reproduces on a current upstream build, provide the new evidence on #2844 where possible, or open a follow-up referencing it. Any PR should address only the remaining reproduced behavior and follow the [contribution requirements](https://github.com/GrimAnticheat/Grim/blob/2.0/CONTRIBUTING.md), including Java 17 runtime compatibility and supported platforms. No upstream issue, comment, or PR was submitted during this work.

## Cause

GrimAC revision `63a684dcc7f43e0cf140691d88a319d7b815d2a2` creates its player tracking at login success. Its transaction sender returns outside PLAY, but its timeout poller still checks the transaction clock during configuration. A player reading a consent dialog can therefore reach Grim's default 60-second timeout before any transaction ping is sent. See the upstream [login listener](https://github.com/GrimAnticheat/Grim/blob/63a684d/common/src/main/java/ac/grim/grimac/events/packets/PacketPlayerJoinQuit.java) and [transaction handling](https://github.com/GrimAnticheat/Grim/blob/63a684d/common/src/main/java/ac/grim/grimac/player/GrimPlayer.java).

ConsentGate's configuration keepalives continue during this hold. They are separate from Grim's play-stage transaction pings and do not reset that clock.

## Controlled setup

The comparison uses a loopback-only Paper-compatible 26.2 server, a 120-second consent timeout, ViaVersion 5.11.1 snapshot, and ViaBackwards 5.11.1 snapshot. Each connection uses a fresh synthetic identity without prior acceptance. The bot speaks Java 26.1 through protocol translation. No player permissions or anticheat thresholds are changed. Grim uses newly generated local defaults, not production settings. Its timeout is 60 seconds and its optional Discord integration is disabled.

The first same-build comparison retained PacketEvents 2.13.0 and changed only the presence of the supplied Grim JAR. Without Grim, acceptance after a 75-second hold reached play at 77.5 seconds. With Grim revision `63a684d`, the server logged `disconnect.timeout` and closed the connection at 60.4 seconds. The failed connection received 57 keepalives, no play pings, and never submitted acceptance or entered play.

The successfully loaded, unpatched source build reproduced `disconnect.timeout` at 60.5 seconds, with 57 keepalives and no pings. This provides the baseline for comparison with the candidate built using the same bundled dependencies.

A subsequent source-built baseline failed to initialize because its PacketEvents dependency requires `preViaInjection(boolean)`, absent from 2.13.0. The bot survived that run because Grim was disabled by the startup failure. That run is excluded from compatibility evidence. Always check plugin startup logs before treating successful admission as a passing anticheat test.

The PacketEvents Maven dependency is not the complete standalone plugin artifact either. A second startup attempt with that thin JAR failed and no bot was run. Source-build comparisons therefore use Grim's default bundled PacketEvents 2.13.1+8187e23 snapshot, retaining the same separate PacketEvents 2.13.0 plugin in both runs. Both source builds come from the pinned checkout with identical flags. Its default local Maven overrides remain enabled, so this does not establish reproducible public dependency resolution.

Grim warns that ViaBackwards on this server generation is unsupported for older clients using vehicles. Idle admission tests do not establish gameplay or vehicle compatibility.

### Artifact identity

| Artifact | SHA-256 |
| --- | --- |
| Paper-compatible 26.2 server | `458746d458da819669e3c8225bb1d6da43367b54c25abefe4cc213b89beecff8` |
| ConsentGate Paper, unchanged | `b6007cf1031bc2db85dc9137bcb4d0b9fffd999a23befbd35121ca7f43bbd7ff` |
| Separate PacketEvents 2.13.0 | `6d9ece0d87ee727a79a20b7ffbd432021609c6f52bafcb654fc2d3e9b6f064c5` |
| Supplied Grim `63a684d` | `37e8afabaa281a2b51cd59e3b31c270e2f6bd160003d6ea17ce0361772e41ef4` |
| Unpatched source build, bundled PacketEvents | `ee9382b69ae8cb402dfda46b9e6ca870c1549a421cf157198507102c55b43a4d` |
| Candidate source build, bundled PacketEvents | `33455de900731b482321ddf879b88aa651b7081f76331c92f80d2383955c4957` |

These hashes identify local test artifacts, not published downloads. Baseline and candidate Grim builds display the same upstream version string, so use hashes to distinguish them.

## Candidate runtime results

| Check | Observed result |
| --- | --- |
| Read for 75 seconds, then accept | Play entry at 77.6 seconds; remained connected for another 70 seconds; answered 935 play pings |
| Accept immediately, withhold play ping replies | Grim logged `disconnect.timeout` at 64.9 seconds, about 60 seconds after play entry; 878 pings received |
| Do not accept within the gate's 120-second limit | ConsentGate disconnected at 121.7 seconds from connection start; no acceptance submission, play entry, or play pings |

The two idle play clients were killed by a mob without disconnecting. These results check connection and timeout handling, not active gameplay, respawn behavior, or movement-check accuracy. The timeout-withholding result was matched to Grim's server log, not inferred from a closed socket alone.

Read-only SQLite inspection after shutdown found one granted event for each client that submitted acceptance. The original Grim timeout, rebuilt baseline timeout, and gate-timeout identities had no acceptance events. Existing history was retained.

All 93 ConsentGate JVM tests were rerun successfully. The four Python framing tests and seven new Node.js helper tests passed. Grim's common suite passed its three existing checks and five new timeout checks. The exported patch passed `git apply --check` against a clean checkout of the pinned revision.

The local server was stopped after testing. Logs, synthetic acceptance history, and baseline/candidate artifacts were retained locally. The local investigation did not change remote servers; a subsequent test deployment is recorded below.

## Full-stack test deployment

A later deployment on 2026-09-12 used a Paper-compatible 26.2 server with its existing plugin stack. Only the Grim JAR and ConsentGate's top-level enabled flag changed. ConsentGate's JAR, documents, database, and timeout settings were retained. The candidate was uploaded under an inactive suffix, downloaded to verify its SHA-256 against the local artifact, then activated while the server was offline. The original Grim JAR and both plugins' settings/data directories were backed up first.

| Check | Observed result |
| --- | --- |
| Read for 75 seconds, then accept | Play entry at 80.1 seconds from connection start; remained connected for another 70 seconds; answered 959 play pings |
| Accept immediately, withhold play ping replies | Grim logged `disconnect.timeout` at 61.5 seconds from connection start, about 60 seconds after play entry |
| Do not accept | ConsentGate disconnected at 120.8 seconds without acceptance submission or play entry |
| Accepted reconnect | Play entry without another dialog or acceptance submission |
| Console validation and status | Validation passed; accepted identities reported accepted, timed-out identity still required acceptance |

The deployment probe used the same packet encoder and assertions as the local probe. It connected to the test endpoint using the server's permitted handshake hostname. The checked-in probe remains loopback-only.

Grim and ConsentGate both enabled successfully. The synthetic client also reported partial `entity_teleport` packet decoding warnings after play entry. These did not prevent the recorded connection checks, but their cause remains unverified and broader gameplay compatibility is not established.

The test server was left running with the candidate and consent gate enabled. Rollback files and acceptance history were retained. No player permissions, other anticheat settings, or unrelated plugins were changed. No graphical client was opened or controlled.

## Historical local patch

Historical candidate for `63a684d` only. Prefer verifying the existing upstream fix described above. Do not apply this patch on top of a newer Grim build without a separate review.

The [review patch](../tools/compat/grim-63a684d-configuration-timeout.patch) changes Grim, not ConsentGate. It suspends transaction timeout checks outside PLAY and starts a bounded response window on the first PLAY poll. Acknowledgments must still remain current after that window. It does not change the transaction clock used by movement checks, send fake responses, grant exemptions, or increase the configured timeout.

Five focused unit tests cover configuration holds, transition into PLAY, normal acknowledgments followed by timeout, reconfiguration, and monotonic-clock wraparound. Reconfiguration currently has unit coverage only. Network-thread transition races, older clients, Fabric, and broader gameplay checks remain open. This patch is a local candidate, not an upstream release or a bundled ConsentGate dependency.

To inspect or build it in a disposable checkout of the pinned Grim revision:

```powershell
git apply --check C:/path/to/ConsentGate/tools/compat/grim-63a684d-configuration-timeout.patch
git apply C:/path/to/ConsentGate/tools/compat/grim-63a684d-configuration-timeout.patch
.\gradlew.bat :common:test :bukkit:shadowJar -PshadePE=true --configure-on-demand --console=plain
```

Use upstream's license and dependency requirements when handling a derived build. Do not distribute it as an official Grim release. No upstream issue, comment, pull request, or public binary upload was made. The private test deployment is recorded above.

## Headless reproduction

Prepare a disposable, offline-mode server bound to `127.0.0.1:25592`. Enable ConsentGate with a local active document and a timeout longer than the requested hold. Set up compatible protocol translation if the server does not speak Java 26.1 directly. Keep metrics disabled. Do not point this fixture at production storage.

The probe uses Node.js with `minecraft-protocol` 1.68.0 and `prismarine-nbt` 2.8.0. Supply an existing dependency directory using `--modules`. It does not download dependencies, change server settings, start a server, or clear acceptance records.

```powershell
node --test tools/test_paper_probe.cjs
node tools/probe_paper.cjs --modules C:/path/to/node_modules --name ConsentProbeA --hold 75 --play-seconds 70 --expect accepted
node tools/probe_paper.cjs --modules C:/path/to/node_modules --name ConsentProbeB --hold 0 --play-seconds 90 --reply-pings false --expect play-timeout
node tools/probe_paper.cjs --modules C:/path/to/node_modules --name ConsentProbeC --hold 150 --expect denied
```

The third command checks the gate's own timeout with a 120-second fixture. It must disconnect without acceptance or play entry even when Grim's configuration timeout is suspended.

For an unpatched configuration-timeout reproduction, use a new name with `--hold 75 --expect denied`. Stop the local server before replacing only its Grim JAR, then restart and repeat with another fresh name. Preserve logs and JAR hashes from each run. Use the administrator's offline reset command if reusing an identity, rather than deleting the database.

The probe selects the first language and checks every required agreement after the requested wait. It intentionally records acceptance for its synthetic identity. It does not prove that documents were read. It responds to keepalives, optionally answers play pings, and confirms teleports without issuing movement, build, or administrator commands.

An `accepted` result requires admission after the probe submits acceptance and staying connected for the full observation time. `denied` checks disconnection before submission and without play entry. `play-timeout` checks disconnection after admission while pings are withheld. Disconnect-only results do not identify the responsible plugin: correlate them with the server's reason and timing. Transport/parser errors fail the probe. The raw custom-click encoder is restricted to Java 26.1 and covered by fixed-byte tests.

## Visual checks

This is a protocol bot, not a graphical Minecraft client. It cannot capture a rendered dialog screenshot. No Minecraft client or launcher was opened or controlled during this investigation. Screenshots and checks of wrapping, GUI scale, button appearance, and touch/controller navigation remain manual. A protocol trace is not visual evidence.
