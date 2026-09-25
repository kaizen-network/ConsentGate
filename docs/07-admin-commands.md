# Administrator commands

These commands are implemented on Velocity and Paper. `validate` and `reload` also work after a successful disabled startup. Other commands require an enabled gate; status and reset also need storage to be available. Velocity commands have wire and console testing. Paper has console and in-game checks on test servers; see the [Paper test results](11-paper-administration-test-results.md) for coverage and remaining race tests.

| Command | Permission | Result |
| --- | --- | --- |
| `/consentgate status <online-player\|uuid>` | `consentgate.admin.status` | Shows whether each current required document has a valid acceptance in any configured translation |
| `/consentgate reset <uuid>` | `consentgate.admin.reset` | Withdraws acceptance for the current required documents in the configured scope |
| `/consentgate validate` | `consentgate.admin.validate` | Checks whether edited files can safely replace the running configuration, without applying changes |
| `/consentgate reload` | `consentgate.admin.reload` | Validates and applies supported changes for new connections |
| `/consentgate preview <uuid> [locale\|cancel]` | `consentgate.admin.preview` | Queues one login preview, or cancels a queued preview; never saves consent |
| `/consentgate document [id] [locale] [page]` | `consentgate.admin.document` | Lists active documents or reads one page in chat or console |

Run these in the proxy or server console without the leading slash, or grant the relevant permission through your permissions plugin. Paper defaults these permissions to operators. Status permission does not grant reset permission. Paper resolves online names and sends command replies on the main server thread; database work stays on the worker queue.

## Reset for testing or administration

1. Use `status` while the player is online to find their UUID.
2. Have the player disconnect.
3. Run `consentgate reset <uuid>` and wait for the success message.
4. Reconnect. The player must accept again.

Reset refuses connected players, including players still reviewing documents. Connections for the target UUID are rejected while reset is pending. An earlier save finishes before reset writes its withdrawal. Reset does not kick players or present dialogs inside a backend server.

No database deletion is needed. Existing acceptance events and document snapshots remain intact; reset adds withdrawal records with the `admin-reset` method. Other scopes and documents outside the current required set are unchanged. A UUID with no previous acceptance is already required to accept; resetting it records the request without inventing an acceptance.

Use the full UUID known to the proxy or server, or stored in the consent database, for offline players. Offline names are not looked up or converted to guessed UUIDs. Authentication and forwarding changes can change player identity.

Status, reset, validation, and reload use the bounded database worker queue. A full queue returns an error. Commands that read or write consent records also report storage failures; validation while disabled does not open storage. Check status before retrying an uncertain result. Administrator responses are currently English.

## Preview and document viewing

Disconnect the test player, then run `consentgate preview <uuid>`. Connect within five minutes to view the normal login flow even if that player already accepted. Read, navigation, checkboxes, language selection, and native Bedrock presentation use the same code as normal admission. Continue ends the preview with a disconnect message; it never grants access or saves acceptance. Leave and timeout also disconnect. The following connection returns to normal admission.

Supply an exact locale, such as `consentgate preview <uuid> id-ID`, to bypass the selector and preview that translation. Every required document must contain it. Omit the locale to follow the normal language settings. Use `consentgate preview <uuid> cancel` to remove an unused request. Cancellation does not interrupt an active preview. Requests are local to this proxy/server, expire after five minutes, and clear on successful reload or shutdown. At most 128 can be queued. Connected players cannot have a new preview queued.

Use `consentgate document` to list IDs, current versions, required status, and available translations. For example, `consentgate document community-rules en-US 2` reads page two. Pages start at one. Without an explicit locale, the normal configured fallback applies and the response identifies the translation used. Unknown explicit locales and invalid pages are rejected. Formatting becomes readable plain text in chat or console.

Document viewing reads the active configuration, so edited files become visible only after a successful reload. It does not query storage. Neither viewing nor preview changes acceptance history, including for players who already accepted. A preview request is marked as such in the shared admission model, and the storage entry point rejects it even after all boxes are checked.

## Validate and reload

Edit local documents, translations, message files, appearance, language selection, Bedrock presentation, or the timeout, then run `consentgate validate`. If it passes, run `consentgate reload` to apply the files as they exist at that moment. Reload validates again; it does not rely on an earlier validation result.

After a disabled startup, set `enabled: true` and run `consentgate reload` to enable the gate without restarting. Add at least one required document first. `consentgate validate` also works while disabled, but does not open storage. A failed reload keeps the previous state. Disabling an active gate, changing its scope or storage/cache settings, and changing `gate.max-pending` still require a restart. Fix startup errors in the files and restart.

Both platforms accept the native Bedrock option and use it when local Geyser identifies a Bedrock connection. Status bypasses the remote admission cache and requires a primary database response. A reset on another instance can remain hidden from admission checks until their cache freshness expires; see [remote storage](15-remote-storage.md).

Both commands check configuration and document structure, formatting, language coverage, and required interface messages. On an enabled gate they also check active and saved document revisions. Changing an existing translation, including titles, labels, or formatting, requires a version bump even if nobody has accepted that active revision yet. While disabled, validation checks the files only; storage is checked when reload enables the gate. Validation does not create tables or change acceptance records.

Reload refuses while consent sessions, resets, or other database jobs are pending. Retry after they finish. While reload is being processed, new connections are briefly rejected with the busy message. Validation can run alongside consent sessions and does not block new connections beyond normal database queue limits.

Any validation failure leaves the running configuration intact. Successful reload does not kick existing players, reset their records, or interrupt backend switching. New connections use the updated documents and messages. A previously accepted player will need to accept changed required versions on their next connection.
