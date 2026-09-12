# Administrator commands

These commands are implemented on Velocity and Paper. ConsentGate must be enabled and storage must be available. Velocity commands have wire and console testing. Paper shares the tested command rules, but its command delivery and admission coordination still need live integration testing.

| Command | Permission | Result |
| --- | --- | --- |
| `/consentgate status <online-player\|uuid>` | `consentgate.admin.status` | Shows whether each current required document has a valid acceptance in any configured translation |
| `/consentgate reset <uuid>` | `consentgate.admin.reset` | Withdraws acceptance for the current required documents in the configured scope |
| `/consentgate validate` | `consentgate.admin.validate` | Checks whether edited files can safely replace the running configuration, without applying changes |
| `/consentgate reload` | `consentgate.admin.reload` | Validates and applies supported changes for new connections |

Run these in the proxy or server console without the leading slash, or grant the relevant permission through your permissions plugin. Paper defaults these permissions to operators. Status permission does not grant reset permission. Paper resolves online names and sends command replies on the main server thread; database work stays on the worker queue.

## Reset for testing or administration

1. Use `status` while the player is online to find their UUID.
2. Have the player disconnect.
3. Run `consentgate reset <uuid>` and wait for the success message.
4. Reconnect. The player must accept again.

Reset refuses connected players, including players still reviewing documents. Connections for the target UUID are rejected while reset is pending. An earlier save finishes before reset writes its withdrawal. Reset does not kick players or present dialogs inside a backend server.

No database deletion is needed. Existing acceptance events and document snapshots remain intact; reset adds withdrawal records with the `admin-reset` method. Other scopes and documents outside the current required set are unchanged. A UUID with no previous acceptance is already required to accept; resetting it records the request without inventing an acceptance.

Use the full UUID known to the proxy or server, or stored in the consent database, for offline players. Offline names are not looked up or converted to guessed UUIDs. Authentication and forwarding changes can change player identity.

Commands use the bounded database worker queue. If storage fails or the queue is full, the command reports an error. Check status before retrying an uncertain result. Administrator responses are currently English.

## Validate and reload

Edit local documents, translations, message files, appearance, language selection, Bedrock presentation, or the timeout, then run `consentgate validate`. If it passes, run `consentgate reload` to apply the files as they exist at that moment. Reload validates again; it does not rely on an earlier validation result.

Changing `enabled`, `scope`, the SQLite path, or `gate.max-pending` requires a restart. These commands require an already running, enabled gate; fix startup errors in the files and restart the proxy or server. They are not a standalone configuration checker for disabled installations. Paper rejects `bedrock.native-forms: true` during startup, validation, and reload until its native renderer is implemented.

Both commands check configuration and document structure, safe formatting, language coverage, required interface messages, and document revisions. Changing an existing document translation requires a version bump, even if nobody has accepted that active revision yet. Saved historical revisions are also checked. Validation does not create a new database, register document revisions, or change acceptance records. It checks the currently supported reload rules, not future storage or platform support.

Reload refuses while consent sessions, resets, or other database jobs are pending. Retry after they finish. While reload is being processed, new connections are briefly rejected with the busy message. Validation can run alongside consent sessions and does not block new connections beyond normal database queue limits.

Any validation failure leaves the running configuration intact. Successful reload does not kick existing players, reset their records, or interrupt backend switching. New connections use the updated documents and messages. A previously accepted player will need to accept changed required versions on their next connection.
