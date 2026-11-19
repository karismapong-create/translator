# 부엉이 통역 — ground rules

## Icons (그라운드룰)
- Every icon in the app must look like the app's existing icons: Material Symbols (outlined,
  rounded, weight 400) vector drawables in `app/src/main/res/drawable/ic_*.xml`, 960×960 viewport,
  single color (tinted at runtime).
- Texts use an emoji as a placeholder; `Ui.kt` draws it as the matching vector icon (at the start
  of a label or inside it). Any new emoji in a string needs an entry in the `Ui.kt` icon map and,
  if no fitting icon exists, a new vector in the same style. Never ship a colour emoji or a
  hand-drawn icon in a different style.
- Toasts and notifications can't show icons: pass their text through `Ui.plain()`.
- CI enforces this: `tools/check_icons.py` fails the build on an emoji without an app icon.
  Allowed as plain text: language flags and sentence arrows (→ ← ↔ ↑ ↓ · • … ▸).
