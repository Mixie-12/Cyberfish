# 0032: Alerts appear by themselves while the app is open

Status: accepted, 2026-11-08

## Context

The app loaded its data when it was opened and when the owner pulled down.
An alert raised while the app was open showed up only after a pull; the
push for it was drawn as a notification over an app that did not show it.
`docs/PROMPT.md` asks for refreshes on open, on pull and on push, and no
realtime subscription; the push part was missing. Supabase Realtime stays
off (`docs/security.md`, "Platform settings"). The owner asked for alerts
to appear by themselves, with a short sound, while the app is open.

## Decision

- Push to app: `PushService` hands a push that names an event to
  `DekoiApplication.showInApp()`, a `SharedFlow` that
  `AppViewModel.watch()` collects only while the app is resumed and signed
  in. With a collector, the app loads at once and no notification is
  posted. Without one (paused, signed out) and for the weekly summary,
  which names no event, the notification shows as before. One rule for
  every screen: each one announces the alert itself (the list, the badge
  or a snackbar), so none needs the notification.
- Fallback: while resumed and signed in, the app also loads every 20 s,
  through the same `load()`; `repeatOnLifecycle(RESUMED)` stops it at
  pause. Pushes can be late or lost. Reads are not counted by the request
  limits (ADR 0016).
- New alert: `newAlerts()` in `Protection.kt` picks incidents of
  `alertsToLookAt()` (unseen, warning or critical, not from a known device)
  with an event the previous data did not have and none that was already
  to look at. An incident that grows stays quiet, as `notify` sends one
  push per source and 10 minutes (ADR 0007); one the owner read sounds
  again when its source acts again. The first load after each resume only
  catches up: what came meanwhile had its notification, and a tapped
  notification opens its alert, whose alarm (ADR 0028) would sound with
  it.
- Announcement: a light tick (context click), the phone's default
  notification sound (`RingtoneManager.TYPE_NOTIFICATION`) under ADR
  0028's attributes, focus and rules, played by its `MediaPlayer`
  (`Ringtone` reports no end, so focus could not be given back), and
  away from the Alerts tab a snackbar "New alert" with "Open", which
  opens it as a tapped notification does. The alert the owner is looking
  at is left to its screen, which shows it and sounds the alarm if it
  becomes one. The new incident enters the timeline with `animateItem()`
  and Home's "Needs attention" with `ExpandingVisibility`.
- One switch for both sounds: "Sound in the app" (was "Sound when an
  alert needs action now"), on by default, stored under the same key.

## Consequences

- An alert raised while the app is open shows as soon as its push
  arrives, and at most about 20 s later without one.
- An open app makes four reads (devices, the latest 200 events, hosts,
  networks) every 20 s; egress grows with the time phones keep it on
  screen.
- In the app, alerts play the phone's default notification sound, not a
  sound the owner set for the channel.
- Loads the owner did not ask for show no pull indicator; one that fails
  shows the usual banner until a load succeeds.
- An alert pushed during the first load after opening is loaded by it and
  plays no sound. One pushed just before the owner leaves the app, whose
  load is then cut short, gets neither a notification nor a sound; the
  next opening shows it.
