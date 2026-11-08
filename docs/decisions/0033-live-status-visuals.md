# 0033: Live status visuals

Status: accepted, 2026-11-08. Refines the motion and status colours of
ADR 0011 and `docs/design.md`.

## Context

The owner demos the app and asked that every feature visibly works: a
live "I am watching" view of each check with its state and when it last
reported, a moving sign for checks that are on, amber while one starts,
red with what to do when one fails, and views of the decoy, intrusion
detection and Wi-Fi Guard at work. The design allowed one idle loop on
screen, on a hero or the Features card in focus; failures were amber and
starting was grey. The data is fixed: no new backend fields.

## Decision

- One state vocabulary for every check and feature: `FeatureState` maps
  to a `Signal` (live, starting, failed, ask, quiet), drawn by one
  component, `CheckStatus`, on Your DeKoi, Features and Home's checks
  tile. Starting is amber, failed is red with one sentence on what to do;
  the containers restart by themselves (`Restart=always`), so the sentence
  says to wait an hour, then unplug the power.
- Still one idle loop on screen. A screen with several live visuals plays
  only the one nearest the middle (`centredKey`), as Features already did
  for its cards. The watch panel keeps to it by passing a light over its
  checks that are on, one after another, each for one loop of its drawing,
  from one shared clock. Lazy lists dispose what is off screen, so nothing
  plays there; reduce motion stops everything, and the Wi-Fi alarm shows
  its end state at once.
- "When it last reported" is the heartbeat time, once for the panel: the
  monitors arrive together in `device_heartbeat_v2`, every minute, so a
  per-row time would repeat it. Green while under 3 minutes old.
- Each check reuses its Features drawing as its icon; no new icons.
- Views use only what the app already reads. Wi-Fi Guard draws the
  `evil_twin` and `new_access_point` events, their BSSID and `signal_dbm`,
  and reuses the alarm of Alert detail for a copy. The app has no list of
  the access points the DeKoi learned, so it does not draw "known" ones.
  The decoy view names the services the heartbeat reports; ports are not
  in it, and copying them from the canary's configuration would make a
  second source of truth, so none are shown.

## Consequences

- Red now also means a check that does not work, always with its word and
  what to do, never alone.
- Showing ports or learned access points needs the agent to report them
  (a `_v2` heartbeat or Wi-Fi RPC); the views would then add them.
