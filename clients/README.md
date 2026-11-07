# clients

Gradle root for the Kotlin code: `shared/` (claim-code format and design
tokens, plain Kotlin), `app/` (Android) and `burner/` (DeKoi Setup).

Requires JDK 21 and the Android SDK (platform 37). Run from this directory:

```
./gradlew :shared:build :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./ktlint.sh            # check formatting
./ktlint.sh --format   # fix formatting
```

Release builds need the public backend settings as Gradle properties:
`-Pdekoi.supabaseUrl=... -Pdekoi.supabaseKey=... -Pdekoi.googleWebClientId=...`
