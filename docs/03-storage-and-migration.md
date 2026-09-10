# Storage and database upgrades

Status: storage choices are decided; schema details and operational defaults remain proposals.

## Database choices

| Choice | Proposed use | Tradeoff |
| --- | --- | --- |
| SQLite | Default local store and remote cache file | Simple installation; one process owns each file. |
| MySQL / MariaDB | Shared primary store for multiple proxies or servers | Needs credentials, migrations, and outage handling. Test both products. |
| H2 | Deferred optional backend | Pure Java embedded storage, but another SQL dialect and migration path to maintain. |

SQLite supports this local application use, but sharing its file across machines is unsuitable. H2 is a valid embedded alternative; supporting both initially adds maintenance without a clear user benefit. MariaDB Connector/J supports MariaDB and MySQL and is a candidate for the remote driver. [SQLite guidance](https://www.sqlite.org/whentouse.html), [H2 overview](https://h2database.com/html/main.html), [MariaDB Connector/J](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)

Use prepared statements, bounded background database work, and transactions. For new acceptance, commit the acceptance records and their audit event together before admitting the player. Repeated clicks and retries must be idempotent.

## Document-based schema

Design around independent documents and versions from the first release. Proposed tables follow, with SQL types and indexes finalized during implementation:

| Table | Purpose |
| --- | --- |
| `cg_schema_history` | Applied database migrations |
| `cg_document_revisions` | Scope, document ID, version, locale, content hash, and text snapshot |
| `cg_acceptance_events` | Grant/withdrawal events, player UUID, revision, timestamp, method, request ID |
| `cg_acceptance_state` | Current decision per scope, player, and document; updated transactionally with events |
| `cg_audit_events` | Administrative changes and acceptance actions, linked by request ID |

Use a canonical UUID representation, UTC timestamps, and database uniqueness constraints. Store IP addresses only when enabled. Do not require player names. An admin reset invalidates acceptance, while deleting records is a distinct operation.

Multiple installations share acceptance only when they use the same scope, document IDs, and versions. Detect conflicting content for the same revision. Keep event insertion and current-state changes consistent under concurrent requests from different proxies.

## Remote cache behavior

The primary remote database remains authoritative. A separate local SQLite file stores only confirmed decisions. It is enabled by default, with these proposed rules:

| Situation | Behavior |
| --- | --- |
| Matching positive record verified within 60 seconds | Allow from cache, including during a brief outage |
| Missing or expired entry | Query the primary database |
| Primary unavailable and no fresh entry | Keep blocked, then show retry/disconnect guidance |
| New acceptance | Require a primary database commit before allowing entry |
| Primary saved but cache write failed | Allow based on the confirmed commit, report degraded caching |
| Local cache corrupt | Discard its authority and use the primary; never infer acceptance |

Freshness is measured from the last successful primary verification, not the last player join. A 30-day cleanup policy controls file size, not permission to trust old data. Include a capacity limit and invalidate mismatched revisions immediately.

The 60-second default permits up to 60 seconds of stale acceptance after a withdrawal elsewhere. Administrators requiring a primary check on every admission can set freshness to zero. There is no immediate cross-proxy revocation guarantee with a local cache alone.

For the local process, invalidate affected entries immediately after a withdrawal/reset. Expired entries cannot be revived during outages. Detect invalid timestamps or backward clock movement and revalidate. Do not cache a new acceptance before its commit is known to have succeeded.

Defer offline write queues: they would allow admission without a primary commit and introduce conflict handling after recovery.

## Database upgrades and portability

Version the plugin's own schema with ordered migrations. Validate it at startup and refuse unsupported newer schemas. Keep pending players blocked if storage cannot initialize safely.

Provide documented SQL migrations for administrators using a separate setup account. Runtime credentials should only need permissions for normal plugin operations. Database administrator credentials do not belong in plugin configuration.

Before upgrading, document backups and whether rollback is supported. Account for database-specific DDL behavior; do not assume failed schema changes roll back identically on every provider. Test upgrades from every supported previous schema version.

A future export/import tool can support moves between providers. Preserve document revisions, event order, UUIDs, timestamps, and provenance, with a dry run and repeatable imports. This is outside the initial release.

Imports from other products and compatibility with their schemas are outside the initial scope. New installations can request fresh acceptance. Independent document versions are available from the start.

## Backups and retention

Document consistent SQLite backups and database-native backups for remote providers. Cache files are disposable and are not acceptance backups.

Define retention separately for acceptance history, audit events, and cached decisions. Preserve referenced document snapshots while their acceptance records are retained. Erasure must invalidate current decisions and prevent records from returning through stale caches. Define multi-instance invalidation and its freshness limits before exposing an erasure command.
