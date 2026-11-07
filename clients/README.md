# clients

Gradle root for the Kotlin code: `shared/` (claim-code format and design
tokens, plain Kotlin), `app/` (Android) and `burner/` (DeKoi Setup).

Requires JDK 21. Run from this directory:

```
./gradlew build        # compile and test everything
./ktlint.sh            # check formatting
./ktlint.sh --format   # fix formatting
```
