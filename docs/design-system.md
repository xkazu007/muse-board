# Muse Board design system

Direction: **Focus, dark and yellow**. One task, shown as its first step,
one big Done. Near-black surfaces, soft dark-grey cards with a subtle
border, and one warm yellow accent used sparingly (the Done circle, the
+ button, the selected day, a few labels). Light mode exists (theme
toggle, or the phone in light mode) with the same structure on warm white.
Minimal means fewer things on screen, not smaller things.

## Colour tokens

Same names everywhere: `--name` in `web/styles.css`, `@color/mb_name` on
Android (`values/` light, `values-night/` dark). The widget uses the same
tokens and follows the launcher's light / dark.

| Token | Use | Dark (main) | Light |
|---|---|---|---|
| `page` | App / web background | `#0E0E10` | `#F7F6F2` |
| `surface` | Cards, widget, sheets | `#1B1B1F` | `#FFFFFF` |
| `inset` | "Then" box, chips, tonal pills | `#27272D` | `#EFEDE6` |
| `border` | 1px card / widget outline | `#2E2E35` | `#E4E2DA` |
| `on-surface` | Main text, the step | `#F4F4F5` | `#18181B` |
| `muted` | Meta line, task title, times | `#A1A1AA` | `#57534E` |
| `primary` | The yellow as a **fill**: Done, +, selected day, primary buttons | `#F5C542` | `#F5C542` |
| `on-primary` | Text / check on `primary` | `#1F1800` | `#1F1800` |
| `accent` | The yellow as **text or a thin line**: brand, links, Undo, the empty Done ring, today | `#F5C542` | `#8A6200` |

`primary` and `accent` are split because yellow text on white is
unreadable: in light mode the accent becomes a deep amber.

Every text pair passes WCAG AA (4.5:1); the lowest is `accent` on `inset`,
light, at 4.68:1. Check any new pair before using it.

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

- Corner radius: the launcher's own for the widget on Android 12+
  (`system_app_widget_background_radius`, inner list uses
  `system_app_widget_inner_radius`), 24dp / 16dp before that. App cards
  28dp, rows 20dp; web 28px / 20px. Cards and the widget carry a 1px
  `border`.
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

- **Top bar**: "Muse Board" in `accent`, then Calendar, History and Theme
  icons (48dp). Under it a big bold **Today** (34sp) and the date.
- **+ button**: a 64dp yellow circle, bottom centre, opens quick-add.
- **Calendar** (top bar): month grid with a dot on days that have tasks
  (plan date, due date as fallback), the selected day in a yellow circle,
  today in `accent`; tap a day for its tasks. Week view lists the week's
  days that have tasks. Tap a task for its detail page.
- **Groups**: an optional short `group` per task. When any task has one,
  the Then list shows group headings (ungrouped last, under "Tasks"); with
  2+ groups, All / group chips filter Then and Later. The widget ignores
  groups.

- **Quick-add** (bottom sheet, from the + button):
  title, first step (both required, the 2-minute rule written under the
  field), When (the phone's own date and time pickers) and Where. Adds to
  the end of the queue.
- **Task detail page** (tap any task card): big editable title, first
  step, Muse's blocks (below), notes (long ones folded to 4 lines with "See more"), then When /
  Where / Link / Group / Due rows (tap to pick, × to clear), a Links section
  for links found in the notes, Done (or Undo) and Later at the bottom. Everything
  saves on its own: "Saving…" / "Saved" at the top right.
- **Blocks** (Muse writes them, `blocks` in the API): under the first
  step on the detail page, and behind "More" on the web's focus card.
  Heading (18sp bold), text (15sp, links in `accent`), button (52dp
  tonal pill with a link icon, opens the link), check (native checkbox in
  `accent`; ticked items fade to 55% and strike through; the whole row is
  the 48dp target), toggle (chevron that turns 90°, body indented under
  it). No filled yellow: Done stays the only primary action.
- **Too big?** A small tonal chip on a task snoozed twice since its step
  last changed. Opens the detail page with the first step focused and "Try
  a first step that takes under 2 minutes." Neutral copy, never red.
- **This week**: under Done today, "This week · N done" with earlier days'
  completions grouped by day. A count of what moved, never what didn't: no
  streaks, no misses.
- **Theme toggle** (web header and app header): Auto (follows the device) →
  Light → Dark, remembered. The app uses the system's per-app night mode on
  Android 12+; the widget always follows the launcher.
- **Go-to link**: when a task has a `url`, a small "Open ↗" (widget: on the
  Then line; app: a chip on the focus card and a link icon on rows; web: a
  chip and a ↗ on rows). Secondary, never bigger than the step.
- **History** (top bar): every completed task by day, newest first.
  Swipe right or long-press to reopen.

## Feel (perceived speed)

- Under 0.1 s every tap visibly does something; that is what "instant"
  feels like (Nielsen's response-time limits). Updates are optimistic; the
  network catches up.
- Screens open with data: cached board and tasks, or the task handed over.
  No blank screens, no spinners for normal use.
- Offline is a quiet line ("Showing your last update…"), never an error.
- Refreshes that change nothing redraw nothing.
- Back uses predictive back (opted in).

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
meets Google's widget quality tiers' baseline: 4×2 support, accurate picker
preview (`widget_preview.xml`), instant update after actions, system corner
radius. (The top tier also asks for wallpaper-based colour; the board
chose its own dark-and-yellow identity instead.)

## Copy

- Short, neutral, no exclamation marks, no guilt. "All clear." beats
  "Great job!!"; nothing beats "Still waiting on you".
- Times are short and relative: `21:00`, `Tomorrow 09:00`, `Fri 12:00`,
  `28 Nov`.
- The step starts with a verb.
