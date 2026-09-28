---
title: Storage
description: Where acceptance is saved, what is recorded, and choosing between SQLite and MySQL/MariaDB.
order: 7
---

# Storage

## Choosing a database

| Choice | Use it for | Notes |
| --- | --- | --- |
| SQLite (default) | One proxy or server | Built in, no setup. One process owns each file; do not share it between machines. |
| MySQL / MariaDB | Sharing consent across several proxies or servers | Needs an empty database and an account. Tables are created automatically. See [remote storage](remote-storage.md). |

Both JARs bundle SQLite and MariaDB Connector/J (which also works with MySQL), so no driver plugin is needed.

Moving from SQLite to MySQL/MariaDB does not copy existing records. Players accept again. Keep the old SQLite file for your records.

## What is recorded

| Table | Contents |
| --- | --- |
| `cg_schema_history` | Applied database versions |
| `cg_document_revisions` | Scope, document ID, version, language, content hash, and a snapshot of the text |
| `cg_acceptance_events` | Each grant and withdrawal: player UUID, document revision, time, method, request ID |
| `cg_acceptance_state` | The current decision per scope, player, and document |
| `cg_audit_events` | Admin actions and acceptance actions |
| `cg_player_locks` | MySQL/MariaDB only: keeps writes for the same player in order across servers |

Records use player UUIDs and UTC timestamps. Player names and IP addresses are not stored. Each acceptance points to a snapshot of the exact text the player accepted.

A reset adds withdrawal records and keeps the history. There is no command to delete history. Keep the database and its backups private, since they link player UUIDs to their decisions.

## Schema versions

SQLite and MySQL/MariaDB each have their own schema version. ConsentGate creates tables in an empty database and checks existing tables before use. It never overwrites or upgrades existing tables on its own. An unknown newer schema or an incomplete one stops startup; see [backups and recovery](backups.md#failed-installation-or-upgrade).

## Backups

See [backups and recovery](backups.md).
