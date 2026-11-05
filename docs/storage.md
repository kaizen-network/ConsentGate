---
title: Storage
description: Where acceptance is saved, what is recorded, choosing between SQLite and MySQL/MariaDB, and the anonymous usage statistics sent to bStats.
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

## Usage statistics (bStats)

ConsentGate sends anonymous usage statistics to [bStats](https://bstats.org), a service many plugins use. They show how many servers run ConsentGate and which platform versions matter. The combined charts are public: [Paper](https://bstats.org/plugin/bukkit/ConsentGate/35143) and [Velocity](https://bstats.org/plugin/velocity/ConsentGate/35145).

The statistics describe the server, not its players. Each report contains:

- the number of players online
- whether the server or proxy uses online mode
- the server software name and version
- the Java version, operating system name, version, and architecture, and the number of CPU cores
- the ConsentGate version
- a random server ID that bStats creates and stores in `plugins/bStats`, shared by all plugins that use bStats
- on Velocity, also the number of servers in the proxy config

ConsentGate adds nothing else. It never sends player names, UUIDs, IP addresses, documents, or consent records.

The first report is sent over HTTPS a few minutes after startup, then one every 30 minutes. bStats also sees the server's IP address, as with any web request. Its [privacy policy](https://bstats.org/privacy-policy) says the address is only used for rate limiting and abuse prevention, and is kept for at most 60 minutes. See also bStats' [page for server owners](https://bstats.org/docs/server-owners).

### Turning it off

bStats has one setting for all plugins on a server or proxy. Change it, then restart.

| Platform | File | Setting |
| --- | --- | --- |
| Paper | `plugins/bStats/config.yml` | `enabled: false` |
| Velocity | `plugins/bStats/config.txt` | `enabled=false` |

On Velocity, change only the `enabled` line and keep the `server-uuid` line. If that line is missing, bStats replaces the file with a new one where `enabled=true`. If you create `config.txt` yourself before the first start, include a `server-uuid=` line with any random UUID.

## Schema versions

SQLite and MySQL/MariaDB each have their own schema version. ConsentGate creates tables in an empty database and checks existing tables before use. It never overwrites or upgrades existing tables on its own. An unknown newer schema or an incomplete one stops startup; see [backups and recovery](backups.md#failed-installation-or-upgrade).

## Backups

See [backups and recovery](backups.md).
