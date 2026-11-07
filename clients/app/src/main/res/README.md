# Resources

This note sits here because the resource merger accepts only resource
files inside `drawable/`.

## Launcher icon

An adaptive icon (`mipmap-anydpi/ic_launcher.xml`) made from
`Images/cyberfish-app-icon.svg`:

- `mipmap-*/ic_launcher_background.png` and `ic_launcher_foreground.png`
  (108 dp: 108, 162, 216, 324 and 432 px) are rendered from
  `clients/app/icon/launcher-background.svg` and `launcher-foreground.svg`.
  Those hold the source's screen and its glowing fish, unchanged, with the
  source's 1024 units as the 72 dp a launcher shows; the fish lies well
  inside the 66 dp safe zone. Two changes, explained in the files: the scan
  lines become their average tone, and the glowing edge is left out, since
  the launcher picks the shape.
- `drawable/ic_launcher_monochrome.xml` is the fish alone, for themed
  icons.

Method: headless Microsoft Edge draws each layer onto a canvas of the exact
size and saves the canvas as PNG. To render again (Windows, Git Bash, from
the repository root):

```
sh clients/app/icon/render.sh
```

## Symbols and marks

In `drawable/`, besides `ic_launcher_monochrome.xml`, `ic_notification.xml`
(the status bar icon) is the earlier koi mark. Every other `ic_*.xml` is a
Material Symbol (docs/design.md, "Libraries"):

- Source: https://github.com/google/material-design-icons, commit
  `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7` (2026-10-23)
- Licence: Apache-2.0
- Style: Material Symbols Rounded, weight 400, grade 0, 24 dp
- `ic_<symbol>.xml` is `symbols/android/<symbol>/materialsymbolsrounded/<symbol>_24px.xml`
  and `ic_<symbol>_fill.xml` is `<symbol>_fill1_24px.xml`, both unchanged.

To add a symbol, copy it from the same commit (from the repository root):

```
base=https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/android
curl -fsS -o clients/app/src/main/res/drawable/ic_NAME.xml "$base/NAME/materialsymbolsrounded/NAME_24px.xml"
```

## Fonts

`font/` holds the brand fonts (ADR 0020), copied unchanged from the Google
Fonts downloads in `UX/UI/` (fonts.google.com/specimen/Michroma and
fonts.google.com/specimen/JetBrains+Mono):

| File | Source in `UX/UI/` | Version | SHA-256 |
| ---- | ------------------ | ------- | ------- |
| `michroma_regular.ttf` | `Michroma/Michroma-Regular.ttf` | 1.100 | `aeb90da44b9f39fd7df446507f4109a39e0b8ffcad76ad428a0f737e9860e3ec` |
| `jetbrains_mono_regular.ttf` | `JetBrains_Mono/static/JetBrainsMono-Regular.ttf` | 2.211 | `b6b1ff4ddefe36d7f2a6174e1d001cab374e594519ee9049af028d577b64c5f5` |
| `jetbrains_mono_medium.ttf` | `JetBrains_Mono/static/JetBrainsMono-Medium.ttf` | 2.211 | `23d57b8a635d023c3717a9c7ac1721199d0f8c6fe4b06d4d5ba3af2170fcbaa2` |
| `jetbrains_mono_extrabold.ttf` | `JetBrains_Mono/static/JetBrainsMono-ExtraBold.ttf` | 2.211 | `4d65f6acd792c4b2007058d05d960671be218b61136050e157aea2f95bf05193` |

Both are under the SIL Open Font License 1.1. Their licence texts are
`raw/licence_ofl_michroma.txt` and `raw/licence_ofl_jetbrains_mono.txt`
(the downloads' `OFL.txt`), for the licences screen; `raw/keep.xml` keeps
them in release builds. ExtraBold is for the `><>` mark only.

## Sound

`raw/alarm_critical.mp3`, the alarm of a very likely attack (ADR 0025), is
not in this directory: the `<variant>AlarmSound` task in
`clients/app/build.gradle.kts` copies it unchanged from
`SFX/CyberFish Error Sound.mp3` into a generated resource directory, so the
repository holds one copy.

- Source: the owner's own sound, added to `SFX/` by the owner (MP3,
  48 kHz, 192 kbit/s, 2.0 s)
- Licence: CyberFish's own, proprietary like the rest of the repository; no
  notice on the licences screen
