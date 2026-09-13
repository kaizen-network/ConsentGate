# Dependency notices

ConsentGate is GPL-3.0-only. Bundled dependencies retain the licenses below. Their full license texts are included in the plugin JARs. The local distribution includes dependency source materials and this source tree, including the build scripts used for shading.

| Bundled library | Version | License | Packaging |
| --- | --- | --- | --- |
| SnakeYAML | 2.7 | Apache-2.0 | Both JARs, relocated to `io.github.consentgate.internal.snakeyaml` |
| Xerial SQLite JDBC | 3.53.4.0 | Apache-2.0, with the included Zentus BSD notice and SQLite public-domain code | Both JARs, including native SQLite libraries |
| MariaDB Connector/J | 3.5.10 | LGPL-2.1-or-later | Both JARs, relocated to `io.github.consentgate.internal.mariadb` |
| Adventure NBT | 4.26.1 | MIT | Paper only, relocated to `io.github.consentgate.internal.adventurenbt`, except the shared API package |
| Examination API and String | 1.3.0 | MIT | Paper only, relocated to `io.github.consentgate.internal.examination` |

Relocation changes package names in bytecode and service descriptors. ConsentGate does not edit these libraries' upstream Java sources. The Gradle scripts describe the transformations. Rebuilding the project with a modified dependency and replacing the resulting platform JAR is supported; no signature or license check prevents replacement. Review local server policy before replacing software.

Full notices are under `META-INF/licenses/`, plus SQLite's `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` and `LICENSE.zentus`. The root project license is `META-INF/LICENSE`. Dependency sources, build metadata, and the full MariaDB Connector/J source archive are listed with URLs and SHA-256 values in [gradle/dependency-sources.json](gradle/dependency-sources.json).

Paper, Velocity, PacketEvents, Geyser, Cumulus, Adventure text APIs, and their API-only dependencies are supplied by the selected platform or its separately installed plugins. They are not bundled by ConsentGate. Development and test dependencies are listed in the Gradle lock files and verification metadata.
