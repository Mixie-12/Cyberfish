# 0024: Fake credentials in the bait files

Status: accepted, 2026-11-07

## Context

The decoy file share (ADR 0018) held three dull files. The owner wants the
share to look worth stealing from in a demo, with passwords, cards and bank
codes, and to know when an intruder goes further and tries what they found.
Sign-ins on every decoy already report the user name; the password never
leaves the device and must not start to.

## Decision

- Public holds files with tempting Norwegian and English names, written for
  the decoy: card numbers are documented test numbers, personal numbers are
  synthetic (Skatteetaten's test population, month plus 80), the passport
  number is the ICAO 9303 specimen, the IBAN is the registry's example for
  Norway, and the 24 recovery words fail the BIP 39 checksum. No logos.
  They are small text sources in `device/canary/persona/public`; the image
  build writes them as PDF, xlsx, docx and PNG with the standard library.
- The credentials in them use user names that exist nowhere else
  (honeytokens), listed once in `bait_users` in `persona.json`. The bait
  files take them from there, and the agent image carries the same file.
- The agent adds `"bait": true` to the detail of a sign-in on any decoy
  whose user name, ignoring case, is one of them. It matches the user name
  only: it never reads the password, so it cannot tell whether the bait
  password was typed. A bait sign-in is never collapsed into earlier
  ordinary sign-ins from the same source.
- `private.valid_detail` accepts `bait` as a boolean. The kind and severity
  stay `login_attempt`, critical; the app explains that the name came from
  the decoy's files. The push text does not change.

## Consequences

- A bait sign-in shows that someone read the share and acted on it, a
  stronger signal than a guessed password.
- A user name an attacker would guess anyway would raise false bait flags,
  so bait names stay specific (`familie.admin`, not `admin`).
- The backend must accept the key before agents send it: the migration
  deploys with the merge to `main`, agents only with a release. An older
  backend refuses a batch with `bait` in it.
- Changing a bait name changes the files and the agent together through
  the persona; cards keep both in step through image updates.
