# Remote storage implementation

Status: repository tests pass on MariaDB 11.8.6 and MySQL 8.4.8. MySQL admission and outage checks pass on both platforms. SQLite remains the default. Broader MariaDB runtime and load tests remain open. Setup is documented in [the remote storage guide](15-remote-storage.md).

## This slice

1. Add strict remote connection and cache settings without breaking existing SQLite configuration.
2. Bundle MariaDB Connector/J for both database products. Use a single primary endpoint, verified TLS by default, bounded connection/socket waits, and no automatic transaction replay.
3. Add a separate versioned InnoDB schema and transactional repository. Keep schema installation separate from ordinary runtime credentials. Serialize decisions for each player and scope, validate immutable revisions, and reject reused request IDs with different data.
4. Cache only confirmed positive checks in a separate local SQLite file. Default freshness is 60 seconds, never refreshed by a cache hit. Missing, expired, corrupt, or invalidated entries cannot grant admission during an outage. New grants always require a confirmed primary commit.
5. Test repository transactions, replay, concurrent instances, cache expiry, reset, clock changes, failures, and recovery. Use only the dedicated test database and client-side fault injection; never stop a shared database for an outage test.
6. Update configuration, installation, dependency notices, and the tested matrix. Keep platform deployment and MySQL verification as separate checks.

## Safety decisions

- Remote schema versioning is independent of SQLite. No automatic data import or destructive upgrades.
- Runtime startup requires a reachable primary and a valid schema even when a cache file exists.
- Administrator status reads the primary, not cached admission decisions.
- All storage and cache setting changes require restart.
- A cache may delay visibility of a reset on another instance by its freshness window. Set freshness to zero to check the primary every time.
- Cache failure must not undo a confirmed primary commit or hide a primary failure. Failed local invalidation disables cache use for the process.
- Remote decisions are serialized per UUID/scope, then ordered by UTC decision timestamp. Withdrawal wins a tie. A reset records a withdrawal even without prior acceptance. Old/replayed grants cannot bypass a current withdrawal. Host clocks must remain synchronized; this is not a global logical clock.

## Test evidence

Date: 2026-09-12. Dedicated MariaDB 11.8.6 database, accessed through a loopback SSH tunnel with a database-limited test account. No shared database restart, firewall change, plugin deployment, or existing player-data mutation. Synthetic records use unique test scopes and remain in the dedicated database. The schema-version test temporarily changes and restores only that database's version marker.

Repository checks cover atomic multi-document grants, shared acceptance, exact request replay, conflicting request data, immutable revisions, rollback, absent-state withdrawal, stale grants, equal timestamps, concurrent instances, cache expiry during an outage, recovery, and a lost commit acknowledgement. The normal local suite checks corrupt cache files, persistence, original verification times, capacity, failed invalidation, local reset races, clock changes, strict configuration, and shaded-driver isolation.

Outages and lost replies are injected on the client connection boundary. They do not prove every real network interruption or TLS setup. The primary service remained online throughout. Cache tests use disposable local SQLite files, not the server's production data.

| Final check | Result |
| --- | --- |
| Local JVM suite | 112 passed, no failures or skips |
| Dedicated MariaDB suite | 10 passed, including real connections from both isolated platform JARs |
| Local Velocity protocol 772 wire suite | All 14 checks passed using SQLite; this is a packaging regression check, not remote admission proof |
| Python framing and Node probe helpers | 4 and 7 passed |
| Packaging and documentation | Both JARs built; schema and LGPL notices included; relative document links and whitespace checks passed |

Review added a clean-shutdown marker to the cache lock file. A crash or failed invalidation discards persisted positives on the next start. A clock-regression test caught an initial issue in the monotonic-time safeguard; it was corrected and both the local and MariaDB suites passed again. No graphical client was opened. The temporary tunnel and loopback proxy were stopped after testing.

Tested artifact SHA-256 values: Paper `d3c6187342be8e3b26be4786325d5b1394ba811b1a136319e4e31fee6798ef0f`; Velocity `d27ed0d92fa2db9665ac8df917cd6f22399be8f572a7f571928aa85a6dda1b48`.

## Still open

- Measure sustained connection load and decide whether a small pool is justified.
- Broader review of multi-instance reset timing and clock-skew handling before release.
- Prepare corresponding dependency source materials before any binary publication.

References: [MariaDB Connector/J](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j), [InnoDB locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html).

## MySQL verification, 2026-09-13

MySQL Community Server 8.4.8 passed all 15 repository tests, with no failures or skips. The official Windows ZIP was extracted into the ignored local workspace, and its server executable had a valid Authenticode signature. The test process used a new data directory, a loopback listener, generated credentials, and a generated test CA. It stopped after the suite; no Windows service or desktop application was installed or launched.

The ordinary connection used `verify-full` TLS and MySQL's default account authentication. Both shaded plugin drivers connected successfully. Tests confirmed negotiated encryption, rejection of an unrelated CA, and rejection of a hostname mismatch while the same endpoint worked with CA-only verification.

A test-only plaintext TCP proxy cut one connection before COMMIT and dropped another connection's successful COMMIT reply. The first operation left no grant or audit record. The second raised a storage failure while leaving one durable grant; replay did not duplicate it. These were actual socket interruptions on disposable loopback connections. They do not prove every network failure or live platform admission path.

## MySQL platform checks, 2026-09-13

The packaged Velocity and Paper plugins passed headless admission through a local TLS relay to MySQL 8.4.8. Both passed new acceptance, accepted rejoin, authoritative status, offline reset with preserved history, and acceptance after reset. Database inspection verified the grant and withdrawal events in unique test scopes.

With only the relay disabled, fresh cached acceptance still admitted the known player. Unknown players were denied, administrator status failed instead of reporting cached data, and the known player was denied after cache expiry. Restoring the relay allowed rejoin. Disconnecting the relay after showing a new agreement prevented a failed save from admitting that player; restoring it allowed a new confirmed grant.

The first relay fixture used a one-second connection timeout and failed during Velocity startup. The final fixture first verifies a complete TLS query through the relay and uses the plugin's default three-second connection and five-second socket timeouts. Both final platform runs passed. The proxy, server, database, and relays stopped afterward, and original ConsentGate configurations were restored. No graphical client or external deployment was used.

## Expanded MariaDB verification, 2026-09-13

MariaDB 11.8.6 passed the same 15 repository checks with no failures or skips, including verified TLS, unrelated-CA and hostname rejection, both isolated drivers, and actual socket interruptions before commit and before its acknowledgement. Its official Windows ZIP matched the archive's published SHA-256 checksum. The runner initialized a fresh loopback database without installing a service.

Both packaged platforms also passed the full admission and TLS-relay outage sequence described above for MySQL: acceptance, rejoin, status, reset history, fresh-cache admission, unknown and expired-cache denial, failed-save denial, and recovery. The proxy, server, database, and relays stopped afterward; original plugin configurations were restored. Sustained load and wider multi-instance timing remain open.
