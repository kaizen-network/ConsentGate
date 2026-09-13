# Dialog compatibility investigation

Status: the encoding cause is identified from official client bytecode. See [findings](09-protocol-compatibility-findings.md) for the bot-library defect, evidence, and remaining client checks. The plan below remains the verification checklist; a custom test encoder alone is not proof of standard-client compatibility.

## What is known

- The installed Java bot library connected using 1.21.8 and received the language selector.
- Its normal custom-click response was rejected. The packet reader reported an unexpected end of data while reading length-prefixed NBT.
- A custom response encoder using a length prefix passed the language selector and acceptance flow. The existing local wire probe uses that same framing approach.
- Live bot checks confirmed reload refusal during an active dialog, a new-version prompt, saved acceptance, and an accepted reconnect reaching a backend.
- Separate real-client tests confirmed the current Java and native Bedrock flow. They do not establish compatibility across all earlier Java versions.

The bot and local probe are not independent references for the disputed packet format. Neither identifies the cause by itself.

## Questions to resolve

1. What response format does an unmodified client send at each relevant protocol version?
2. Does the bot library encode the correct format for its selected version?
3. Does PacketEvents select the correct client version and packet layout in Velocity's configuration phase?
4. Does the proxy or a protocol translation plugin transform the packet before ConsentGate reads it?
5. Are configuration-phase and play-phase dialog packets being mixed up in a test or adapter?

Possible causes include outdated bot definitions, a version-specific PacketEvents issue, incorrect version information, packet translation ordering, or a plugin integration mistake. Keep these as hypotheses until reproduced.

## Phase 1: preserve and inspect

- Record exact proxy, PacketEvents, protocol translation, bot library, and client versions. Record JAR hashes and resolved dependency versions, not just snapshot labels.
- Preserve the failing normal-bot response and passing custom response as minimal synthetic fixtures. Exclude credentials, player addresses, and unrelated traffic.
- Inspect the installed bot serializer, the matching PacketEvents source or bytecode, and ConsentGate's wrapper construction. Trace which client/server version each reader uses.
- Compare the payload presence marker, length prefix, NBT root encoding, packet ID, and connection phase. Record offsets and decoded fields rather than relying only on a hex dump.
- Establish the reference format from an unmodified client and matching version-specific implementation evidence. A second library is useful supporting evidence, but not sufficient if both libraries share definitions.

Deliverable: a short findings table with expected bytes, observed bytes, selected versions, and the first point where they differ. Cite exact source revisions when source inspection is used.

## Phase 2: isolate the environment

Start on a disposable loopback proxy with only ConsentGate and PacketEvents. Use an instrumented backend listener to detect any handshake before acceptance. Run one client at a time.

| Test group | Client selection | Purpose |
| --- | --- | --- |
| Minimum boundary | Java 1.21.6 | Verify the currently intended minimum, not merely the version check in code |
| Reproduction | Java 1.21.8 | Reproduce the observed normal-bot failure |
| Format boundaries | Releases immediately before and after any encoding change found in phase 1 | Test the actual branch boundaries without guessing them |
| Newer client | The exact newer Java build already used in real-client testing | Guard against regressing the working flow |
| Unsupported client | One version below the intended minimum | Confirm a clear rejection without backend admission |

For each applicable row, distinguish an unmodified client, an unmodified bot library, and synthetic packet fixtures. Record unsupported bot versions as unavailable, not as failures of ConsentGate.

Once the minimal case is understood, compare with the dedicated test proxy's normal plugin stack. Add protocol translation and optional integrations to the minimal setup one at a time where needed. Do not disable unrelated plugins on the shared test proxy to obtain the minimal case.

## Phase 3: choose the smallest supported fix

| Finding | Action |
| --- | --- |
| Bot definitions are wrong | Correct or pin the test dependency; keep plugin decoding strict |
| PacketEvents has a version-specific defect | Prefer a verified upstream fix; otherwise isolate a narrowly versioned adapter with regression tests |
| ConsentGate chooses the wrong format or version | Fix the adapter and cover each affected boundary |
| Translation changes the packet at the wrong stage | Establish the supported event ordering or document the incompatible combination |
| Evidence remains inconclusive | Keep the support claim provisional and state the missing evidence |

Do not accept multiple encodings by trial and error. Do not catch a decode failure and silently reinterpret the packet using another layout. Do not weaken token, checkbox, or connection checks to make a bot pass.

Avoid a new runtime dependency unless the findings justify it. Do not report an upstream defect publicly without separate approval.

## Phase 4: regression checks

For each supported encoding branch, check language selection, document reading, Previous/Next/Back, unchecked submission, full acceptance, Leave, and reconnect. Confirm that persisted records match the version and language shown.

Also test truncated NBT, invalid lengths, missing or extra checkbox fields, wrong types, foreign tokens, repeated responses, and responses from the wrong connection phase. Invalid input must not write an acceptance or release a backend connection. Check that rejected connections clean up their pending sessions.

Retain the existing timeout, keepalive, storage-failure, reset, and reload checks. Recheck Java Leave behavior and optional Geyser absence. Smoke-test native Bedrock acceptance if a shared session or connection path changes.

Update the wire probe only after the reference format is established. Fixtures should describe the protocol version and source of the expected encoding. Separate a deliberate malformed-packet test from a normal-client test.

## Test-server safeguards

- Use only the dedicated test proxy for live checks. Keep server identifiers, connection details, and credentials outside tracked files.
- Back up the installed JAR and any edited configuration or documents before deploying a candidate.
- Use a dedicated synthetic test account. Do not impersonate a real account or reset unrelated players.
- Keep bots bounded by a time limit and stop them after the check. No gameplay, building, chat automation, or unrelated commands.
- Use temporary document versions only when needed. Restore the exact original files and reload them after the test, including on failure.
- Verify the final running version and restored configuration. Keep acceptance history; do not delete the database.
- If a candidate fails, restore the previous JAR and configuration, restart, and verify normal startup before ending the test.

## Completion gate

This investigation is complete when the cause is supported by independent evidence, the relevant version boundaries have passing regression checks, and documentation clearly separates verified support from pending checks. The custom encoder alone cannot close it.

If an unmodified client cannot be run in the available environment, finish the source and automated checks, then request one narrowly specified client test. Do not label bot output as visual verification.

Record the outcome in the development validation matrix. Commit and push remain separate approved actions.

## Work after this investigation

1. **Paper parity:** reuse the shared document, locale, admission, SQLite, and admin behavior. Adapt native dialogs and bounded asynchronous storage to Paper's lifecycle. Keep platform types out of the shared core.
2. **Paper admission proof:** measure configuration completion, world/player creation, and first world entry. Test accepted reconnect, version changes, timeout, disconnect, shutdown, database failure, and reload. Do not claim before-world-entry support from compilation alone.
3. **Shared storage:** implemented and checked against both MySQL and MariaDB, including cache expiry, withdrawals, outages, recovery, TLS, and uncertain commits. Keep H2 deferred.
4. **Release preparation:** pin dependencies, finish permissions and compatibility documentation, review dependency licenses, and produce local checksummed artifacts. Public distribution remains a separate decision.

Each stage should produce a tested, documented local change before moving to the next. A finding that changes the platform or compatibility promise needs an explicit decision rather than a silent change in scope.
