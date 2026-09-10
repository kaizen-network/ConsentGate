# Platform findings

Research date: 2026-09-10. These are source findings and proposed approaches, not tested compatibility claims.

## What “at the front” means

On a network, install the plugin on the proxy itself. The intended sequence is authentication, acceptance check and dialog, successful save, then the first backend connection. It does not introduce another proxy process in front of Velocity or BungeeCord.

The proxy already handles a connection and player identity before this screen. The promise is no backend connection before admission. On a standalone server, the equivalent promise is no world entry before admission.

| Platform | What the inspected sources establish | Work still needed |
| --- | --- | --- |
| Velocity | Login handling enters configuration before selecting and connecting to the initial server. Its ordinary configuration event runs with a configuring backend. | Hold an awaited login/initial-selection event, send and receive dialogs through a packet adapter, maintain keepalives, then resume safely. |
| BungeeCord | Has native dialog methods and an asynchronous configuration event. Its current normal login path obtains backend login success before sending client login success. | Prove a backend-free configuration stage. The normal configuration event alone is insufficient. |
| Paper | Has a dialog API and an asynchronous configuration event before world entry. | Prove bounded waiting, callbacks, timeout, shutdown, and supported builds. |
| Plain Spigot | Current Player API has dialog methods. This does not establish a pre-world connection hold. | Investigate configuration packet interception and safe resumption. Do not call a post-join freeze equivalent. |

Evidence: [Velocity login handling](https://github.com/PaperMC/Velocity/blob/843a47e2a38325309cd66133149fc9a984f76bb8/proxy/src/main/java/com/velocitypowered/proxy/connection/client/AuthSessionHandler.java), [Velocity configuration handling](https://github.com/PaperMC/Velocity/blob/843a47e2a38325309cd66133149fc9a984f76bb8/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ClientConfigSessionHandler.java), [BungeeCord backend login handling](https://github.com/SpigotMC/BungeeCord/blob/7e5a616a77ef3043cf0807a332401ea0f2514ddc/proxy/src/main/java/net/md_5/bungee/ServerConnector.java), [BungeeCord configuration dispatch](https://github.com/SpigotMC/BungeeCord/blob/7e5a616a77ef3043cf0807a332401ea0f2514ddc/proxy/src/main/java/net/md_5/bungee/connection/DownstreamBridge.java), [Paper connection event](https://jd.papermc.io/paper/26.1.2/io/papermc/paper/event/connection/configuration/AsyncPlayerConnectionConfigureEvent.html), [Spigot Player API](https://hub.spigotmc.org/javadocs/bukkit/org/bukkit/entity/Player.html).

Velocity and Paper are the initial release targets. BungeeCord and plain Spigot are later targets and do not block the first release. Preserve the admission guarantee when adding platforms.

## In-game documents

Java Edition introduced dialogs in 1.21.6. They support text, inputs, buttons, and scrollable content. Configuration-phase dialogs must use inline definitions, and command click actions are unavailable there. Use custom click callbacks for acceptance and navigation. [Minecraft release notes](https://www.minecraft.net/fr-ca/article/minecraft-java-edition-1-21-6)

Therefore, full policies can live in local files and open as separate screens without a website, resource pack, or client mod. Long documents should be split into administrator-defined pages to keep payloads bounded. Opening a page is not proof that somebody read it.

The minimum client feature version is separate from the supported server/proxy versions. Exact platform builds, Java runtimes, and client versions must come from prototype tests. Do not advertise every later version as automatically supported.

## Dependencies and alternatives

- Prefer native platform APIs when they provide the required connection guarantee.
- Evaluate PacketEvents for protocol encoding and callbacks. It has configuration dialog and custom-click wrappers, but wrappers alone do not manage a backend-free session. [PacketEvents repository](https://github.com/retrooper/packetevents), [configuration dialog wrapper](https://github.com/retrooper/packetevents/blob/2.0/api/src/main/java/com/github/retrooper/packetevents/wrapper/configuration/server/WrapperConfigServerShowDialog.java)
- Velocity's native dialog API request was closed as not planned. Treat packet support as a separate adapter, and verify the selected build. [Upstream issue](https://github.com/PaperMC/Velocity/issues/1644)
- LimboAPI is a possible Velocity fallback, providing virtual servers inside the proxy. It adds a dependency and uses AGPL-3.0, so it needs a separate compatibility and licensing review before adoption. [LimboAPI](https://github.com/Elytrium/LimboAPI)
- A dedicated holding backend would weaken the requested architecture and add infrastructure. It is only an alternative to discuss if strict prototypes fail.

## Older clients and Bedrock

Default proposal: unsupported clients receive a configurable disconnect message, with no silent bypass. ViaVersion does not by itself prove the client can display this flow; test translation paths explicitly.

Include Geyser-translated dialogs in the initial compatibility matrix. Translation is the baseline Bedrock path. Verify full text, inputs, navigation, and callbacks on supported Geyser builds at the actual admission stage.

An optional native renderer can use Cumulus through Geyser or Floodgate. Share document content and admission logic. Pre-login identity detection does not establish form delivery at that point; test delivery and responses before enabling native forms. [Floodgate API](https://geysermc.org/wiki/floodgate/api/), [Cumulus forms](https://geysermc.org/wiki/geyser/forms/)

Detect Bedrock clients through a supported API, not a username prefix. If native forms are unavailable, use translated dialogs only on a verified configuration. Otherwise disconnect with an actionable message. Neither path may bypass acceptance or contact a backend early.

## Repository examples

| Reference | Useful pattern |
| --- | --- |
| [ViaVersion](https://github.com/ViaVersion/ViaVersion) | Common/API modules and thin platform entry points; its current base JAR targets Paper and Velocity, with separate integrations for other platforms. |
| [PacketEvents](https://github.com/retrooper/packetevents) | Shared protocol code with platform-specific integration. |
| [SkinsRestorer](https://github.com/SkinsRestorer/SkinsRestorer/tree/dev) | Shared, Bukkit, Bungee, Velocity, and universal modules. |
| [FancyDialogs build](https://github.com/FancyInnovations/FancyPlugins/blob/main/plugins/fancydialogs/build.gradle.kts) | A concrete Paper dialog plugin; its platform and dependency setup is not proof of proxy support. |

Use these as architectural references. Copying code would require respecting its license. Keep this project smaller than these general-purpose systems.

The supplied [Minecraft Wiki dialog page](https://minecraft.wiki/w/Dialog) could not be fetched during this review. Minecraft's own release notes supplied the protocol/UI evidence instead.
