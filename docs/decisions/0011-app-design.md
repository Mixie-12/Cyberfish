# 0011: App design system and notifications

Status: accepted, 2026-11-07

## Context

The owner asked for a redesigned app: UniFi depth with Apple clarity,
visual and animated, easy for someone who is not technical. A research pass
(UniFi, Apple HIG, Firewalla, Fing, eero, Material 3, Android notification
and accessibility guidance, research on security warnings) produced
`docs/design.md`. Some of its choices change existing behaviour.

## Decision

- Interactive accent moves from koi orange to blue (#0A63D1 light, #5AA2FF
  dark). In a security app orange reads as a warning. Orange stays for the
  brand mark. The tokens are shared, so DeKoi Setup changes too.
- Severity is colour, glyph and word together: critical "Act now", warning
  "Check soon", info "For your information". The words come from the
  database severity (ADR 0010); `rogue_router` is a warning because Thread
  border routers send router advertisements legitimately.
- Charts are drawn with Compose Canvas. A chart library (Vico 3.3.1,
  Apache-2.0, about 1 MB) would add axes and zoom we do not need and API
  churn across 3.x.
- Icons are Material Symbols vector drawables copied at a pinned commit.
  `material-icons-extended` stopped at 1.7.8 and Google recommends Symbols;
  custom illustrations for 17 kinds are not worth maintaining yet.
- Push stays on FCM notification messages. The design pass proposed
  data-only messages rendered by the app; in the Phase 7 test on an Honor
  phone the notification message arrived in about 1 s, and data-only
  messages depend on the app being allowed to start in the background, which
  OEM battery managers often deny. `notify` instead chooses a channel by
  severity (`alerts_urgent`, `alerts`, `updates`) and names the source
  device's kind, both from fixed vocabularies, so abuse case 8 holds.
- No action buttons in notifications and no trust from a swipe: trusting a
  device needs the full alert.

## Consequences

- Notification wording stays in two places, `notify.ts` and the app's
  strings (as ADR 0007 already accepted); both are covered by tests on their
  side.
- Material 3 1.4.0 (current BOM) has no stable expressive APIs; the design
  uses standard components and revisits motion when 1.5.0 is stable.
