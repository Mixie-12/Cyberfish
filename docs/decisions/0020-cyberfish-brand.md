# 0020: The CyberFish brand in the apps

Status: accepted, 2026-11-07. Changes the tokens and type of ADR 0011.

## Context

The owner added the CyberFish aesthetic guide (`UX/UI/CyberFish-aesthetic.md`)
with its fonts: dark and calm, CyberFish blue as an accent only, Michroma for
wordmarks and headings, JetBrains Mono for everything else, hairlines instead
of boxes, a HUD frame and a horizon motif. `docs/design.md` followed Apple
and Material defaults (light and dark themes, system font, cards).

## Decision

- The guide wins where the two differ. The apps are dark only, with the
  guide's palette (background #060A14, navy #0B1636, blue #2EA8FF, white
  #F2F4FA, text #C9D1E3, mid grey #8A94AB, dim grey #4A5368, hairline
  #1B2234). Blue stays under about a tenth of a screen and is never a fill:
  primary actions are outlined or lit, not solid blue.
- Severity keeps its own colours, tuned for the navy background and checked
  for contrast; they are the only other colours.
- Fonts are bundled (both OFL-1.1, licence texts shipped and listed in the
  app's licences): Michroma for the DeKoi wordmark, screen titles and
  section numbers, uppercase with slight tracking; JetBrains Mono 400, 500
  and 800 for all other text; labels small, uppercase, widely tracked
  ("01 / NETWORK").
- Structure by hairlines and space; the HUD frame (thin blue line, 45-degree
  cut corners) marks hero areas; the horizon (glowing line fading into lines
  below) carries sign-in, onboarding and empty states; the `><>` mark is the
  logo.
- `docs/design.md` keeps its screens, behaviour, alert template and
  accessibility rules; its token, type and card sections now point here.

## Consequences

- Body text in a monospace font is wider; layouts are checked at 200 % font
  scale.
- DeKoi Setup shares the palette through `clients/shared` and moves with it.
- About 400 KB of fonts in the app.
