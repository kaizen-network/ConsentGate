# Architecture

## The promise

- **Velocity:** no backend connection before acceptance.
- **Paper:** no world entry before acceptance.
- **Spigot (hold mode):** a weaker promise, announced as weaker. The player enters the world, but is frozen and hidden until acceptance, and nothing persistent is changed on them.

The proxy has already accepted the connection and knows the player's identity before the consent screen. ConsentGate does not add another proxy in front of Velocity. Everything below exists to keep the promise above, including when things fail.

## Why these platforms

| Platform | How it holds the player | Status |
| --- | --- | --- |
| Velocity | Login enters the configuration stage before the first server is chosen. ConsentGate holds an awaited login event and talks to the client through PacketEvents. | Supported |
| Paper | `AsyncPlayerConnectionConfigureEvent` runs before world entry and waits for its handler. | Supported |
| Spigot | Has dialog methods, but no stage before world entry that waits for a plugin. A separate JAR checks acceptance at pre-login; players without it join, then a listener cancels their actions, damage, chat, and commands and hides them until the save. | Hold mode, weaker promise |
| BungeeCord | Has dialog methods, but its normal login connects to the backend before the client finishes logging in, and no released API offers a stage before that. A proxy-only freeze cannot see movement, interaction, or damage. | No proxy JAR. Backends run the Paper or Spigot JAR with shared storage. |

Velocity has no native dialog API ([closed as not planned](https://github.com/PaperMC/Velocity/issues/1644)), so dialog packets go through PacketEvents. LimboAPI was considered and not used: it adds a dependency and uses AGPL-3.0. A separate holding backend would weaken the promise and add infrastructure.

New platforms must keep the same promise before support is announced, or be announced as a separate mode with its own, weaker promise next to it, as Spigot hold mode is.

The Spigot JAR is separate from the Paper JAR. Spigot has no Adventure, so the Spigot JAR shades and relocates it; relocating inside one shared JAR would also rewrite the Paper code that must use Paper's own Adventure. Each JAR refuses every login on the other platform, so a wrong JAR never runs a weaker gate silently.

## Dialogs

Java dialogs exist since 1.21.6. In the configuration stage they must be defined inline, and command click actions are not available, so ConsentGate uses custom click callbacks for navigation and acceptance. Long documents are split into admin-defined pages to keep packets bounded.

On Spigot, dialogs are built with the BungeeCord dialog API that Spigot exposes. Text goes through the shared Adventure formatter, then JSON, then BungeeCord components. The checkbox payload arrives as JSON, and only `Byte` values of 0 or 1 count, matching the Paper decoder.

Bedrock clients are detected through Geyser's API, never by a username prefix. Native forms use the Cumulus copy that Geyser's connection API needs. Geyser and Floodgate can each expose their own Cumulus, and binding to the wrong one causes a `LinkageError`. The renderer binds to Geyser's actual provider, and the packaged Bedrock tests check this with two independent providers.

## Admission state

```text
Authenticated -> Checking -> Showing documents -> Saving -> Admitted
                     |              |                |
                     +--------------+----------------+-> Disconnected
```

A current acceptance goes straight from Checking to Admitted. Only Admitted releases the held connection.

- Every action is bound to the connection, player, active document revision, and a random short-lived session token. Expired, repeated, forged, and cross-player callbacks are ignored.
- Input types and required checkboxes are checked on the server. Screen closure is not the gate; server-side connection state is.
- Saves are idempotent and release at most once.
- Disconnect, timeout, shutdown, and storage or rendering failures clean up without admitting anyone.
- Pending sessions, queued database work, payload sizes, callback rate, and retries are all bounded.
- Velocity checks admission again on every backend connection request, so initial and fallback routes cannot bypass it. It keeps sending keepalives while holding and consumes its own delayed responses.
- Paper waits only on its async configuration thread, never the main thread. Decision callbacks run outside the session lock to avoid a native-response and timeout deadlock.
- Paper and Spigot share `core.admission.AdmissionHold`, which commits before release and finishes once. Spigot hands Bukkit calls to the main thread and kicks every held player when the plugin disables.
- Language selection uses the connection token and a one-time claim, then checks acceptance again for the chosen language.
- Preview requests are marked in the shared admission model, and the storage entry point rejects them, so a preview can never save consent.

## Storage

- One SQL implementation per dialect (SQLite, MySQL/MariaDB), no ORM. H2 was left out: it would add another dialect without a clear benefit.
- Acceptance and its audit event commit in one transaction before the player is admitted.
- Two database workers per instance with a bounded queue. Each remote operation opens its own connection. Measure before adding a pool.
- Remote writes for the same player and scope are serialized with a row lock. Document IDs are processed in a fixed order to reduce deadlocks. Failed transactions are reported, not retried automatically.
- Newer UTC timestamps win; withdrawal wins a tie. A reset records a withdrawal even without prior acceptance, so a delayed older grant cannot revive consent. A reset that cannot replace a newer grant fails and rolls back.
- The driver has local file loading, multiple statements, and automatic transaction replay turned off.
- Remote and SQLite schema versions are independent. Tables are created only in an empty database; existing ones are never overwritten or upgraded automatically.

### Cache rules

- Only complete positive checks are cached. Freshness starts before the primary query and is never extended by a hit.
- Grants and resets invalidate the local entry before the primary write, even if the outcome is unknown. A per-player lock stops a reset from racing an older cache fill.
- A failed invalidation or broken cache file disables the cache for the process. A clean-shutdown marker in the lock file discards persisted entries after a crash.
- Future timestamps and backward clock movement are not trusted. Monotonic time also bounds freshness inside a running process.
- There are no offline write queues: they would admit players without a primary commit.

## Future work

- A BungeeCord gate, once a released `bungeecord-api` has a stage before backend connection (`PlayerConfigurationEvent` exists only on its development branch).
- Export and import between databases, keeping revisions, event order, UUIDs, timestamps, and a dry run.
- Retention and erasure: erasure must invalidate current decisions and must not come back through stale caches on other instances.
