# Paper implementation progress

Status: Java admission passes on stock Paper 1.21.7 and the newer test server. A real Bedrock client passed native acceptance, world entry, and reconnect on a Paper-compatible 26.2 server. Current work is tracked in [release progress](16-release-progress.md).

## Implemented

- Shared configuration, local documents, versions, translations, and SQLite acceptance.
- Native Paper dialogs with summary checkboxes, optional language selection, full reading pages, and two-column navigation.
- Optional native Bedrock forms through local Geyser-Spigot, using the same renderer as Velocity. The [client test results](19-final-client-check.md#findings-from-september-13-2026) record the verified Paper setup.
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

Keep `bedrock.native-forms: false` for the initial Java check. For native Bedrock testing, install Geyser-Spigot on the same server and enable the option. Paper declares Geyser as an optional dependency. Java-only installations still load without it. Verify native delivery on your selected server build; Geyser-translated dialogs require a separate check.

Do not install the gate on both a proxy and its backend for the same admission requirement. Direct backend access and proxy forwarding need their own network configuration.

## Remaining parity work

Stock Paper 1.21.7 build 32 with Java 21.0.2 passed fresh disabled startup, generated defaults, normal disabled-mode play, and denial when an enabled installation contained a newer unsupported database schema. The packaged admission flow also passed preview without saved consent, accepted-player preview, cancellation, document viewing, capacity rejection, busy reset/reload refusal, acceptance, accepted rejoin, reset history, version reload, Leave, and timeout. These checks used synthetic Java protocol 772, which 1.21.7 and 1.21.8 share.

This runtime check exposed an Adventure API mismatch that compilation missed: Paper 1.21.7 lacks `Audience.closeDialog()`. Java actions already close their dialog, so the redundant call was removed. Denial closes the configuration screen by disconnecting; native Bedrock forms retain their explicit close call. The corrected packaged flow passed on the same stock server.

Live testing on 2026-09-12 used a Paper-compatible 26.2 server with protocol translation and a Java 26.1 synthetic client. It verified Indonesian selection, unchecked submission rejection, stale-token rejection, two-page reading, Back, acceptance followed by play login, accepted reconnect without a dialog, and immediate Leave disconnection. SQLite inspection confirmed one granted event for the shown version and `id-ID` locale. The bot used the documented corrected custom-click encoding, not an unmodified client.

The older GrimAC revision `63a684d` caused a configuration timeout around 60 seconds. Official build `2.3.74-8eb5f28` includes upstream's fix and passes the initial-login long-wait, play-timeout, consent-timeout, and reconnect checks. Use the exact tested revision, not the broad `2.3.74` label. See [administration results](11-paper-administration-test-results.md) and the [controlled anticheat investigation](12-paper-anticheat-compatibility.md).

1. Finish detailed native Bedrock layout/close checks and validate the translated-dialog path on Paper with real clients. Native acceptance, world entry, and reconnect passed on the documented test installation.
2. Check unmodified Java clients, visible world-entry timing, long text, large GUI scale, and reconnects on the advertised versions.
3. Other plugins that deliberately hold reconfiguration after PLAY need their own compatibility checks. ConsentGate's normal reconfiguration and next-login version behavior pass with the exact Grim build below.

The Paper event's [API documentation](https://jd.papermc.io/paper/1.21.7/io/papermc/paper/event/connection/configuration/AsyncPlayerConnectionConfigureEvent.html) places it before world entry and resumes the connection after the handler finishes. Runtime testing must still check that callbacks arrive while the event is held and that no play login is sent before acceptance.

## Local administrator checks

The shared command implementation passes permission, target parsing, save/reset ordering, and queued/running job accounting tests. Paper presentation tests cover bundled defaults, optional native-form configuration, unsafe markup, missing messages, and preserving the runtime after failed validation. Eleven Paper admission tests cover commit-before-release, queued cancellation, full queues, locked SQLite, save/reset ordering after disconnect, shutdown, timeout, rendering failures, and native callback lock ordering. Earlier console and in-game checks are recorded in the [test results](11-paper-administration-test-results.md).

Keep the following covered behaviors in the regression checklist:

1. Console and authorized players receive status and validation replies; each command rejects users without its own permission.
2. Reset refuses a player reading documents, an accepted connection not yet in the world, and an online player. After disconnect, reset keeps history and requires consent on reconnect.
3. Reload refuses a held dialog and pending storage work. Invalid edits keep old settings; a valid version bump takes effect on the next connection.
4. Disconnect during a queued save, failed storage, shutdown, and a full queue never admit an unaccepted connection or let an older save undo reset.

## Long-wait investigation

GrimAC revision `63a684d` starts player tracking at login success and checks its transaction timeout during configuration, although transaction sends return outside PLAY. The [same-build investigation](12-paper-anticheat-compatibility.md) records the reproduction, historical patch, and passing official upstream build `8eb5f28`, which includes fix `29d8fb4`. ConsentGate does not bundle Grim or bypass anticheat checks.

The synthetic client was updated to reply to pings and log connection state. No pings arrived during the failed configuration hold; keepalives continued until disconnect. Pings arrived and were answered after play login. Anticheat settings were not changed. Do not shorten reading time or disable anticheat globally as a workaround.
