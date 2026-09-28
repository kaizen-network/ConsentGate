---
title: Commands and permissions
description: Admin commands for status, reset, preview, document viewing, validation, and reload.
order: 6
---

# Commands and permissions

Run these in the console without the leading `/`, or in game with `/` and the right permission. On Paper, the permissions default to operators.

| Command | Permission | What it does |
| --- | --- | --- |
| `/consentgate status <online-player\|uuid>` | `consentgate.admin.status` | Shows whether each current required document has a valid acceptance |
| `/consentgate reset <uuid>` | `consentgate.admin.reset` | Withdraws acceptance for the current required documents in this scope |
| `/consentgate validate` | `consentgate.admin.validate` | Checks edited files without applying them |
| `/consentgate reload` | `consentgate.admin.reload` | Checks and applies supported changes for new connections |
| `/consentgate preview <uuid> [locale\|cancel]` | `consentgate.admin.preview` | Queues one login preview for a player, or cancels it. Never saves consent. |
| `/consentgate document [id] [locale] [page]` | `consentgate.admin.document` | Lists active documents, or shows one page in chat or the console |

`validate` and `reload` also work while the gate is disabled. The other commands need an enabled gate, and `status` and `reset` also need working storage. Each permission covers only its own command. Admin replies are in English.

## Reset a player

1. While the player is online, run `status` to find their UUID.
2. Have the player disconnect.
3. Run `consentgate reset <uuid>` and wait for the success message.
4. When they reconnect, they must accept again.

Reset refuses players who are connected, including players still on the consent screen. It keeps the full history and adds withdrawal records, so there is no need to delete the database to test again. Other scopes are not affected.

Use the full UUID the proxy or server knows the player by. Offline names are not looked up. Changing authentication or forwarding can change a player's UUID.

With MySQL/MariaDB and the cache on, a reset on one server can take up to the cache freshness time (60 seconds by default) to reach other servers. See [remote storage](remote-storage.md#cache).

## Preview

1. Have the test player disconnect.
2. Run `consentgate preview <uuid>`.
3. Connect within five minutes. The normal consent screen appears, even if the player already accepted.

Continue ends the preview with a disconnect and never saves acceptance. The next connection is normal again.

Add a locale, like `consentgate preview <uuid> id-ID`, to skip the selector and preview that translation. Use `consentgate preview <uuid> cancel` to remove an unused preview. Previews are local to one proxy or server, expire after five minutes, and are cleared by reload or shutdown. Up to 128 can be queued.

## View documents

`consentgate document` lists document IDs, current versions, required status, and translations. `consentgate document community-rules en-US 2` shows page two of that translation. Formatting is shown as plain text.

It reads the running configuration, so edits appear only after a successful reload.

## Validate and reload

Run `consentgate validate` after editing. If it passes, run `consentgate reload`. Reload checks everything again, including document versions against saved acceptance, so text changed under an old version is rejected.

- Reload refuses while players are on the consent screen or database work is running. New connections are briefly rejected while a reload is applied.
- A failed reload keeps the previous settings.
- Reload can enable a disabled gate. Add at least one required document first.
- Disabling an active gate, changing its scope or storage/cache settings, and changing `gate.max-pending` need a restart.
- Reload never kicks online players or changes their records.
