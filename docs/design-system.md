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
- **Done**: 60dp circle (64px web), `primary`. On Android 12+ (widget) and
  in the app it is a native CheckBox: an empty ring with a faint check,
  which fills and draws its check when tapped (animated-selector,
  `drawable/mb_check_button.xml`). Before 12 the widget shows the filled
  circle. The only filled element.
- **Later**: a tonal pill (40dp, `inset` fill, `on-surface` text), in the
  widget and the app. In the widget it never opens a popup:
  the widget itself swaps Done for three 44dp chips on `inset` (In 1 hour /
  Tonight / Tomorrow; Tonight only before 19:00) and Later becomes Back. The
  time is worked out when a chip is tapped. Tonight = 20:00, Tomorrow =
  09:00. The app shows the same choices in the bottom sheet.
- **Bottom sheet** (app: long-press menu and Later): `surface`, 28dp top
  corners, a drag handle, the step as title and the task under it, 56dp
  rows with a 24dp line icon and the time on the right ("20:00"). Slides up
  from the bottom.
- **Press feedback**: every tappable thing gets a contained ripple
  (`on-surface` at 12%) clipped to its own shape. Never the borderless
  ripple: it spills past small targets as a loose circle.
- **Then list** (widget at about 260dp tall and up, usually 4 rows, and on the web): up to 3
  rows, title left, short time right, on `inset`.
- **Feedback line**: replaces the Then line for 60 s after an action: "✓
  Done. 2 today." (with an **Undo**, 32dp, `primary`, for the same 60 s) /
  "Moved. Reminder at 21:00." / "Back on your board." / "Couldn't save that.
  Try again."
- **Then line when the queue is empty**: "Next: <task>, <time>" for the next
  snoozed task coming back, so "All clear." never hides what's coming.

## App screens

- **Quick-add** (bottom sheet, from "Add a task" under the Then list):
  title, first step (both required, the 2-minute rule written under the
  field), When (the phone's own date and time pickers) and Where. Adds to
  the end of the queue.
- **Task detail page** (tap any task card): big editable title, first step,
  When / Where / Due rows (tap to pick, × to clear), notes with each link
  as a tappable row, Done (or Undo) and Later at the bottom. Everything
  saves on its own: "Saving…" / "Saved" at the top right.
- **Too big?** A small tonal chip on a task snoozed twice since its step
  last changed. Opens the detail page with the first step focused and "Try
  a first step that takes under 2 minutes." Neutral copy, never red.
- **This week**: under Done today, "This week · N done" with earlier days'
  completions grouped by day. A count of what moved, never what didn't: no
  streaks, no misses.
- **Web theme toggle**: Auto (follows the device) → Light → Dark, remembered
  on that device, applied before the first paint.

## Native gestures

Android widgets can't take long-press (the launcher owns it) or swipe, on
Samsung too. So each gesture lives where Android allows it:

| Where | Gesture | Does |
|---|---|---|
| Widget | Tap the ring | Native tick animation, then Done |
| App icon | Long-press | Shortcuts: "Done: <first step>", "Later: 1 hour" |
| Reminder | Action buttons | Done, Later 1h |
| App | Swipe a card right / left | Done (primary behind it) / Later (asks when) |
| App | Long-press a card | Menu: Done, Later choices, or Undo |
| App | Tick the ring | Same as the widget |
| App | Tap a card | Opens the task's detail page |

Haptics: a tick when a swipe crosses its threshold (gesture threshold
haptics on Android 14+), a confirm on Done. Every Done in the app shows an
Undo bar for 6 s; on the widget the Undo stays for 60 s.

## Flow rules

- Every widget tap stays on the home screen. Only tapping the card body opens
  the app.
- Every tap changes the widget at once (from the cache); the network catches
  up after.
- Every action that removes a task from view can be undone or says when it
  comes back.

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
