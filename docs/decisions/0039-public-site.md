# 0039: The public site is static, on GitHub Pages, built from this repository

Status: accepted, 2026-11-09

## Context

For the launch, anyone must be able to find and install the app. The
CyberFish landing page, a three.js scene built at the hackathon, lived in
[nevermindmae/cyberfish-landing-page](https://github.com/nevermindmae/cyberfish-landing-page),
where this repository's owner can only read. The owner asked for it at
`dekoi.sysnordic.no`, on hosting with no server and nothing shared with
the product's backend or devices. The releases that hold the APK and the
MSI belong to this private repository, so nobody outside can download
them from GitHub. `sysnordic.no` is served by GitHub Pages already, and
its DNS is at Domeneshop, outside any repository.

Considered: moving this repository to the owner's GitLab group. It would
need every CI secret entered again by hand (GitHub never shows them), and
devices pull signed images from `ghcr.io`; not done. Hosting the site on
GitLab Pages or the owner's cluster: the site would need a token to read
this repository's releases, or the APK in a public package that the owner
makes public by hand; GitHub Pages needs neither.

## Decision

- `site/` holds the landing page as shipped (commit f9b1fc8 of the
  repository above; its Blender sources and screenshots stay there) and a
  download page, `site/download/`, with no script: one button for the
  APK, three install steps, DeKoi Setup for Windows, and hashes under
  "Technical details". The hero, the sprint and the sea floor link to
  it. The loading screen did too until 2026-11-10; the owner removed
  that link so visitors see the scene before the download. Anyone can
  still open `/download/` directly, and the QR code leads there.
- `site.yml` builds it with Vite and publishes it with GitHub Pages: on a
  push to `site/`, and from `app-release.yml` and `clients.yml` once a
  release has its files. It takes the newest release with both the APK and
  the MSI, checks their SHA-256 files and the APK's signing certificate
  against the one `app-release.yml` pins, and writes the version, sizes and
  hashes into the page. A build without those values fails.
- `site/vite.config.ts` holds the site's address and makes the QR code of
  the download page from it (uqr), in the dev server and in the build, so
  the page can be checked before it is published.
- The `github-pages` environment deploys from `main` only; the custom
  domain and HTTPS are set by `infra/github/apply.sh`. Pages from a
  private repository needs the owner's paid GitHub plan, which it has.
- Each page sets a Content Security Policy in a meta tag (Pages cannot
  send headers): the download page allows no script at all.

## Consequences

- The site has no server, no secret and no access to the backend; the
  worst a compromised site can do is offer a wrong file, and the page
  states the certificate Android checks every update against.
- One step outside git, once: a `CNAME` record `dekoi` to
  `mixie-12.github.io` at Domeneshop. Verifying `sysnordic.no` in the
  owner's GitHub Pages settings (a `TXT` record) stops anyone else's
  Pages site from claiming the name.
- The APK and the MSI are public from now on. The MSI is unsigned (code
  signing is on the roadmap), so Windows warns, and the page says what
  to do.
- Fonts still come from Google Fonts, as the landing page had them.
- Pages allows about 100 GB of traffic a month; the MSI is 74 MB.
