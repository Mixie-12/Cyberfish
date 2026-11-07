# 0006: Client stack

Status: accepted, 2026-11-07

## Context

Decisions for the Android app and DeKoi Setup made in Phase 4.

## Decision

- Android Gradle Plugin 9 with its built-in Kotlin support; the Kotlin Android
  plugin is not applied. Kotlin 2.4, Compose BOM, Material 3.
- `clients/shared` is a plain Kotlin/JVM library (claim-code format, design
  tokens as raw values). AGP 9 no longer allows the Kotlin Multiplatform plugin
  together with an Android application module, and the shared code needs no UI
  types.
- `compileSdk` and `targetSdk` 37: current AndroidX libraries require 37 to
  compile against, and a new app has no older behaviour to preserve. `minSdk`
  29. Behaviour on API 37 is verified on a real phone in Phase 7.
- No Material icon libraries: they are deprecated and frozen. The few icons are
  vector drawables in the app.
- One ViewModel for the signed-in session and manual wiring instead of a
  dependency injection framework. The app has one data source and a handful of
  screens.
- ktlint 1.8.0 is the only Kotlin formatter, run through `clients/ktlint.sh`
  at a pinned, signature-verified version.
- Backend URL, publishable key and Google web client ID are Gradle properties
  filled by CI from repository variables. Release builds fail without them;
  debug builds may run without them.
- Backups and device-to-device transfers exclude all app data, so a session
  never moves to another phone.

## Consequences

- Moving to AGP 10 should need no Kotlin plugin changes.
- Each future `targetSdk` raise is its own tested change.
