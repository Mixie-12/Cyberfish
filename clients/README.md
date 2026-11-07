# clients

Gradle root for the Kotlin code: `shared/` (claim-code format and design
tokens, plain Kotlin), `app/` (Android) and `burner/` (DeKoi Setup).

Requires JDK 21 and the Android SDK (platform 37). Run from this directory:

```
./gradlew :shared:build :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :burner:build
./ktlint.sh            # check formatting
./ktlint.sh --format   # fix formatting
```

Release builds need the public backend settings as Gradle properties:
`-Pdekoi.supabaseUrl=... -Pdekoi.supabaseKey=... -Pdekoi.googleWebClientId=...`

DeKoi Setup bundles `device/image.json`, `device/cloud-init/` and
`device/host/` at build time, and the CyberFish fonts from `UX/UI/` with
their OFL-1.1 licence texts. After `:burner:test`, check the rendered
cloud-init files with cloud-init itself (from the repository root):

```
docker run --rm -v "$PWD:/repo:ro" debian:trixie@sha256:9c6e381421d0f48d1b8133e573f33c185ee16cbf505eda9c4a8a4791b25ea8a6 sh /repo/clients/burner/check-cloud-init.sh
```

Run DeKoi Setup (Windows), or build its installer into
`burner/build/compose/binaries/main/msi/` (the release job does this on
`v*` tags):

```
./gradlew :burner:run
./gradlew :burner:packageMsi -Pdekoi.version=0.1.0
```
