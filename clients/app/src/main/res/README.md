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

In `drawable/`, besides `ic_launcher_monochrome.xml`,
`ic_launcher_foreground.xml` (drawn by `BrandTile`) and `ic_notification.xml`
(the status bar icon) are the earlier koi mark. Every other `ic_*.xml` is a
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
