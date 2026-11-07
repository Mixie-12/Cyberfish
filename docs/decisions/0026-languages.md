# 0026: Languages

Status: accepted, 2026-11-07

## Context

The app gained a Bokmål translation (`values-nb`) and push text in the
phone's language (ADR 0025). DeKoi Setup was still English only, with its
text in Kotlin. Each language added multiplies the text to write, review
and keep in step, so the set of languages and the rules for them are fixed
here once.

## Decision

- Two languages: Norwegian Bokmål (`nb`) and English. English is the
  fallback: it holds every text, and any other language gets it. There is
  no Nynorsk text.
- The app: `values/` in English and `values-nb/` in Bokmål; Android picks
  from the phone's language list. `MissingTranslation` and
  `InconsistentArrays` are fatal in `clients/app/lint.xml`, so a string or
  a list item without its Bokmål text fails the build, release builds
  included.
- Push text follows the language the app registers with its push token
  (ADR 0025).
- DeKoi Setup follows the Windows display language
  (`Locale.Category.DISPLAY`, which can differ from the regional format):
  `nb`, `no` or `nn` get Bokmål, everything else English. Its text is in
  `strings.properties` and `strings_nb.properties`, read with
  `java.util.ResourceBundle` (in the JDK, no dependency); `StringsTest`
  fails on a key or a placeholder missing in either language. The runtime
  includes `jdk.localedata`, so country names, their order and the decimal
  comma follow Bokmål too.
- Texts are whole sentences per case. Never build a phrase from parts
  ("A new %s"): Bokmål adjectives agree with the noun's gender (en ny
  telefon, et nytt nettbrett).
- The Bokmål words follow the app's `values-nb`: din DeKoi, varsel,
  lokkedue, enhet, kjent enhet, ruter, minnekort.

## Consequences

- Every new or changed text needs its Bokmål version in the same commit.
- DeKoi Setup gives Nynorsk and plain Norwegian (`no`) systems Bokmål.
  What the app shows on a phone set to Nynorsk is Android's choice and has
  not been tested.
- `jdk.localedata` adds about 28 MB to DeKoi Setup's unpacked runtime;
  jlink could keep only `en` and `nb`, but the Compose plugin does not pass
  `--include-locales`.
- The Wi-Fi checks in `shared` (`Network.WiFi`) give English messages, which
  DeKoi Setup shows as they are until those checks report a reason it can
  translate.
