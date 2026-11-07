# 0030: The mark drawn from the app icon

Status: accepted, 2026-11-07. Refines the logo in ADR 0020.

## Context

The owner said the logo in the app looks a little different from the
artwork in `Images/`. Both apps set `><>` in JetBrains Mono ExtraBold with a
blue stroke 0.08 em wide under the fill and one soft shadow, a reading of
the aesthetic guide. `Images/cyberfish-app-icon.svg` (rendered as
`Images/Cyberfish_logo.png`) has the same glyphs as a path, but a blue edge
of a tenth of that width, a bluer white (#F4FBFF) and five stacked blurs
for the glow. The thick stroke closed the 0.08 em gaps between the glyphs,
so the app's mark read as one joined shape, 14 % taller than its stated
height. The status bar icon was still the earlier koi, and the widget set
`><>` in the system's monospace bold.

## Decision

- The artwork is the source. Its path `fish` is the one copy of the
  outline: the app's build turns it into the string resource `mark_path`
  (vector drawables take it as `pathData`, Compose parses it), and DeKoi
  Setup bundles the SVG and reads the path at start.
- The artwork's styling, in the units of the outline (fill, edge width,
  each blur's sigma and colour), is in `DesignTokens`, so both apps draw the
  same mark at any size. The guide's limit stays: the glow from 48 dp, flat
  below.
- Android draws the blurs as shadow layers: `Paint.setShadowLayer` is
  hardware accelerated for paths from API 28, `setMaskFilter` never is
  (Android hardware acceleration guide). DeKoi Setup uses Skia's blur mask
  filter.
- The status bar icon, the themed launcher layer and the widget's mark use
  the same outline. DeKoi Setup's window shows the app icon itself.
- Neither app needs JetBrains Mono ExtraBold any more; both drop it.

## Consequences

- A change to the artwork's path reaches every mark at the next build. The
  frames of `ic_notification.xml` and `ic_mark.xml` assume the mark's place
  in the icon's 1024 units, as the launcher layers do.
- The launcher icon keeps its two documented differences from the artwork
  (`clients/app/src/main/res/README.md`): no glowing edge, since the
  launcher picks the shape, and no scan lines.
- DeKoi Setup's installer and Start menu shortcut still show the default
  icon; that needs an `.ico` file.
