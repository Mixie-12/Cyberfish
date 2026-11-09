# site

The public site, [dekoi.sysnordic.no](https://dekoi.sysnordic.no): the
CyberFish landing page (a three.js scene, one chapter per section) and the
download page for the app and DeKoi Setup. Static, on GitHub Pages, built
and published by `.github/workflows/site.yml` (ADR 0039).

| Path | What it is |
|---|---|
| `index.html`, `src/` | The landing page; `src/scene/` the three.js scene, `src/ui/` loader, sound and CSS |
| `download/index.html`, `src/ui/download.css` | The download page: no script, no scene |
| `public/` | Models, sounds and the CyberFish mark, shipped as they are |
| `BRAND.md` | Colours, type, voice and product facts every page follows |

Imported from
[nevermindmae/cyberfish-landing-page](https://github.com/nevermindmae/cyberfish-landing-page)
at f9b1fc8. The Blender sources and build scripts for the models, the
original sound files and the visual references stay there.

## Build and run

```bash
npm ci --ignore-scripts
npm run dev
```

The dev server is at http://localhost:5173. `npm run build` needs the
release values `site.yml` sets (`VITE_VERSION`, `VITE_APK_SHA256`,
`VITE_APK_MB`, `VITE_MSI_SHA256`, `VITE_MSI_MB`, `VITE_CERT_SHA256`,
`VITE_SITE_HOST`) and stops without them. `site.yml` then adds the APK, the
MSI and the QR code to `dist/download/`.

## Credits

- Models: generated in Blender by team CyberFish; concept imagery from
  Midjourney.
- Sound effects: ElevenLabs libraries.
- Music: *Digital Horizons*, licensed from premiumbeat.com.
- Fonts: Michroma and JetBrains Mono (SIL Open Font License), from Google
  Fonts.
