# Storage and database upgrades

Status: SQLite and remote SQL/cache support are implemented for `0.1.0`. MariaDB 11.8.6 and MySQL 8.4.8 passed repository, TLS, and admission/outage checks on both platforms. See [setup and exact behavior](15-remote-storage.md) and [release progress](16-release-progress.md).

## Database choices

| Choice | Use | Tradeoff |
| --- | --- | --- |
| SQLite | Default local store and remote cache file | Simple installation; one process owns each file. |
| MySQL / MariaDB | Shared primary store for multiple proxies or servers | Needs an empty database and credentials; ConsentGate creates its tables automatically. |
| H2 | Deferred optional backend | Pure Java embedded storage, but another SQL dialect and migration path to maintain. |

SQLite supports this local application use, but sharing its file across machines is unsuitable. H2 is a valid embedded alternative; supporting both initially adds maintenance without a clear user benefit. MariaDB Connector/J is bundled for both MariaDB and MySQL. [SQLite guidance](https://www.sqlite.org/whentouse.html), [H2 overview](https://h2database.com/html/main.html), [MariaDB Connector/J](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)

Use prepared statements, bounded background database work, and transactions. For new acceptance, commit the acceptance records and their audit event together before admitting the player. Repeated clicks and retries must be idempotent.

## Document-based schema

Both implementations use independent documents and versions. Remote SQL types and indexes are defined in [mysql-v1.sql](../core/src/main/resources/db/mysql-v1.sql); SQLite keeps its existing version-1 schema. Remote and SQLite schema versions are independent.

| Table | Purpose |
| --- | --- |
| `cg_schema_history` | Applied database migrations |
| `cg_document_revisions` | Scope, document ID, version, locale, content hash, and text snapshot |
| `cg_acceptance_events` | Grant/withdrawal events, player UUID, revision, timestamp, method, request ID |
| `cg_acceptance_state` | Current decision per scope, player, and document; updated transactionally with events |
| `cg_audit_events` | Administrative changes and acceptance actions, linked by request ID |
| `cg_player_locks` | Remote-only serialization of decision writes for each UUID and scope |

Consent records use player UUIDs and UTC timestamps. They do not store player names or IP addresses. An admin reset invalidates acceptance, while deleting records is a distinct operation.

Multiple installations share acceptance when they use the same database, scope, player UUIDs, and matching document IDs, versions, and text. Separate scopes keep consent separate, even within one database. The database does not synchronize document files. See [scope examples](../README.md#shared-or-separate-consent).

## Remote cache behavior

The primary remote database remains authoritative. A separate local SQLite file stores only confirmed positive checks. It is enabled by default in remote mode, with these rules:

| Situation | Behavior |
| --- | --- |
| Matching positive record verified within 60 seconds | Allow from cache, including during a brief outage |
| Missing or expired entry | Query the primary database |
| Primary unavailable and no fresh entry | Keep blocked, then show retry/disconnect guidance |
| New acceptance | Require a primary database commit before allowing entry |
| Primary saved but cache invalidation failed | Allow based on the confirmed commit, disable caching until restart, and warn |
| Local cache corrupt | Discard its authority and use the primary; never infer acceptance |

Freshness starts before the successful primary query, not the last player join. Cache hits do not renew it. A 30-day cleanup policy and entry limit control retained records, not permission to trust old data. Cleanup happens on cache writes. Mismatched revisions cannot use a cached check. Grants invalidate cache entries but do not seed new ones without a separate primary check.

The 60-second default permits up to 60 seconds of stale acceptance after a withdrawal elsewhere. Administrators requiring a primary check on every admission can set freshness to zero. There is no immediate cross-proxy revocation guarantee with a local cache alone.

For the local process, invalidate affected entries before attempting a grant or withdrawal/reset, including uncertain outcomes. Expired entries cannot be revived during outages. Detect invalid timestamps or backward clock movement and revalidate. Do not cache a new acceptance before its commit is known to have succeeded.

Defer offline write queues: they would allow admission without a primary commit and introduce conflict handling after recovery.

## Database upgrades and portability

ConsentGate creates remote version-1 tables automatically in an empty dedicated database and checks existing tables before use. No SQL import is needed. Existing tables are not overwritten or upgraded; incomplete or unsupported schemas block startup. A database outage also blocks startup, even when a cache file exists.

Initial setup needs `CREATE`, `REFERENCES`, `SELECT`, `INSERT`, and `UPDATE` on the dedicated database. After setup, only `SELECT`, `INSERT`, and `UPDATE` are needed. Use a plugin-specific account, not a database administrator account. There are no automatic remote schema upgrades yet.

Before upgrading, document backups and whether rollback is supported. Account for database-specific DDL behavior; do not assume failed schema changes roll back identically on every provider. Test upgrades from every supported previous schema version.

A future export/import tool can support moves between providers. Preserve document revisions, event order, UUIDs, timestamps, and provenance, with a dry run and repeatable imports. This is outside the initial release.

Imports from other products and compatibility with their schemas are outside the initial scope. New installations can request fresh acceptance. Independent document versions are available from the start.

## Backups and retention

Follow [backup and recovery procedures](17-backups-and-recovery.md) for consistent SQLite copies, database-native remote backups, isolated restores, and failed schema installation. Cache files are disposable and are not acceptance backups.

Define retention separately for acceptance history, audit events, and cached decisions. Preserve referenced document snapshots while their acceptance records are retained. Erasure must invalidate current decisions and prevent records from returning through stale caches. Define multi-instance invalidation and its freshness limits before exposing an erasure command.
