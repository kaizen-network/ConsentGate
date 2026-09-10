# Administrator commands

These commands are implemented on Velocity. ConsentGate must be enabled and storage must be available. Paper commands and live configuration reload are not implemented yet.

| Command | Permission | Result |
| --- | --- | --- |
| `/consentgate status <online-player\|uuid>` | `consentgate.admin.status` | Shows whether each current required document has a valid acceptance in any configured translation |
| `/consentgate reset <uuid>` | `consentgate.admin.reset` | Withdraws acceptance for the current required documents in the configured scope |

Run these in the proxy console without the leading slash, or grant the relevant permission through your permissions plugin. Status permission does not grant reset permission.

## Reset for testing or administration

1. Use `status` while the player is online to find their UUID.
2. Have the player disconnect.
3. Run `consentgate reset <uuid>` and wait for the success message.
4. Reconnect. The player must accept again.

Reset refuses connected players, including players still reviewing documents. Connections for the target UUID are rejected while reset is pending. An earlier save finishes before reset writes its withdrawal. Reset does not kick players or present dialogs inside a backend server.

No database deletion is needed. Existing acceptance events and document snapshots remain intact; reset adds withdrawal records with the `admin-reset` method. Other scopes and documents outside the current required set are unchanged. A UUID with no previous acceptance is already required to accept; resetting it records the request without inventing an acceptance.

Use the full UUID known to the proxy or stored in the consent database for offline players. Offline names are not looked up or converted to guessed UUIDs. Authentication and forwarding changes can change player identity.

Commands use the bounded database worker queue. If storage fails or the queue is full, the command reports an error. Check status before retrying an uncertain result. Administrator responses are currently English.
