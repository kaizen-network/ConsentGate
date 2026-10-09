# Releasing

## Build inputs

The Gradle 9.3.1 wrapper checks its own download checksum. All normal dependency versions are locked, and every dependency artifact and metadata file has a SHA-256 entry in `gradle/verification-metadata.xml`.

Five snapshot APIs use exact timestamped builds:

| API | Fixed build |
| --- | --- |
| Paper API | `1.21.7-R0.1-20250716.201120-28` |
| Velocity API | `3.4.0-20260121.190037-118` |
| Velocity Brigadier | `1.0.0-20210613.082804-10` |
| Geyser API | `2.10.0-20260604.180820-30` |
| Geyser Events | `1.1-20230815.153219-4` |

Gradle stores timestamped builds under their base `SNAPSHOT` version in lock files, which conflicts with the exact request. So these five are excluded from locking only; their versions and hashes stay fixed, and their dependencies are locked normally. Gradle also does not resolve them reliably offline, so builds need a network connection. Do not swap in a newer snapshot to work around a download failure.

Do not regenerate the verification file to silence a checksum mismatch. To change a dependency on purpose: review its source and metadata, update the fixed version, regenerate locks and hashes, and repeat the affected tests.

## Package

From a clean, committed tree, after the checks pass:

```powershell
python tools/prepare_local_release.py --sources .run/release-sources --fetch-sources
```

`--fetch-sources` downloads the dependency source files listed in `gradle/dependency-sources.json` and checks their hashes; leave it out if they are already there. The script builds both JARs and writes a package under `build/distributions/` with the JARs, a project source ZIP, dependency sources, `README.md`, `CHANGELOG.md`, `docs/`, license notices, `BUILD.json` (commit, source tree, test count), and checksums. It does not upload, commit, or push anything.

To package reviewed but uncommitted changes, add `--working-tree`. It snapshots tracked and new non-ignored files through a temporary Git index, leaves your index alone, and marks the package as a working-tree snapshot. Use a committed build for anything published.

The source ZIP includes the wrapper and everything needed to rebuild the shaded JARs. For a modified MariaDB driver, use the included upstream source and its Maven build, then update the dependency and verification entries before rebuilding.

## Publishing a version

1. Update the version in `build.gradle.kts` and in the `@Plugin` annotation of `ConsentGateVelocity.java`.
2. Add a `## <version> (<date>)` section at the top of `CHANGELOG.md`. Modrinth and GitHub Releases use that section as the release notes.
3. Run the automated tests, and the [manual client checklist](testing.md#manual-client-checklist) if dialogs, forms, or connection handling changed.
4. Commit and push, and wait for the GitHub Actions run on that commit to pass.
5. Publish the JARs from that exact run's package, with their SHA-256 checksums. Do not rebuild them separately.

Only list platforms and versions that were actually tested. Include the license and third-party notices with every published binary.
