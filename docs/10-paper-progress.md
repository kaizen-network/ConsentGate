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

The `presentation` module supplies the safe text formatter, interface messages, and bundled defaults to both platforms. Paper does not depend on the Velocity plugin or PacketEvents. Paper bundles Adventure NBT for strict response parsing; it does not bundle another copy of the Adventure text APIs.

## Installation for testing

Use `ConsentGate-Paper-0.1.0-prototype.jar` on a disposable Paper-compatible server. Fresh installations are disabled and create inactive example documents. Adapt the examples, rename selected documents to `.yml`, then enable the gate and restart.

Leave `bedrock.native-forms: false`. Native Cumulus forms are not implemented on Paper yet; enabling that option makes startup fail closed rather than silently promising an unavailable renderer. Geyser-translated native dialogs still need separate Paper testing.

Do not install the gate on both a proxy and its backend for the same admission requirement. Direct backend access and proxy forwarding need their own network configuration.

## Remaining parity work

Live testing on 2026-09-12 used a Paper-compatible 26.2 server with protocol translation and a Java 26.1 synthetic client. It verified Indonesian selection, unchecked submission rejection, stale-token rejection, two-page reading, Back, acceptance followed by play login, accepted reconnect without a dialog, and immediate Leave disconnection. SQLite inspection confirmed one granted event for the shown version and `id-ID` locale. The bot used the documented corrected custom-click encoding, not an unmodified client.

The longer waiting attempt ended with a `disconnect.timeout` message logged by GrimAC. Its cause is not isolated: client behavior, configuration-stage integration, and anticheat interaction still need comparison on a minimal server. Do not claim prolonged-wait compatibility with that plugin stack yet. The test container was stopped afterward and its temporary gate disabled; records were retained.

1. Live-test Paper status/reset and validation/reload commands, including permission checks, reply delivery, admission races, queue rejection, and unchanged running settings after failure.
2. Validate native Bedrock integration at Paper's configuration connection, then share the compatible presentation code.
3. Isolate the long-wait disconnect, then expand automated lifecycle coverage: disconnect/save races, shutdown, full queue, locked SQLite, timeouts, and connection limits.
4. Measure world-entry timing with native Paper events and real clients, including protocol translation and reconnects.
5. Verify the oldest supported Paper build and client versions. Compilation against the 1.21.7 API is not a complete compatibility claim.

The Paper event's [API documentation](https://jd.papermc.io/paper/1.21.7/io/papermc/paper/event/connection/configuration/AsyncPlayerConnectionConfigureEvent.html) places it before world entry and resumes the connection after the handler finishes. Runtime testing must still check that callbacks arrive while the event is held and that no play login is sent before acceptance.

## Local administrator checks

The 2026-09-12 command implementation passes the shared permission, target parsing, save/reset ordering, and queued/running job accounting tests. Paper-specific presentation tests check bundled defaults, native-form rejection, unsafe markup, missing messages, and keeping the running runtime usable after failed validation. The local Velocity wire suite passed all 14 checks after extracting the shared command rules. These checks do not replace live Paper command testing.

Before deploying this slice, use a disposable test UUID and confirm:

1. Console and authorized players receive status and validation replies; each command rejects users without its own permission.
2. Reset refuses a player reading documents, an accepted connection not yet in the world, and an online player. After disconnect, reset keeps history and requires consent on reconnect.
3. Reload refuses a held dialog and pending storage work. Invalid edits keep old settings; a valid version bump takes effect on the next connection.
4. Disconnect during a queued save, failed storage, shutdown, and a full queue never admit an unaccepted connection or let an older save undo reset.

## Long-wait investigation

Source inspection found a plausible configuration-stage timeout path in GrimAC revision `63a684d`: its [login listener](https://github.com/GrimAnticheat/Grim/blob/63a684d/common/src/main/java/ac/grim/grimac/events/packets/PacketPlayerJoinQuit.java) adds a user at login success. In [GrimPlayer](https://github.com/GrimAnticheat/Grim/blob/63a684d/common/src/main/java/ac/grim/grimac/player/GrimPlayer.java), transaction sends return outside the play phase, while `pollData()` checks time since the last transaction clock without that phase guard. This could expire during an extended configuration hold if that player is being polled. It is a hypothesis, not a confirmed runtime cause.

The synthetic client's installed protocol library handles keepalives but does not automatically reply to ping packets. A follow-up client must reply to pings and log connection state before comparing a minimal Paper server with the anticheat stack. Do not shorten reading time or disable anticheat globally as a workaround. Neither anticheat settings nor remote servers were changed during this local investigation.
