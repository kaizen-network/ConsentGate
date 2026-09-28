# Testing

## Quick checks

```powershell
.\gradlew.bat build
python tools/check_docs.py
python -m unittest discover -s tools -p "test_*.py"
node --test tools/test_paper_probe.cjs
```

`build` runs the JVM tests and never contacts a remote database. `check_docs.py` checks the pages the website renders: front matter (`title`, `description`, `order`) on every page outside `contributing/`, relative links, images, and `#anchors` that resolve, and the changelog heading format. CI runs all four; see [development](development.md#github-actions).

## What the automated tests cover

- **Core (`core:test`):** unchecked and foreign actions, timeout, repeated clicks, shutdown, config paths and limits, document loading and hashing, language selection, multiple documents, SQLite transactions, repeated requests, changed text under an unchanged version, and withdrawal order.
- **Cache:** expiry, persistence, reset, capacity, corrupt files, failed invalidation, clock changes, and stalled connection setup.
- **Paper admission:** commit before release, queued cancellation, full queues, locked SQLite, save and reset order after disconnect, shutdown, timeout, rendering failures, and native callback lock order.
- **Bedrock:** Cumulus response parsing, menu and page navigation, unchecked defaults, partial selections, malformed and stale responses, close behavior, and failed delivery. `:integration-bedrock:paperArtifactTest` and `:integration-bedrock:velocityArtifactTest` repeat the provider regression against the real shaded JARs.
- **Python tools:** dialog framing against fixed bytes (see [protocol notes](protocol-notes.md)), runner cleanup, and the release packager.
- **Node:** helpers for the Paper probe.

## Local test servers

The runners below use disposable servers under `.run/` (ignored by Git), bound to loopback with metrics off. They never download server software, and they restore the original ConsentGate config when they stop. Run any runner with `--help` for its options. Always check a server's startup log before trusting a pass: a plugin that failed to enable can make a test look successful.

Test on a minimal server too (only ConsentGate and what it needs). On a full plugin stack, another plugin can hide a missing dependency in the shaded JAR.

### Velocity

Put Velocity at `.run/velocity/velocity.jar` and PacketEvents at `.run/velocity/plugins/packetevents.jar`, then create `.run/velocity/velocity.toml`:

```toml
config-version = "2.7"
bind = "127.0.0.1:25590"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
ping-passthrough = "DISABLED"
enable-player-address-logging = false

[servers]
backend = "127.0.0.1:25591"
try = ["backend"]

[forced-hosts]

[advanced]
compression-threshold = -1
login-ratelimit = 0
read-timeout = 30000

[query]
enabled = false
```

Set `enabled=false` in `plugins/bStats/config.txt`. Build the Velocity JAR, then run:

```powershell
python tools/run_velocity_probe.py --protocol 772
```

Use `--protocol 771` for Java 1.21.6 or `772` for 1.21.8 (the default). The probe opens an instrumented backend listener and checks:

- no backend connection during 35 seconds of waiting with keepalives
- the pending limit rejects extra connections
- unchecked, malformed, extra-field, wrong-type, and foreign-token submissions cannot release the connection
- bad framing, truncated NBT, and oversized payloads disconnect without records or backend contact
- document IDs with hyphens map to safe checkbox names, and formatting renders instead of leaking tags
- valid acceptance releases the backend handshake, and an accepted reconnect skips the dialog
- status, reset, validate, and reload behave as documented; a locked SQLite write fails safely
- Leave disconnects, and an unsolicited configuration-finish packet cannot bypass the gate

The backend listener stops after the handshake on purpose, so backend errors after that point are expected. Read `.run/velocity/logs/latest.log` if startup fails.

`python tools/run_velocity_routing_probe.py` starts a fresh proxy with two Paper backends and checks disabled startup, unsupported-schema denial, world entry, server switching, accepted rejoin, forced hosts, and fallback. Timestamped relays confirm no backend contact before acceptance.

### Paper

Prepare `.run/paper-minimal` with an offline-mode Paper server bound to `127.0.0.1:25592`, with RCON, query, and metrics off. Supply Node dependencies (`minecraft-protocol` 1.68.0 and `prismarine-nbt` 2.8.0) with `--modules`:

```powershell
python tools/run_paper_probe.py --modules C:/path/to/node_modules
```

It tests the packaged JAR's acceptance, rejoin, reset history, version reload, Leave, and timeout.

`python tools/run_paper_installation_probe.py` builds a fresh stock Paper fixture and checks inactive defaults, disabled-mode play, unsupported-schema denial, and the full admission flow. Use `--version 1.21.8` when testing Paper 1.21.7: both use protocol 772, and the bot library's `1.21.7` label maps to an older protocol.

`python tools/run_paper_reconfiguration_probe.py --directory .run/paper-minimal --modules C:/path/to/node_modules --version 26.1` checks an initial consent wait followed by several play-to-configuration cycles. It compiles a helper plugin, so `javac` must be on PATH.

For long waits and anticheat timeouts, run the probe directly:

```powershell
node tools/probe_paper.cjs --modules C:/path/to/node_modules --name ConsentProbeA --hold 75 --play-seconds 70 --expect accepted
node tools/probe_paper.cjs --modules C:/path/to/node_modules --name ConsentProbeB --hold 0 --play-seconds 90 --reply-pings false --expect play-timeout
node tools/probe_paper.cjs --modules C:/path/to/node_modules --name ConsentProbeC --hold 150 --expect denied
```

- `accepted`: admitted after submitting, and still connected after the observation time.
- `denied`: disconnected before submitting, without entering play. Use a gate timeout shorter than the hold.
- `play-timeout`: disconnected after admission while pings are withheld.

A disconnect alone does not say which plugin caused it; match it with the server log. Use a new `--name` per run, or an offline reset. The probe records real acceptance for its synthetic player and does not prove anything was read.

## Remote databases

Real database tests are opt-in and need a dedicated database whose name starts with `consentgate_test_`. Set these privately:

- `CG_TEST_DB_HOST`, `CG_TEST_DB_PORT`, `CG_TEST_DB_DATABASE`, `CG_TEST_DB_USERNAME`, `CG_TEST_DB_PASSWORD`
- `CG_TEST_DB_ALLOW_WRITES=true`
- `CG_TEST_DB_SSL_MODE` (default `verify-full`) and `CG_TEST_DB_SERVER_CERTIFICATE` if needed

```powershell
.\gradlew.bat :core:remoteDatabaseTest --no-daemon --console=plain
```

The suite covers automatic setup (including simultaneous starts on an empty database), atomic grants, shared acceptance, replay conflicts, immutable revisions, concurrent instances, lost commit replies, cache outages and expiry, recovery, and newer-schema rejection. It never drops the database; synthetic records stay under unique scopes.

Extra TLS checks use `CG_TEST_DB_UNTRUSTED_CERTIFICATE` (an unrelated CA) and `CG_TEST_DB_WRONG_HOST` (an alias not in the certificate). `CG_TEST_DB_SOCKET_FAULTS=true` adds a loopback proxy that cuts a connection before COMMIT or drops the COMMIT reply.

On Windows, `tools/run_local_mysql_tests.py` does all of this against a disposable local database, with generated credentials and certificates, a 30-second load run, and a dump/restore check:

```powershell
python tools/run_local_mysql_tests.py --server C:/path/to/extracted/mysql
python tools/run_local_mysql_tests.py --engine mariadb --server C:/path/to/extracted/mariadb --client C:/path/to/mysql/bin/mysql.exe
```

It needs Python's `cryptography` package and an extracted official ZIP (with `mysql.exe`, `mysqladmin.exe`, and `mysqldump.exe`). Add `--platforms velocity paper --modules C:/path/to/node_modules` to also run both prepared platform fixtures through a TLS relay, including cache outage, expiry, and failed-save checks (`tools/run_remote_admission_probe.py`).

Run the suite against each database product and version before claiming support. Never stop a shared database or change its firewall for a test.

## Manual client checklist

Automated probes are not a real client. Before a release that touches dialogs, forms, or connection handling, check with real clients on a disposable installation.

**Java** (an unmodified 1.21.6 client on Velocity, 1.21.7 on Paper 1.21.7, plus the newest version you want to support):

1. Two required documents with several pages, both bundled languages. Check the selector, text, Previous, Next, and Back. Once with a small window and a large GUI scale.
2. Continue with a box unchecked: no entry and a readable error. Navigate away and back: selections are kept.
3. Wait at least 90 seconds on the screen (with a longer gate timeout), then accept. Normal entry and movement. Reconnect: no dialog.
4. Offline reset, reconnect, and choose Leave: immediate, readable disconnect, no entry.

**Bedrock on Paper** (Geyser-Spigot on the same server): with native forms on, repeat reading, Back, partial selections, acceptance, and accepted reconnect. Closing the agreement form returns to the menu; closing the menu disconnects. Then turn native forms off, restart, reset the test player, and check the translated dialogs.

**Bedrock on Velocity** (Geyser on the proxy): the same native checks, plus normal login, backend entry, and reconnect. Check login and routing problems separately from consent.

Record the platform build, client version, Geyser build, and which step passed or failed. Never admit a player as a workaround for a form that cannot be delivered.

## Testing on a shared server

- Use only a dedicated test server. Keep server addresses and credentials out of the repo.
- Back up the installed JAR and any config or documents before deploying a test build.
- Use a synthetic test account. Do not reset real players.
- Keep bots time-limited, with no gameplay, chat, or unrelated commands.
- Restore the original documents and config afterward, even on failure. Keep acceptance history; do not delete the database.
- If a test build fails, restore the previous JAR and config, restart, and check normal startup.
