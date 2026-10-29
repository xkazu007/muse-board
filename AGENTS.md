# AGENTS.md

Read this first if you are an agent working on Muse Board: Muse writing the
owner's tasks, or a coding agent changing this repo. Humans are welcome too.

Muse Board puts ONE task on the owner's home screen as a two-minute first
step, with a big Done button. It exists to beat procrastination, and every
choice below comes from the research in `docs/research.md`. If a change
would add pressure, guilt, choice or clutter, it is probably the wrong
change.

```
api/      Node + Express + Postgres. The only source of truth. Also serves web/.
web/      Small web app, same board. No build step.
android/  Kotlin app + home-screen widget. Framework APIs only, no AndroidX.
docs/     design-system.md (look and copy), research.md (why it works this way)
```

Live at `https://board.mourid.tech`. Every `/api/*` call needs
`Authorization: Bearer <BOARD_TOKEN>`. The token lives in the deploy's env
vars and on the owner's devices. Never print it, commit it or put it in a
task.

---

## Part 1: for Muse (writing tasks)

You decide what the owner sees. The widget shows the **focus** (the open
task with the lowest `position`) as its `first_step` in large text, the task
title small under it, and a "Then:" line with the next one. Write tasks so
that glance is enough to start.

### The fields

| Field | Rule |
|---|---|
| `title` | What the task is, short. "Watch the 7-min ML video". |
| `first_step` | **Always set it.** The first physical action, doable in under 2 minutes, starting with a verb. "Open Coursera and press play". Max 140 characters, aim for under 40. No prerequisites hidden inside ("research X first" is a task, not a step). |
| `plan_at` | **When** they will start. Set it for every task meant for today or tomorrow. The phone sends one reminder at this time. Local Casablanca time, no offset: `"2026-10-29T21:00"`. |
| `plan_where` | **Where**, 1 to 3 words: "desk", "library", "bed". Max 40 characters. |
| `due_at` | The real deadline, if there is one. Same time format. Shows as a coloured chip once it's under a week away: neutral, then yellow (tomorrow), orange (today), deep orange once passed ("Was due…", never red). Only set real deadlines, or every task looks urgent. |
| `detail` | Optional notes, up to 4000 characters. Shown on the app's detail page; links in it become tappable there. |
| `position` | Queue order, lower comes first. The lowest open one is the focus. |
| `group` | Optional short label, up to 40 characters: "Coursera ML", "Manara", "NutriSynth", "Personal". The app groups its list by it and can filter to one group; the widget ignores it. Set it when the task clearly belongs to a stream of work. |
| `blocks` | What the task's page shows under the first step: an ordered list of text, headings, buttons, checklist items and toggles (see "Blocks" below). Up to 40. The app's detail page and the web's "More" show them; the widget ignores them. |
| `estimate_min` | How long the **whole task** realistically takes, in minutes (1 to 1440). Shown as a "~15 min" chip whose colour moves from green (quick, up to 15) through blue and violet to pink (over 90) on the widget, the app and the web. Set it when you can judge it; be honest rather than small, a true "~45 min" is easier to plan for than a hopeful "~10 min". `null` clears it. |
| `url` | Go-to link: where the first step happens (the lesson, the Zoom room, the doc). `http://` or `https://` only. Shows as a small "Open" next to the task on the widget, the app and the web; the first step stays the big text. Set it whenever you know the link. |

`plan_at` + `plan_where` together are an implementation intention ("at 21:00
at my desk I open Coursera"), the best-supported technique in the research
(d = 0.65 over 94 studies).

### How to write them

- **One focus.** Order the queue so the right thing is at the lowest
  position. Keep the open list short; 7 or fewer is a good ceiling. A long
  list is a different problem: talk to the owner instead.
- **Steps, not projects.** "SA project" is a title. Its first step is "Open
  the project brief and read the first page". When a step gets done, the
  next step usually deserves its own task, or a PATCH to `first_step`.
- **Concrete times.** "Tonight" is not a plan; `21:00` is. Pick times the
  owner actually has.
- **Neutral tone.** No guilt, no "still waiting on you", no exclamation
  marks. Self-forgiveness predicts less procrastination next time; shame
  predicts more.
- **Don't lean on deadlines.** The well-known study saying self-set
  deadlines help (Ariely & Wertenbroch 2002) was retracted in 2026 and
  failed to replicate. Plans and first steps do the work.
- **One reminder per plan.** The app already fires it at `plan_at`. Don't
  ask for more alerts; frequent alerts get ignored.
- **"Later" is data.** When the owner taps Later, they pick a time (in 1
  hour, tonight 20:00, tomorrow 09:00). The API hides the task until then
  and moves `plan_at` there. If the same task keeps getting moved, the first
  step is probably too big or the task is unclear: rewrite it smaller, or
  ask. Every task carries `snooze_count` (Laters since its first step last
  changed); at 2 the app shows "Too big? Make it smaller", and writing a new
  `first_step` resets it to 0.
- **The owner reorders and switches.** They can drag tasks into a new
  order and tap "Do this now" on any task: it becomes the focus and the
  current one is paused, next in line (`focused` / `reordered` in the
  feed). Their order wins; if a new order looks wrong, suggest, don't
  silently undo it. They can also add, rename and delete checklist steps.
- **The owner adds tasks too.** The app has a quick-add (title + first step,
  optional when / where) and a detail page to edit any task. Tasks added
  from the phone land at the end of the queue: give them a plan, a place or
  a better position when you see them.
- **Times are local.** Send date-times without an offset and the API reads
  them as Casablanca time. Morocco has been on UTC+0 all year since
  2026-09-20, so `+01:00` is wrong.

### Blocks: the task's page

`blocks` lets you lay out a task's page: what to read, what to click, what to
tick. Each block is an object with a `type`:

| Type | Fields | Shows as |
|---|---|---|
| `heading` | `text` (≤ 80) | A bold line that starts a section |
| `text` | `text` (≤ 2000) | A paragraph; links in it are tappable |
| `button` | `label` (≤ 40), `url` (http/https) | A full-width button that opens the link |
| `check` | `text` (≤ 200), `done` (true/false, default false) | A checklist item the owner ticks; the tick is saved |
| `toggle` | `text` (≤ 120), `body` (≤ 2000) | A line that expands to show `body` when tapped |

How to use them:

- **The first step stays the main thing.** Blocks are for after it: the
  next small moves, the links, the context. Don't repeat the first step.
- **Checks are small steps** (the board's subtasks), each under 2 minutes,
  3 to 7 of them. The app shows progress as "✓ 2/5" on the task. When
  they're all ticked, the task is probably done or needs a new first step.
  Prefer checks over "1a / 1b" tasks; a step that needs its own time and
  reminder is its own task, in the same `group`.
- **Buttons are destinations**: "Open lesson 2", "Join Zoom", "Open the
  brief". One per place, labels that start with a verb. The task's `url`
  is still its main link; a button for the same place is redundant.
- **Toggles hold what's useful but not needed to start**: why it matters,
  background, a long how-to. Keeps the page short.
- **Sending `blocks` replaces the whole list.** Read the task first and
  keep the owner's ticks (`done`) on items you keep. `null` or `[]` clears.
- Unknown types are skipped by the app, so new kinds can be added later
  without breaking old installs.

```json
"blocks": [
  { "type": "button", "label": "Open lesson 2", "url": "https://www.coursera.org/learn/machine-learning" },
  { "type": "check", "text": "Watch the first 3 minutes" },
  { "type": "check", "text": "Write one line about what regression is" },
  { "type": "toggle", "text": "Why this one first", "body": "It is 7 minutes and unlocks the week 2 quiz." }
]
```

### Keeping up: the activity feed and your task notes

The board logs every change and who made it. Read it to keep your own
context current, so you know what the owner did, not just what the board
looks like now.

- **Say who you are.** Send `X-Board-Client: muse` on every call. The app
  sends `app`, the web `web`; the feed records it.
- **Read what changed.** `GET /api/activity?since=<last id you saw>&exclude=muse`
  returns events oldest first, each `{ id, at, task_id, task_title, kind,
  source, data }`, plus `last_id` to remember for next time. Kinds:
  `created` (`data.fields`), `updated` (`data.changes`: field → new value;
  for checklists `data.ticked` / `data.unticked` item texts, and
  `blocks_edited`), `completed`, `reopened`, `snoozed` (`until`,
  `snooze_count`), `focused` ("Do this now"; `paused_task_id`),
  `reordered` (`order`: open task ids), `suggestion_applied`,
  `suggestion_dismissed`. Kept 180
  days.
- **Keep one Markdown file per task** in your own workspace (for example
  `tasks/<id>.md`): what it's for, links, what the owner did and wrote in
  its notes, decisions, what got in the way. Update it from the feed. The
  board stores the task; the file is your memory of it.
- **Notice patterns, then suggest.** Snoozed again and again, every check
  ticked but the task still open, notes that say what's next: those are
  moments for a suggestion (below), not a silent rewrite.

### Suggestions: propose, the owner decides

When a change isn't clearly what the owner asked for, suggest it instead
of making it. The app and web show the oldest pending suggestion as a card
under the focus, with **Apply** and **Not now**.

```bash
curl -s -H "$AUTH" -H "X-Board-Client: muse" -H 'Content-Type: application/json' \
  -X POST $B/api/suggestions -d '{
  "title": "Make the NutriSynth step smaller",
  "detail": "It moved twice; a smaller start might help.",
  "task_id": 3,
  "changes": { "first_step": "Open the doc and write one question", "estimate_min": 10 },
  "new_tasks": [ { "title": "NutriSynth: send the questions", "first_step": "Open the chat with Manal" } ]
}'
```

- `title` (≤ 120) is the card's headline, `detail` (≤ 500) one line of
  why. `changes` (any task fields, with `task_id`) and / or `new_tasks`
  (1 to 5 new tasks) are what **Apply** does, in one go. The card lists
  each change for the owner ("First step: …", "New: …").
- **One at a time, rarely.** Only the oldest pending one shows. Don't
  queue several; a stack of proposals is pressure. A day or more apart is
  a good rhythm unless asked.
- Neutral and specific, like the rest: "Plan it for 21:00 at your desk?"
  beats "You keep putting this off".
- A dismissed suggestion is an answer. Don't re-send it; note it in the
  task's file.
- Things the owner asked for directly, just do; no suggestion needed.

### Recipes

```bash
B=https://board.mourid.tech
AUTH="Authorization: Bearer $BOARD_TOKEN"

# See what the owner sees
curl -s -H "$AUTH" $B/api/board

# Add a task (lands at the end of the queue unless you pass position)
curl -s -H "$AUTH" -H 'Content-Type: application/json' -X POST $B/api/tasks -d '{
  "title": "NutriSynth prep",
  "first_step": "Write 3 questions for Manal",
  "plan_at": "2026-10-29T18:00",
  "plan_where": "desk",
  "estimate_min": 20,
  "due_at": "2026-10-30T12:00"
}'

# Make it the focus, or fix a step or time
curl -s -H "$AUTH" -H 'Content-Type: application/json' -X PATCH $B/api/tasks/12 \
  -d '{"position": 0, "first_step": "Open the doc and write one sentence"}'

# Lay out its page (replaces any blocks it had)
curl -s -H "$AUTH" -H 'Content-Type: application/json' -X PATCH $B/api/tasks/12 -d '{"blocks": [
  {"type": "button", "label": "Open the brief", "url": "https://docs.example.com/brief"},
  {"type": "check", "text": "Read the first page"},
  {"type": "check", "text": "Write 3 questions"}
]}'

# Undo a Done the owner didn't mean
curl -s -H "$AUTH" -X POST $B/api/tasks/12/reopen
```

### API

| Call | Does |
|---|---|
| `GET /api/board` | `{ date, focus, next, upcoming[≤3], plans[], doneToday, suggestion }`, what the widget and app render (`upcoming`: the next three in queue order; `suggestion`: the oldest pending one, or null) |
| `GET /api/tasks` | Every task, by position |
| `GET /api/tasks/:id` | One task |
| `POST /api/tasks` | Create. `title` required; any field above |
| `PATCH /api/tasks/:id` | Edit any field above. `null` clears an optional one. A new `first_step` resets `snooze_count` |
| `POST /api/tasks/:id` | Same as PATCH (for clients that can't send PATCH, like the Android app) |
| `POST /api/tasks/:id/complete` | Done |
| `POST /api/tasks/:id/reopen` | Undo a Done |
| `POST /api/tasks/:id/focus` | "Do this now": it becomes the focus (back from Later if snoozed); the old focus is paused, next in line |
| `POST /api/tasks/order` | `{ "ids": [...] }`: the queue in this order (open tasks; any not listed keep their order after) |
| `POST /api/tasks/:id/snooze` | Later. `{ "until": "<date-time, under 14 days>" }` hides it until then and moves `plan_at` there. No body: until tomorrow 09:00 |
| `GET /api/activity` | What changed, oldest first. `?since=<id>`, `?exclude=muse`, `?limit=` (≤ 500). `{ events, last_id }` |
| `POST /api/suggestions` | Suggest a change: `title`, `detail`, `task_id` + `changes`, and / or `new_tasks` |
| `GET /api/suggestions` | `?status=pending` (default), `applied`, `dismissed`, `all` |
| `POST /api/suggestions/:id/apply` | Apply it (the app's Apply) |
| `POST /api/suggestions/:id/dismiss` | Dismiss it (the app's Not now) |
| `GET /health` | No auth |

Errors: 400 with `{ error }` for bad input (blank title, too long, bad
date-time, estimate outside 1 to 1440), 401 for a bad token, 404 for an unknown id or wrong state.

---

## Part 2: for coding agents (changing the repo)

The owner is happy for the code to be rewritten freely. Keep the behaviour
and the rules below.

### Ground rules

- **Postgres owns time.** Day boundaries and offset-less date-times are
  resolved by Postgres (session `TimeZone=Africa/Casablanca`). Don't compute
  "today" with Node's `Intl`: its bundled tz data lags behind real changes.
- **Android: framework APIs only.** No AndroidX, no Glance, no Compose. The
  app must build with `android/build-manual.sh` (aapt2 + kotlinc + d8), which
  has no dependency resolution. Gradle builds the same code.
- **Widget layouts** (`res/layout*/widget_*.xml`) may only use RemoteViews
  classes (LinearLayout, FrameLayout, TextView, ImageView, Button, ...). A
  plain `<View>` builds fine and then fails on the phone with "Couldn't add
  widget". CheckBox / Switch / RadioButton only in `layout-v31` (Android
  12+). `build-manual.sh` enforces both.
- **Two widget layouts**: `layout/widget_focus.xml` and its Android 12+
  twin `layout-v31/widget_focus.xml` (Done is a native CheckBox there).
  Change both.
- **Gestures**: widgets can't take long-press or swipe. Those live in the
  app (`SwipeRow`, long-press menus), the icon shortcuts (`ShortcutSync`)
  and the reminder actions. See "Native gestures" in
  `docs/design-system.md`.
- **Every widget update resets every view** (`WidgetUpdater.baseViews`): the
  launcher applies updates on top of what is on screen, so anything you
  don't set keeps its old value.
- **Instant feedback.** Done and Later update the widget from `BoardCache`
  before the network call (`WidgetUpdater.showOptimistic`). The app does the
  same (`MainActivity.optimistic`), paints from the cached board and task
  list before fetching, fetches both in parallel, and redraws only when the
  data changed. The detail page opens with the task it was given. Keep any
  new screen or action this fast: never a blank screen waiting on the
  network, never a toast for being offline.
- **Touch targets** are 48dp (a smaller visible pill sits inside an inset
  drawable, e.g. `mb_pill_tonal_inset`).
- **Version** lives in `android/gradle.properties`. Bump `versionCode` for
  every APK the owner will install.
- **Design** comes from `docs/design-system.md`: colour tokens, sizes,
  copy rules. Web and Android share the same tokens.
- **Feel native.** Taps go through `View.onTap` (haptic tick) and tappable
  views get `animator/mb_press` (press-in); lists are cards of rows with
  hairline dividers (`newListCard` / `addCardRow`); no small caps labels.
  Plus Jakarta Sans comes from the theme; widget TextViews set it themselves.

### Run and check

```bash
# API: needs a Postgres
cd api && npm ci
BOARD_TOKEN=dev DATABASE_URL=postgres://... PORT=8080 node server.js
# then curl the recipes above against http://localhost:8080

# Web: served by the API from api/web (the Docker image copies web/ there;
# locally: ln -s ../web api/web)

# Android APK (JDK 17+, Android SDK platform 34 + build-tools 34.0.0)
ANDROID_HOME=~/Android ./android/build-manual.sh
# -> android/muse-board-release.apk
```

There is no test suite yet. Before pushing: `node --check api/server.js`,
exercise the endpoints you touched with curl, build the APK, and click
through the web app.

### Deploy

Push to `main` and Dokploy redeploys the API and web app. The APK is
sideloaded: build it with the release keystore (`~/.muse-board-keystore/`)
and install it over the old one.
