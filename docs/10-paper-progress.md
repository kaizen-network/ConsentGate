# Paper implementation progress

Status: first Java admission slice. Not a production release or full platform parity.

## Implemented

- Shared configuration, local documents, versions, translations, and SQLite acceptance.
- Native Paper dialogs with summary checkboxes, optional language selection, full reading pages, and two-column navigation.
- Accepted reconnects use the shared acceptance rules, including accepted translations.
- Two database workers with a bounded queue and the configured pending-connection limit.
- Only the asynchronous configuration event waits for completion. The main server thread does not wait for storage or player input.
- Acceptance is saved before the held configuration event is released. Decline, timeout, startup failure, and storage failure disconnect the connection.
- Strict checkbox field/type validation and delayed redisplay for invalid submissions.
- Administrator status, offline reset, validation, and reload, with shared permission and UUID rules.
- Reset preserves history, reserves the target UUID, and waits for an outstanding save. Accepted connections remain protected against reset until world join or disconnect.
- Reload refuses active sessions and database work; unsupported native forms, unsafe presentation, and incompatible revisions leave the running settings unchanged.

The `presentation` module supplies the safe text formatter, interface messages, and bundled defaults to both platforms. Paper does not depend on the Velocity plugin or PacketEvents. Paper bundles and relocates Adventure NBT and Examination for strict response parsing. Paper's NBT API types and Adventure text APIs remain platform-provided.

## Installation for testing

Use `ConsentGate-Paper-0.1.0-prototype.jar` on a disposable Paper-compatible server. Fresh installations are disabled and create inactive example documents. Adapt the examples, rename selected documents to `.yml`, then enable the gate and restart.

Leave `bedrock.native-forms: false`. Native Cumulus forms are not implemented on Paper yet; enabling that option makes startup fail closed rather than silently promising an unavailable renderer. Geyser-translated native dialogs still need separate Paper testing.

Do not install the gate on both a proxy and its backend for the same admission requirement. Direct backend access and proxy forwarding need their own network configuration.

## Remaining parity work

Live testing on 2026-09-12 used a Paper-compatible 26.2 server with protocol translation and a Java 26.1 synthetic client. It verified Indonesian selection, unchecked submission rejection, stale-token rejection, two-page reading, Back, acceptance followed by play login, accepted reconnect without a dialog, and immediate Leave disconnection. SQLite inspection confirmed one granted event for the shown version and `id-ID` locale. The bot used the documented corrected custom-click encoding, not an unmodified client.

The full stack reproduced a GrimAC `disconnect.timeout` about 60 seconds after login success despite continuing keepalive replies. A later same-build comparison confirmed the timeout with Grim present and successful acceptance after a 75-second hold without it. Do not claim prolonged-wait compatibility with the unpatched stack. See [administration results](11-paper-administration-test-results.md) and the [controlled anticheat investigation](12-paper-anticheat-compatibility.md).

1. Expand Paper command testing to admission races, queue rejection, and storage failures. Console status/reset/reload and in-game permission/reply checks now pass.
2. Validate native Bedrock integration at Paper's configuration connection, then share the compatible presentation code.
3. Resolve the long-wait anticheat compatibility issue, then expand automated lifecycle coverage: disconnect/save races, shutdown, full queue, locked SQLite, timeouts, and connection limits.
4. Measure world-entry timing with native Paper events and real clients, including protocol translation and reconnects.
5. Verify the oldest supported Paper build and client versions. Compilation against the 1.21.7 API is not a complete compatibility claim.

The Paper event's [API documentation](https://jd.papermc.io/paper/1.21.7/io/papermc/paper/event/connection/configuration/AsyncPlayerConnectionConfigureEvent.html) places it before world entry and resumes the connection after the handler finishes. Runtime testing must still check that callbacks arrive while the event is held and that no play login is sent before acceptance.

## Local administrator checks

The 2026-09-12 command implementation passes the shared permission, target parsing, save/reset ordering, and queued/running job accounting tests. Paper-specific presentation tests check bundled defaults, native-form rejection, unsafe markup, missing messages, and keeping the running runtime usable after failed validation. The local Velocity wire suite passed all 14 checks after extracting the shared command rules. Console and in-game runtime checks are recorded in the [test results](11-paper-administration-test-results.md).

Keep the following checklist for regression testing; the race and failure cases are not all covered yet:

1. Console and authorized players receive status and validation replies; each command rejects users without its own permission.
2. Reset refuses a player reading documents, an accepted connection not yet in the world, and an online player. After disconnect, reset keeps history and requires consent on reconnect.
3. Reload refuses a held dialog and pending storage work. Invalid edits keep old settings; a valid version bump takes effect on the next connection.
4. Disconnect during a queued save, failed storage, shutdown, and a full queue never admit an unaccepted connection or let an older save undo reset.

## Long-wait investigation

GrimAC revision `63a684d` starts player tracking at login success and checks its transaction timeout during configuration, although transaction sends return outside PLAY. The [same-build investigation](12-paper-anticheat-compatibility.md) records the reproduction, a candidate upstream patch, and its limits. ConsentGate does not bundle that patch or bypass anticheat checks.

The synthetic client was updated to reply to pings and log connection state. No pings arrived during the failed configuration hold; keepalives continued until disconnect. Pings arrived and were answered after play login. Anticheat settings were not changed. Do not shorten reading time or disable anticheat globally as a workaround.
