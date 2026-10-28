# Muse Board design system

Direction: **Focus + Material You**. One task, shown as its first step, one
big Done. Colours come from the owner's wallpaper on Android 12+, with a
fixed blue palette everywhere else. Minimal means fewer things on screen,
not smaller things.

## Colour tokens

Same names everywhere: `--name` in `web/styles.css`, `@color/mb_name` on
Android.

| Token | Use | Light (static) | Dark (static) | Android 12+ light | Android 12+ dark |
|---|---|---|---|---|---|
| `surface` | Widget / card background | `#dae2ff` | `#23314f` | `system_accent1_100` | `system_accent1_800` |
| `on-surface` | Main text, the step | `#001a41` | `#dae2ff` | `system_accent1_900` | `system_accent1_50` |
| `muted` | Meta line, task title, times, Later | `#3a4a6b` | `#b0bfe3` | `system_accent2_700` | `system_accent2_200` |
| `primary` | Done button | `#2b5bb5` | `#b0c6ff` | `system_accent1_600` | `system_accent1_200` |
| `on-primary` | The check mark | `#ffffff` | `#002d6e` | `system_accent1_0` | `system_accent1_800` |
| `inset` | The "Then" list box | `#c6d3fa` | `#1a2540` | `system_accent2_100` | `system_accent2_900` |

Web only: `page` `#f9f9ff` / `#111318`, `secondary` `#b9c8f2` / `#34466b`
(Later chips), `error` `#ba1a1a` / `#ffb4ab`.

Every text pair above passes WCAG AA (4.5:1); the lowest is `muted` on
`inset`, light, at 5.9:1. Check any new pair before using it.

## Type

System font (Roboto / One UI Sans on phones). Five sizes only:

| Role | Size | Weight |
|---|---|---|
| The step (focus) | 21sp widget, 26px web | Bold |
| List rows | 14sp | Regular |
| Task title under the step | 13sp | Regular |
| "Then:" / feedback line | 12sp | Regular |
| Meta line ("NOW · 21:00 · DESK"), count | 11sp, caps, 0.08em tracking | Regular |

## Shape and space

- Corner radius: the launcher's own on Android 12+
  (`system_app_widget_background_radius`, inner list uses
  `system_app_widget_inner_radius`); 24dp / 16dp before that; 28px / 20px
  on the web.
- Padding 16dp inside the widget. Gaps: 4dp under the step, 8dp before the
  Then line, 12 to 14dp between blocks.
- No borders, dividers, bullets or section labels in the widget. Space and
  colour do the grouping.

## Components

- **Focus block**: meta line (`Now · <plan time or "by" deadline> · <where>`)
  + done count on the right, the step (max 2 lines, so a 4×2 never clips), the task title (1
  line), the Then line (1 line).
- **Done**: 60dp circle (64px web), `primary`, white check. The only filled
  element.
- **Later**: plain text button, `muted`, 44dp tall. Opens the time chooser
  (in 1 hour / tonight 20:00 before 19:00 / tomorrow 09:00).
- **Then list** (widget at about 260dp tall and up, usually 4 rows, and on the web): up to 3
  rows, title left, short time right, on `inset`.
- **Feedback line**: replaces the Then line for 60 s after an action: "✓
  Done. 2 today." / "Moved. Reminder at 21:00." / "Couldn't save that. Try
  again."

## Sizes (Android)

Default 4×2 (the Focus layout). Resizable from 3×2 to 4×4; from about 260dp
tall (usually 4 rows) the Then list appears (Android 12+ picks the layout per size). This
meets Google's widget quality tiers: 4×2 support, accurate picker preview
(`widget_preview.xml`), instant update after actions, dynamic colour,
system corner radius.

## Copy

- Short, neutral, no exclamation marks, no guilt. "All clear." beats
  "Great job!!"; nothing beats "Still waiting on you".
- Times are short and relative: `21:00`, `Tomorrow 09:00`, `Fri 12:00`,
  `28 Nov`.
- The step starts with a verb.
