# Local distribution and reproducible inputs

The package is for final testing. It is not a published or production release. See [release progress](16-release-progress.md) for the remaining client checks.

## Build inputs

Use the checked-in Gradle 9.3.1 wrapper with JDK 25. Output bytecode targets Java 21. All normal dependency versions are locked, and dependency artifacts and metadata have SHA-256 entries in `gradle/verification-metadata.xml`. The wrapper also checks its distribution checksum.

The five snapshot APIs use the exact timestamped builds tested during development. Gradle records them under base `SNAPSHOT` versions in lock files, which conflicts with timestamped requests. Those five modules are therefore excluded from locking only; their explicit versions and verification hashes remain fixed. Their transitive dependencies are locked normally.

| API | Fixed build |
| --- | --- |
| Paper API | `1.21.7-R0.1-20250716.201120-28` |
| Velocity API | `3.4.0-20260121.190037-118` |
| Velocity Brigadier | `1.0.0-20210613.082804-10` |
| Geyser API | `2.10.0-20260604.180820-30` |
| Geyser Events | `1.1-20230815.153219-4` |

Build with `./gradlew build` or `.\gradlew.bat build`. A network connection is required: Gradle 9.3.1 did not consistently resolve these timestamped builds in offline mode, even after a successful online build. The portable SQL runner uses the same normal resolution path. Do not substitute a newer snapshot to work around a download failure.

The verification file records reviewed local build inputs. An intentionally incorrect Paper API hash rejected the build, including for that timestamped snapshot; the original hash was then restored. This is not a claim that every upstream artifact has an authenticated publisher signature. Do not regenerate it to silence a checksum mismatch. For an intentional dependency change, review its source and metadata, update the fixed version, regenerate locks and hashes, and repeat the affected checks.

## Create the package

Run from a clean committed source tree after the build checks pass:

```powershell
python tools/prepare_local_release.py --sources .run/release-sources --fetch-sources
```

The optional `--fetch-sources` flag downloads the exact source materials listed in `gradle/dependency-sources.json` and verifies their hashes. Omit it when those files already exist. The script builds locally, packages the two JARs, project source ZIP, dependency source files, documentation, notices, and checksums under `build/distributions/`. It makes no upload, commit, push, or deployment.

Keep the source and notice materials with the binaries when preparing a later distribution. The project source includes the wrapper and the transformations needed to rebuild the shaded JARs. For a modified MariaDB driver, use the included complete upstream source archive and its Maven build file, then update the project dependency and verification entries before rebuilding ConsentGate.

## Candidate notes

This candidate implements pre-admission documents, explicit acceptance, translations, Java dialogs, optional native Bedrock forms, SQLite and remote SQL storage, outage caching, preserved withdrawal history, administrator preview, document viewing, status, reset, validation, and reload.

The stock Paper 1.21.7 check found and fixed an unavailable Adventure dialog-close method. Storage checks found and fixed older or replayed decisions that could otherwise report success without changing current state. The shared session now completes callbacks outside its lock to avoid a native-response and timeout deadlock.

Final unmodified-client checks remain necessary for the advertised version boundary, GUI layout, and Paper Bedrock delivery. BungeeCord, plain Spigot, and external import tools are outside this candidate.
