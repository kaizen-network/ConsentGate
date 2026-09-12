# ConsentGate

Configurable in-game agreements for Minecraft servers and networks.

ConsentGate lets administrators present rules, policies, and other documents before players are admitted. Documents live in local files with custom titles, full text, and independent versions. No website or additional hosting is required.

Fresh installations include inactive Terms of Service and Privacy Policy starter templates. Administrators must review and adapt them before use. Velocity also offers optional native Bedrock forms through a local Geyser installation, with separate document buttons and agreement toggles.

## Status

Early development. Velocity now loads styled local documents, supports automatic locale matching and an optional language selector, checks and stores SQLite acceptance before backend admission, and supports full document navigation. Paper has an initial shared Java consent flow and administrator commands. Paper administrator commands have console and synthetic-client checks. Native Bedrock forms, broader failure testing, and verification of an existing upstream GrimAC timeout fix remain open. Neither artifact is a production release. Features below describe the planned complete plugin.

## Planned features

- Read full documents in game and explicitly accept required agreements.
- Configure names, versions, order, wording, colors, safe text formatting, and translations.
- Request acceptance again when required documents change.
- Store acceptance in SQLite, MySQL, or MariaDB.
- Use a local persistent cache with remote storage.
- Support Java dialogs and Bedrock through Geyser, with optional Cumulus forms.

## Platforms

| Platform | Target |
| --- | --- |
| Velocity | Initial release: acceptance before any backend connection |
| Paper | Initial release: acceptance before world entry |
| BungeeCord / Spigot | Later platform support |

Connection handling must pass prototype tests before these guarantees are advertised as supported. Exact platform builds and client requirements will accompany the first release.

## Documentation

- [Build and test the prototypes](docs/05-development.md)
- [Platform feasibility](docs/01-feasibility.md)
- [Player flow and configuration](docs/02-product-and-config.md)
- [Storage and database upgrades](docs/03-storage-and-migration.md)
- [Administrator commands](docs/07-admin-commands.md)
- [Paper implementation progress](docs/10-paper-progress.md)
- [Paper anticheat compatibility](docs/12-paper-anticheat-compatibility.md)
- [Architecture and roadmap](docs/04-implementation-plan.md)

## License

GNU General Public License v3.0 only (`GPL-3.0-only`). See [LICENSE](LICENSE). Dependencies retain their respective licenses.
