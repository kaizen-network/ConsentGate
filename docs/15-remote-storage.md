# MySQL, MariaDB, and the local cache

Prototype feature. MariaDB 11.8.6 and MySQL 8.4.8 each passed dedicated repository, verified TLS, socket-failure, live platform admission, initial sustained-load, and dump/restore checks. Neither is a production support claim. Deployment-specific certificate and capacity checks remain necessary.

## Set up the database

1. Back up the plugin folder and any existing databases. Stop the test proxy/server before changing storage settings.
2. Create an empty, dedicated database with `utf8mb4` character support. Do not reuse another plugin's tables or copy the SQLite schema into it.
3. With a setup account, select that database and run [mysql-v1.sql](../core/src/main/resources/db/mysql-v1.sql). The same file is bundled inside each platform JAR at `db/mysql-v1.sql`.
4. Create a runtime account limited to this database, with `SELECT`, `INSERT`, and `UPDATE`. No global permissions, `GRANT OPTION`, `CREATE`, or `DROP` are needed at runtime. Restrict its allowed client hosts. Integration-test accounts need additional schema permissions, but production runtime accounts should not.
5. Configure the remote section below, choose `mariadb` or `mysql`, and start. Check successful initialization and run `consentgate validate` and a fresh acceptance/rejoin test.

The plugin validates schema version 1 and requires its tables to use InnoDB. It does not run remote schema changes automatically. An empty, incomplete, or newer schema blocks startup. MariaDB and MySQL DDL is not one rollback-safe transaction: if initial installation fails, inspect and repair the dedicated schema with the setup account before retrying. Do not run the script over an existing installation or drop an existing database to hide an upgrade error.

Moving from SQLite does not import existing records. Players must accept again unless a future, separately verified migration tool transfers the history. Preserve the original SQLite database for audit and rollback. Do not turn a cache file into the primary database.

## Configuration

Replace only the `storage` section in the existing `config.yml`. Keep the rest of the generated configuration, and replace the example credentials before use:

```yaml
storage:
  type: mariadb
  sqlite:
    file: data/consent.db
  remote:
    host: database.example.net
    port: 3306
    database: consentgate
    username: consentgate
    password: "replace-this-password"
    ssl-mode: verify-full
    server-certificate: ""
    connect-timeout-millis: 3000
    socket-timeout-millis: 5000
  cache:
    enabled: true
    file: data/remote-cache.db
    freshness-seconds: 60
    max-entries: 100000
```

The `sqlite` path is retained for compatibility and is not opened as primary storage in remote mode. The cache must use a different file. Cache defaults apply when its entire section is omitted: enabled for remote storage, 60-second freshness, and 100,000 entries. Existing SQLite-only configurations still work.

All storage settings require restart, including credentials, endpoint, TLS, and cache settings. Reload refuses those changes and keeps the running settings. Configuration objects redact remote connection details in their string representation. Keep the configuration and backups private; passwords are stored in the administrator's local configuration, not in acceptance records or the cache.

## Connections and TLS

Both platforms bundle MariaDB Connector/J 3.5.10, relocated to avoid conflicts with other plugins. No separate JDBC plugin is required. This integration supports one primary TCP endpoint, not read replicas, automatic failover, Unix sockets, or Windows named pipes.

`verify-full` is the default and verifies the server certificate and hostname. Use a hostname matching the certificate. `server-certificate` can point to a PEM certificate file relative to the plugin directory, for example `certificates/database.pem`; an empty value uses the driver's normal trust configuration. `verify-ca` verifies the certificate without hostname verification. `disable` turns TLS off and is only appropriate when another trusted channel protects the connection, such as a loopback SSH tunnel. Do not expose an unencrypted public database connection. Follow the [driver TLS guidance](https://mariadb.com/docs/connectors/mariadb-connector-j/using-tls-ssl-with-mariadb-java-connector).

Connections and sockets default to 3-second and 5-second timeouts. Each can be set from 100 to 30,000 milliseconds; zero is not allowed. Each operation opens and closes its own connection. The platform's two database workers and bounded queue limit concurrent work. Remote sessions use a 5-second InnoDB lock wait. These are individual connection, socket, and lock limits, not a single deadline for a whole multi-statement operation. Admission timeouts still stop waiting players from entering.

Local file loading, multiple statements per request, and automatic transaction replay are disabled in the driver. A connection failure or lost commit reply denies the current admission. A later primary check can discover a commit that did succeed. Reusing the same request ID with identical data does not create duplicate events; changing its data is rejected.

## Decisions and concurrent instances

The schema stores immutable document snapshots, acceptance/withdrawal events, current state, and audit records. Decision writes are transactional. A database row lock serializes writes for the same player UUID and scope across plugin instances. Document IDs are processed in a fixed order to reduce deadlocks; a failed transaction is reported rather than retried automatically.

Current state uses the supplied UTC decision timestamp. Newer timestamps win, and withdrawal wins an equal-timestamp tie. Keep proxy/server clocks synchronized. There is no global ordering guarantee for badly skewed host clocks. A reset records a withdrawal even when the player has no prior state, preventing an older delayed grant from reviving it. Replayed or stale grants cannot admit a player whose current required decisions remain withdrawn. Already-online players are not kicked by a reset on another instance.

A reset verifies that every requested decision is withdrawn before returning success. An older or replayed reset that cannot replace a newer grant returns an error and rolls back its new records. Correct the host clocks, inspect authoritative status, and retry as a new reset. Success never means only that an audit row was written.

Revision keys are byte-sensitive, including case and trailing spaces. Reusing a scope, document ID, version, and locale with different text fails the whole grant transaction. Use matching scopes and document revisions only when installations should share acceptance.

## Cache rules

| Situation | Result |
| --- | --- |
| Complete positive primary check younger than the freshness limit | Admission may use the cache, including during a brief outage |
| Missing, expired, or different revision | Check the primary; a primary failure denies admission |
| New acceptance or reset | Always contact the primary; never queue offline writes |
| Primary status command | Bypass the cache and report the primary result |
| Cache unavailable, corrupt, locked, or failing writes | Disable cache use until restart, warn once, and keep using the primary |
| Plugin startup | Require a reachable primary and valid schema; a cache cannot bypass initialization |

Cache hits never extend freshness. The timestamp starts before the successful primary query, so a slow response does not gain a new full freshness window. Only positive checks are stored. A known negative primary check invalidates the player's cached checks for that scope. Grant/reset attempts invalidate locally before the primary write, even if its outcome becomes unknown. Per-player locking prevents a local reset from racing an older cache fill.

Normal admission and administrator status read all required document decisions together. Any current translation can satisfy its document, so changing client language does not require another database read while the complete check remains fresh. Explicit language selection still checks the selected translations. Partial matches are never cached as complete acceptance.

The batch-read update uses new cache keys and ignores entries from earlier candidates. Each player needs a successful primary check to populate the new entries. Acceptance records and history are unchanged.

With the default 60 seconds, another instance's reset can take up to 60 seconds to become visible through this cache. Set `freshness-seconds: 0` or `enabled: false` for a primary query on every admission. The maximum configurable freshness is 300 seconds. No immediate cross-instance invalidation is promised.

The file preserves original verification times across clean restarts. An unclean shutdown or cache failure leaves a marker that discards old entries on the next start. Cache entries are tied to the primary endpoint/database, UUID, scope, and shown revisions. Future timestamps and observed backward wall-clock movement are not trusted. Elapsed monotonic time also bounds freshness within a running process. Keep system clocks correct, including while the plugin is stopped.

One process owns each cache file. Do not share it between servers. Oldest entries are evicted at the configured capacity. Records older than 30 days are cleaned up on successful cache writes; this retention is not permission to trust old decisions. Cleanup is opportunistic, not a scheduled deletion guarantee. A cache is disposable and is never an acceptance-history backup.

## Integration tests

The normal `build` runs local tests only. Real database tests require explicit opt-in and a database whose name starts with `consentgate_test_`. The task initializes the supplied schema only when that database has no tables. It never drops the database. Tests retain synthetic records under unique scopes and temporarily change and restore the test schema version to check rejection.

Set these environment variables privately: `CG_TEST_DB_HOST`, `CG_TEST_DB_PORT`, `CG_TEST_DB_DATABASE`, `CG_TEST_DB_USERNAME`, `CG_TEST_DB_PASSWORD`, and `CG_TEST_DB_ALLOW_WRITES=true`. `CG_TEST_DB_SSL_MODE` defaults to `verify-full`; set `CG_TEST_DB_SERVER_CERTIFICATE` to a local CA file when needed. Use `disable` only for a protected test connection. Then run:

```powershell
.\gradlew.bat :core:remoteDatabaseTest --no-daemon --console=plain
```

The suite covers atomic grants, shared acceptance, replay conflicts, immutable revisions, initially missing withdrawal state, concurrent instances, lost commit replies, fresh-cache outages, expiry, recovery, and newer-schema rejection. Never stop a shared database service or change its firewall for these tests.

Additional TLS checks use `CG_TEST_DB_UNTRUSTED_CERTIFICATE` (a valid unrelated CA) and `CG_TEST_DB_WRONG_HOST` (an alias reaching the same server but absent from its certificate). `CG_TEST_DB_SOCKET_FAULTS=true` enables a loopback-only plaintext protocol proxy that cuts its own connection before COMMIT or drops the server's successful COMMIT reply. These targeted socket checks leave the database service running; ordinary repository checks still use the configured TLS connection.

On Windows, `python tools/run_local_mysql_tests.py --server C:/path/to/extracted/mysql` prepares a disposable database with generated credentials and test certificates, runs all 17 repository checks including 30 seconds of load, verifies a dump restored into another empty database, then stops its process. It requires Python's `cryptography` package and an already extracted official MySQL ZIP distribution, including `mysql.exe`, `mysqladmin.exe`, and `mysqldump.exe`. It does not download software, install a service, or launch a desktop app. Test data, certificates, credentials, results, dumps, and logs remain in the ignored `.run/` fixture for inspection.

For MariaDB, use `--engine mariadb --server C:/path/to/extracted/mariadb --client C:/path/to/mysql/bin/mysql.exe`. The common inspection helper uses the MySQL client for explicit TLS settings with either server. The runner initializes new MariaDB data without registering a Windows service.

Add `--platforms velocity paper --modules C:/path/to/node_modules` to exercise both prepared headless platform fixtures against that database. The [development guide](05-development.md) describes the required local servers and Node dependencies. These checks temporarily replace only their ConsentGate configuration and JAR, verify acceptance and reset history, interrupt a dedicated TLS relay to test cache expiry and failed writes, then restore the original configuration. The primary database stays running until its owning test runner shuts it down.

Run the suite separately against each supported MariaDB and MySQL version before claiming support. Repository tests and isolated driver loading do not replace live client admission, certificate setup, network fault, or load testing.
