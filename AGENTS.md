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
| `due_at` | The real deadline, if there is one. Shown only when there is no plan. Same time format. |
| `detail` | Optional notes, up to 4000 characters. Shown on the app's detail page; links in it become tappable there. |
| `position` | Queue order, lower comes first. The lowest open one is the focus. |

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
- **The owner adds tasks too.** The app has a quick-add (title + first step,
  optional when / where) and a detail page to edit any task. Tasks added
  from the phone land at the end of the queue: give them a plan, a place or
  a better position when you see them.
- **Times are local.** Send date-times without an offset and the API reads
  them as Casablanca time. Morocco has been on UTC+0 all year since
  2026-09-20, so `+01:00` is wrong.

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
  "due_at": "2026-10-30T12:00"
}'

# Make it the focus, or fix a step or time
curl -s -H "$AUTH" -H 'Content-Type: application/json' -X PATCH $B/api/tasks/12 \
  -d '{"position": 0, "first_step": "Open the doc and write one sentence"}'

# Undo a Done the owner didn't mean
curl -s -H "$AUTH" -X POST $B/api/tasks/12/reopen
```

### API

| Call | Does |
|---|---|
| `GET /api/board` | `{ date, focus, next, upcoming[≤3], plans[], doneToday }`, exactly what the widget renders |
| `GET /api/tasks` | Every task, by position |
| `GET /api/tasks/:id` | One task |
| `POST /api/tasks` | Create. `title` required; any field above |
| `PATCH /api/tasks/:id` | Edit any field above. `null` clears an optional one. A new `first_step` resets `snooze_count` |
| `POST /api/tasks/:id` | Same as PATCH (for clients that can't send PATCH, like the Android app) |
| `POST /api/tasks/:id/complete` | Done |
| `POST /api/tasks/:id/reopen` | Undo a Done |
| `POST /api/tasks/:id/snooze` | Later. `{ "until": "<date-time, under 14 days>" }` hides it until then and moves `plan_at` there. No body: until tomorrow 09:00 |
| `GET /health` | No auth |

Errors: 400 with `{ error }` for bad input (blank title, too long, bad
date-time), 401 for a bad token, 404 for an unknown id or wrong state.

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
  before the network call (`WidgetUpdater.showOptimistic`). Keep any new
  action this fast.
- **Version** lives in `android/gradle.properties`. Bump `versionCode` for
  every APK the owner will install.
- **Design** comes from `docs/design-system.md`: colour tokens, sizes,
  copy rules. Web and Android share the same tokens.

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
