# 0025: A sound with the alarm of a very likely attack

Status: accepted, 2026-11-07

## Context

A critical alert that is very likely an attack opens with the alarm hero
and a firm click (`docs/design.md`, "Alert detail"). The owner added a
sound for it, `SFX/CyberFish Error Sound.mp3` (2.0 s). Everything else in
the app stays calm and silent. Notification sounds already belong to the
channels, which the owner sets in the phone's settings.

## Decision

- The sound plays in the app, once per opening of the alert's screen, at
  the moment of the firm click: only for the alarm hero, not when the
  owner comes back from a screen pushed on top, and leaving the screen
  stops it. Push notifications keep their channel sounds.
- It plays only when the phone's ringer mode is normal, and only while the
  owner keeps "Sound when an alert needs action now" on (Settings, on by
  default, stored on the phone).
- Audio attributes `USAGE_NOTIFICATION_EVENT` ("to attract the user's
  attention, such as a reminder", Android reference) with
  `CONTENT_TYPE_SONIFICATION`, not `USAGE_ALARM`: an alarm clock's usage
  plays through the alarm volume and through Do Not Disturb. Transient
  audio focus that lets other audio duck; no focus, no sound.
- `MediaPlayer`, created for the one play and released when it completes or
  the screen goes; `SoundPool` needs an asynchronous load for one sound.
- One copy of the file: a task in `clients/app/build.gradle.kts` copies it
  from `SFX/` to `raw/alarm_critical.mp3` in a generated resource directory,
  registered with `variant.sources.res.addGeneratedSourceDirectory`, the
  Variant API that AGP documents for generated resources (Extend the
  Android Gradle plugin, "Contribute generated sources to the build").

## Consequences

- An owner on silent, on vibrate or in Do Not Disturb still gets the hero
  and the click, never the sound.
- Renaming or moving the file in `SFX/` fails the build, by design.
- The sound is CyberFish's own; it needs no notice on the licences screen
  (`clients/app/src/main/res/README.md`).
