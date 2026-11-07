# 0025: Push text in the phone's language

Status: accepted, 2026-11-07

## Context

The app has a Norwegian Bokmål translation (`values-nb`), but `notify`
words every push in English: a Norwegian phone showed Norwegian alerts and
English notifications. FCM notification messages are drawn by the phone
itself while the app is in the background, so the text has to be in the
payload (ADR 0011); the app cannot translate it. Notification text must
never carry attacker data (abuse case 8).

## Decision

- The phone's language is stored with its push token. The app calls
  `register_push_token_v2(token, language)` with `nb` for any Norwegian
  locale (`nb`, `no`, `nn`) and `en` otherwise. The database keeps a
  fixed list (`private.push_languages()`: `en`, `nb`); anything else is
  stored as `en`, so a newer app never fails to register.
- `notify` reads the language from `notification_targets_v3` and
  `weekly_summary_v2` and uses it only as a key into its fixed template
  table; unknown keys read English. The language is never put into a
  message.
- Templates name a source device by a whole phrase with its article ("A
  phone", "Et nettbrett"), never an article or adjective next to a
  placeholder, because Norwegian articles and adjectives agree with the
  noun's gender. The English new-device title follows the app's
  ("A phone joined your network for the first time") for the same reason.
- Additive only: `register_push_token_v1` stays and stores English;
  `notification_targets_v2` and `weekly_summary_v1` stay for the function
  deployed before this change. The app falls back to v1 when the backend
  answers 404.

## Consequences

- Push wording now lives in two languages in `notify.ts` and in the app's
  string resources (as ADR 0011 accepted for English); both have tests.
- A phone's language is updated when the app next opens signed in. A
  phone switched to Norwegian gets English pushes until then.
- Nynorsk phones get Bokmål pushes; there is no Nynorsk translation of
  the app or the templates.
- A new language means a template table in `notify.ts`, a value in
  `private.push_languages()` and a mapping in `pushLanguage` (`Push.kt`).
