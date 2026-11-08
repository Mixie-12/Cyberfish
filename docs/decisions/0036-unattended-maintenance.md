# 0036: Maintenance without anyone watching

Status: accepted, 2026-11-08

## Context

The owner wants every running part to patch and maintain itself for years
with nobody watching. An audit of the device and the backend on 2026-11-08
found that most of it already does (unattended-upgrades with a 03:30
reboot, `podman auto-update` with rollback, `podman image prune` after each
update, journald in memory, the hardware watchdog, `Restart=always`, 90-day
retention, monthly key rotation, a daily monitor), and these gaps, each
tested in Debian trixie or on the local stack: unattended-upgrades holds
back for good every update that ships a new version of a conffile first
boot replaced (`nftables.conf`, `ulogd.conf`, `policy.json`); `autoclean`
keeps every kernel the Raspberry Pi archive still lists (11 per flavour
today); the agent's watchdog pings had no `WatchdogSec`; logrotate checks
the scan log's size once a day; `cron.job_run_details` grows forever;
nothing noticed pushes failing, the Google sign-in client being deleted
after six months unused, or a failed backup, rotation or expiry run.

## Decision

- Device, cards written from now on: dpkg keeps DeKoi's conffiles and
  installs the update (`--force-confdef`, `--force-confold`); `apt-get
  clean` weekly; `WatchdogSec=15min` on the agent; logrotate hourly.
- Backend: `apply_retention` deletes job runs older than 30 days.
  `monitor.sql` checks the Vault entries notify needs and notify's answers
  that pg_net keeps (6 hours). `monitor.yml` checks the Google client daily
  and the last run of `backup`, `expiry`, `rotate-keys` and `security`;
  `expiry.yml` fails while a credential replaced by hand has no date.
- Every growing table is bounded: by age (events, inventory, health 90
  days, job runs 30, pending setups 30, notified sources 1, pg_net 6
  hours, rate counts 70 minutes) or by a count per account (devices, push
  tokens). Push tokens get no age limit: a phone that never opens the app
  must still ring; FCM's `UNREGISTERED` removes them.
- Left to people, on purpose: the restore test (only the owner holds the
  backup key); approving releases and weekly rebuilds, without which
  container images, CA bundles and IDS rules age (ADR 0022); a kernel that
  does not boot, a worn-out card, or the end of Debian 13 support (LTS to
  June 2030), which need a new card; a network that blocks NTP, where the
  agent waits for the clock (TLS needs it).

## Consequences

- Cards in the field keep their host configuration; ADR 0037 proposes a
  signed path to change it.
- The monitor's issue stays open until the backup key and the `EXPIRES_`
  variables exist (owner actions 2 and 7).
- If the repository goes public, GitHub disables scheduled workflows after
  60 days without repository activity, the monitor among them; that needs
  an answer before owner action 6.
- Auth sessions never expire on the Free plan and grow by one per sign-in;
  Supabase Auth owns those tables, and DeKoi does not delete from them.
