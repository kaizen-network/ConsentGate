---
title: MySQL and MariaDB
description: Set up MySQL or MariaDB, TLS, timeouts, and the local cache for sharing consent across servers.
order: 8
---

# MySQL and MariaDB

Use MySQL or MariaDB to share consent across several proxies or servers. For a single server, SQLite is simpler. Tested versions are listed in [compatibility](compatibility.md).

## Set up the database

1. Back up the plugin folder and any existing databases. Stop the server or proxy.
2. Create an empty database just for ConsentGate, using `utf8mb4`.
3. Create a database user with `CREATE`, `REFERENCES`, `SELECT`, `INSERT`, and `UPDATE` on that database only, and limit the hosts it can connect from. No global permissions or `GRANT OPTION` are needed.
4. Fill in the `storage` section of `config.yml`, set `type` to `mariadb` or `mysql`, and start. ConsentGate creates its tables. No SQL import is needed.
5. Check the log, run `consentgate validate`, and test a fresh acceptance and rejoin.

After setup, the account only needs `SELECT`, `INSERT`, and `UPDATE`. If you prefer to create the tables yourself, the schema is in [`mysql-v1.sql`](https://github.com/kaizen-network/ConsentGate/blob/main/core/src/main/resources/db/mysql-v1.sql).

If the first setup stops partway, table creation cannot be undone as a whole. Keep that database for inspection and point the plugin at a new empty one to retry.

## Configuration

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

- Keep the `sqlite` section. It is not used as storage in remote mode.
- The password is used as written. Environment variables are not expanded. Keep the config and its backups private.
- If the `cache` section is left out, it defaults to on, 60 seconds, and 100,000 entries. The cache file must be different from the SQLite file.
- Changing storage settings on an active gate needs a restart. On a disabled gate, you can fill them in and enable it with `consentgate reload`.

## TLS

| `ssl-mode` | Behavior |
| --- | --- |
| `verify-full` (default) | Checks the certificate and the hostname. Use a hostname that matches the certificate. |
| `verify-ca` | Checks the certificate, but not the hostname |
| `disable` | No TLS. Only for connections already protected another way, such as an SSH tunnel on the same machine. |

`server-certificate` can point to a PEM file inside the plugin folder, like `certificates/database.pem`. Leave it empty to use the normal Java trust store. Never expose an unencrypted database connection to the internet. See the [driver TLS guide](https://mariadb.com/docs/connectors/mariadb-connector-j/using-tls-ssl-with-mariadb-java-connector).

## Connections

- One database address only. Replicas, failover, Unix sockets, and Windows named pipes are not supported.
- Each operation opens its own connection. Two database workers per server, with a limited queue, keep load bounded.
- Connect and socket timeouts default to 3 and 5 seconds (100 to 30,000 milliseconds allowed).
- A lost connection or lost commit reply denies that join. A later check finds the commit if it did go through. Retried requests never create duplicate records.

Keep server clocks in sync. When two servers record decisions for the same player, the newer timestamp wins, and a withdrawal wins a tie.

## Cache

The cache is a small local SQLite file that remembers recent successful checks, so players can still join during a short database outage.

| Situation | Result |
| --- | --- |
| Player passed a database check within the freshness time | Can join from the cache, even if the database is briefly down |
| No cache entry, or it is too old | Check the database. If the database is down, the player waits and is then disconnected. |
| New acceptance or reset | Always goes to the database. Nothing is queued offline. |
| `status` command | Always reads the database |
| Cache file broken or failing | Cache is turned off until restart, with a warning. The database is still used. |
| Startup | Needs the database to be reachable. The cache cannot replace it. |

Using the cache never extends its freshness. Only successful checks are cached.

With the default 60 seconds, a reset on one server can take up to 60 seconds to reach the others. Set `freshness-seconds: 0` or `enabled: false` to check the database on every join. The maximum is 300 seconds.

Each server needs its own cache file. Entries older than 30 days are cleaned up over time. A crash or cache error clears the old entries on the next start. The cache is not a backup.

When you restore an older database backup, give every server a new empty cache file. See [backups](backups.md#mysql-and-mariadb).
