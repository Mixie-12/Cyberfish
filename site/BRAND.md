# CyberFish / DeKoi brand tokens

Read this before writing any CSS or shader colour.

## Colours

| Token | Hex | Use |
|---|---|---|
| `--bg` | `#060a14` | Page background, loading screen |
| `--bg-sky-top` | `#040712` | Sky gradient, top |
| `--bg-sky-horizon` | `#0b1636` | Sky gradient at horizon, fog colour |
| `--water-base` | `#0a1430` | Ocean base colour |
| `--accent` | `#2EA8FF` | Glow, circuit traces, HUD frame, links |
| `--text` | `#c9d1e3` | Body text |
| `--text-dim` | `#4a5368` | Labels, captions, HUD text |
| `--text-mid` | `#8a94ab` | Secondary paragraph text |
| `--white` | `#f2f4fa` | Headings, wordmark |
| `--fish-body` | `#e8edf5` | Fish body material |
| `--fish-fin` | `#dbe6f5` | Fin tint (transparent) |
| `--line` | `#1b2234` | Hairline dividers |

## Typography

Google Fonts import:

```
https://fonts.googleapis.com/css2?family=Michroma&family=JetBrains+Mono:wght@400;500&display=swap
```

| Role | Font | Size | Case | Tracking |
|---|---|---|---|---|
| Wordmark "CYBERFISH" | Michroma | 16px nav, 34px large | Upper | 0.02em |
| Product name "DeKoi" | Michroma | 20px to 52px | Mixed case, exactly `DeKoi` | 0.01em |
| H1 | Michroma | 40 to 56px desktop, 28px mobile | Upper | 0.02em |
| Section numbers 01 02 03 | Michroma | 14px | | 0.1em |
| HUD labels, nav links, chips | JetBrains Mono 500 | 12px | Upper | 0.12 to 0.14em |
| Paragraph | JetBrains Mono 400 | 15px, line-height 1.75 | Sentence | 0 |
| Small caption | JetBrains Mono 400 | 11 to 12px | Upper | 0.14em |

Rules: Michroma only for wordmark, H1 and section numbers. Everything else JetBrains Mono. Paragraphs max 3 to 4 lines. No font weight above 500 except the mark, which is already outlined.

## Logo

- `logo/cyberfish-mark.svg`: flat version, white fill with accent stroke. Nav, favicon, footer.
- `logo/cyberfish-mark-glow.svg`: same paths with a blue halo filter. Hero and large sizes only; it looks blurry under about 48px tall.
- `logo/app-icon-1024.png`: the Android app icon, for reference on glow intensity and the scanline texture. Do not use it on the site.
- The mark is the characters `><>` set in JetBrains Mono ExtraBold with -0.06em tracking, outlined to paths. viewBox is `30 -649 1620 640`.
- Lockup: mark at cap height of the adjacent text, gap 0.6em. Never taller than the text.

## Voice

Calm, specific, plain language. No exclamation marks. No hype words: seamless, robust, powerful, cutting-edge, leverage, revolutionary. Sentence case for all copy; uppercase only for HUD labels and chips. Energy comes from clarity, not adjectives.

## Product facts (do not invent beyond these)

- DeKoi is a small plug-in box (Raspberry Pi) for homes and small offices that pretends to be a network storage drive.
- It runs 9 decoy services: HTTP, HTTPS, SSH, FTP, Telnet, MySQL, RDP, VNC, Redis.
- Nothing legitimate touches it, so any touch triggers a plain-language push notification on the owner's phone within about a minute.
- Setup: install the Android app, write the SD card with DeKoi Setup, scan a QR code, plug in.
- It detects, it does not block. It does not see phishing, stolen cloud passwords, or attackers who go straight to one target.
- Android first. Data hosted in the EU. Attacker-typed passwords never leave the box.
- Built at Oslo Sprint 2026 by team CyberFish, Oslo.
