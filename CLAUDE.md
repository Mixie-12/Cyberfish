# CLAUDE.md

DeKoi by CyberFish: a plug-and-play Raspberry Pi honeypot, an SD-card setup
tool (DeKoi Setup), an Android app, and a Supabase backend.

- What to build, in which order: `docs/PROMPT.md`
- Where the build stands: `docs/STATUS.md`. Read it at the start of every
  session. Update it at the end of every phase.
- Why things are the way they are: `docs/decisions/`
- Security model and evidence: `docs/security.md`

Component map: `backend/` Supabase project (hosted on Supabase Cloud). `device/`
everything that runs on the Pi. `clients/` Gradle root with `shared/`, `app/`
(Android) and `burner/` (DeKoi Setup). `site/` the public site and download
page on GitHub Pages. Each component README has its build, test and run
commands.


## Philosophy

Write code the way a kernel maintainer reviews it.

1. Simplicity is the requirement. The best code is code that does not exist.
   Prefer deleting code to adding it. Fewer moving parts beat clever ones.
2. Good taste: restructure data and control flow so special cases disappear,
   instead of adding branches for them. Get the data model right first; the
   code follows.
3. Do not reinvent. If the OS, the platform, Supabase, Podman, systemd or a
   mature library already does it, use that. Our code is glue. Before writing
   anything non-trivial, check whether something well-vetted exists.
4. One source of truth. Every fact (constant, schema, config value, string,
   version) is defined once and referenced everywhere else. Never copy. Do not
   abstract before there are three real uses.
5. Never break deployed devices. The device-to-backend API is an ABI. RPCs are
   versioned (`_v1`). Changes are additive. Migrations are expand, then
   contract. Old agents keep working until no device runs them.
6. Security is the floor, not a feature. Deny by default, least privilege,
   minimal attack surface. A change that weakens a control is wrong, even if it
   makes something work.
7. Talk is cheap; show it running. Nothing is done until it has been executed
   and verified. Report what you ran and what you saw.
8. Long-term quality, MVP scope. Build only what `docs/PROMPT.md` asks for, and
   build that properly.


## GitOps

Git is the only source of truth for every running environment, and CI is the
only thing that changes them (ADR 0002). Never change production by hand: no
dashboard edits, no ad hoc CLI commands against the hosted project, no logging
in to devices. If something cannot be expressed in git yet, it is a one-time
bootstrap step and belongs in a runbook.


## Security rules

- No secrets in the repository, code, logs, commit messages or chat. Use
  git-ignored `.env` files with a committed `.env.example`, and CI secrets.
- The Supabase `service_role` key lives only in the backend runtime. Clients and
  devices use the anon key with RLS or secret-checked RPCs.
- Every table has RLS enabled with explicit policies. Every policy change ships
  with a pgTAP test for both the allowed and the denied case.
- `SECURITY DEFINER` functions: `SET search_path = ''`, schema-qualified names,
  every argument validated, only rows the caller is entitled to,
  `REVOKE ALL ... FROM PUBLIC` and then explicit grants.
- SQL is static and parameterized. No string-built or dynamic SQL.
- Honeypot data is attacker-controlled. Bound its size, strip control
  characters, store it as data, render it as plain text. Never put it into
  notification text, shell commands or log format strings.
- Containers: non-root, read-only root filesystem, all capabilities dropped
  except the named few required, no-new-privileges, never privileged, never a
  container runtime socket, base images pinned by digest.
- Dependencies are pinned: lockfiles, hashes, image digests, Actions by commit
  SHA. A new dependency needs a justification in the commit body: what it does,
  why the standard library or platform is not enough, that it is maintained and
  widely used, and its license.
- No `curl | sh`. Every download is checked against a pinned SHA-256 or a
  signature.
- TLS everywhere outside localhost. No cleartext fallback.
- Never disable a test, lint, hook, check or verification to get green. Fix the
  cause.


## Code rules

- Small functions, flat control flow, early returns. Names say what things are.
- Handle an error where something useful can be done about it; otherwise let it
  propagate. No silent catches, no bare `except:`, no empty `catch`. Fail
  closed.
- No dead code, commented-out code, unused files, placeholder implementations,
  stub data in production paths, or TODOs without a reason and an owner.
- Comments explain why, never what. No banners, no restating the code.
- No emojis anywhere: code, comments, logs, commit messages, docs, UI text.
- No hype or filler in code, docs or commits ("seamless", "robust", "powerful",
  "leverage", "cutting-edge", "state-of-the-art", "blazing fast",
  "comprehensive"). State facts.
- Consistency beats preference. Follow the existing pattern in the file and the
  component. Formatters decide formatting.
- No speculative generality: no interface with one implementation, no factory,
  manager or helper for a single call site, no option nobody asked for.

Per language:
- Kotlin: one formatter (chosen in Phase 0, never switched). No `!!` unless a
  comment proves the value cannot be null. Structured concurrency. StateFlow for
  UI state. No reflection-based frameworks.
- Python (device agent): standard library only, type hints, `ruff check` and
  `ruff format` clean, `pytest`. Python version is the one in the agent base
  image.
- SQL: lowercase keywords, snake_case names. One migration per change. Never
  edit a migration that has been applied anywhere. Tests in
  `backend/supabase/tests`.
- TypeScript (Edge Functions): strict mode, no `any`, `deno fmt` and
  `deno lint` clean.
- Shell: `set -euo pipefail` (or POSIX `set -eu`), shellcheck clean, quote every
  expansion. A script over about 50 lines belongs in a real language.
- Containerfiles: hadolint clean, multi-stage, pinned by digest, no package
  caches in the final image.


## Git

- Commit early and often. Every self-contained step that builds and passes its
  tests is a commit. Never squash a phase into one commit.
- One logical change per commit. Every commit builds and passes tests.
- Message format:

  ```
  area: imperative summary, at most 72 characters

  Why the change is needed and what it does, wrapped at 72 columns.
  Trade-offs and anything a reviewer must know.
  ```

  Areas: `backend`, `device`, `agent`, `canary`, `app`, `burner`, `shared`,
  `site`, `ci`, `docs`, `repo`.
- Push to `origin` whenever tests are green after a few commits, and always at
  the end of a phase.
- Never force-push `main`, rewrite published history, or skip hooks
  (`--no-verify`). Never commit build outputs, keystores, `.env` files or
  generated artifacts.
- LF line endings everywhere (enforced by `.gitattributes`). Files that run on
  the Pi must never contain CRLF.


## Workflow

1. Start of session: read `docs/STATUS.md`, then the relevant part of
   `docs/PROMPT.md`.
2. Before using a tool, library, flag or config format, check its current
   official documentation. Do not rely on memory.
3. Implement the smallest useful step. Run its tests. Commit.
4. Before each commit, review the diff as a hostile reviewer: what breaks, what
   is exposed, what is duplicated, what can be deleted.
5. End of phase: full test suite, security review of trust-boundary changes,
   `docs/STATUS.md` updated, push.
6. Record every significant decision when it is made, not afterwards: an ADR
   in `docs/decisions/NNNN-title.md` (context, decision, consequences; one page
   at most) and a line in `docs/decisions/README.md`. Significant means anything
   a future reader would otherwise have to rediscover: a choice between
   alternatives, a constraint found by testing, a trade-off accepted. A
   deviation from `docs/PROMPT.md` also needs a note to the owner.

Ask the owner before spending money, creating external accounts, doing anything
irreversible (deleting data, publishing, rotating production keys), installing
software on their machine, or when a security-relevant requirement is
ambiguous. Otherwise choose the simplest option, record it, and continue.


## Writing

- Docs: short, factual, current. Link to code instead of copying it.
- App and DeKoi Setup text: plain language for a non-technical person. Calm and
  specific. No jargon in primary text, no fear-mongering, no exclamation marks.
  Technical detail goes in a collapsed section.
- Reports to the owner: what was done, how it was verified, what is not done or
  not verified. No padding.
