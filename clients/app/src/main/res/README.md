# Resources

This note sits here because the resource merger accepts only resource
files inside `drawable/`.

In `drawable/`, `ic_launcher_foreground.xml` and `ic_notification.xml` are
the DeKoi koi mark. Every other `ic_*.xml` is a Material Symbol
(docs/design.md, "Libraries"):

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
