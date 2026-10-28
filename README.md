# Muse Board

Native Android home-screen widget + backend for Zakaria's Muse-managed task list.

**Purpose:** beat procrastination. The widget shows ONE "do this now" task big
at the top, max 3 "up next" items with dates, and a "done today" count.
Dark theme. After 20:00, if the day's task isn't done, the widget shows
"Still waiting on you" (the 20:00 chat nudge itself comes from Muse).

## Layout

```
muse-board/
  api/        Node + Express + pg API (served on :80, also serves web/)
  web/        Companion web app (same board, for any browser)
  android/    Native Kotlin app + AppWidgetProvider (minSdk 26, targetSdk 34)
  Dockerfile  Builds the API image (node:22-alpine)
```

## API

All `/api/*` endpoints need `Authorization: Bearer <BOARD_TOKEN>`.

- `GET /health` — `{ ok: true }` (no auth)
- `GET /api/board` — `{ date, focus, upcoming[<=3], doneToday }`
- `GET /api/tasks` — full task list
- `POST /api/tasks` — `{ title, detail?, due_at? }`
- `PATCH /api/tasks/:id` — `{ title?, detail?, due_at? }` edit a task
  (`null` clears `detail` / `due_at`)
- `POST /api/tasks/:id/complete` — mark done
- `POST /api/tasks/:id/reopen` — undo a "done"
- `POST /api/tasks/:id/snooze` — "not now": hidden from focus until tomorrow

"Today" is computed in Africa/Casablanca time, by Postgres. A `due_at`
without a UTC offset (e.g. `2026-10-30T12:00`) is read as Casablanca local
time — prefer that over writing an offset by hand. Morocco has been on
UTC+0 all year since 2026-09-20 (tz data 2026e), so `+01:00` is now wrong.
Bad input (blank title, unreadable `due_at`) gets a 400. The database
seeds itself with the real task list on first boot when the table is
empty.

## Deploy

Live at https://board.mourid.tech (Dokploy app `muse-board`, Postgres
`muse-board-db`, private org). Env vars on the Dokploy app:

- `BOARD_TOKEN` — single bearer token for the API
- `DATABASE_URL` — internal Postgres connection string
- `PORT` — 80

Redeploys automatically on push to `main` (Dokploy auto-deploy).

## Android app

Native Kotlin app + AppWidgetProvider (minSdk 26, targetSdk 34). The code
uses framework APIs only (plus the Kotlin stdlib and kotlinx-coroutines) —
no AndroidX libraries. That means: `HttpURLConnection` instead of OkHttp,
`AlarmManager` (30-min tick, re-armed on boot) instead of WorkManager, a
plain `Activity` + `LinearLayout` list instead of AppCompat/RecyclerView,
and plain `SharedPreferences` for the board token (v1 — see
`TokenStore.kt`).

The app version lives in `android/gradle.properties`
(`museboard.versionCode` / `museboard.versionName`); both builds read it.
Bump `versionCode` for every APK you install, or Android refuses the update.

Build a sideloadable release APK — two ways, same code:

```
cd android
./gradlew assembleRelease          # normal machine (needs Gradle)
# -> app/build/outputs/apk/release/app-release.apk

./build-manual.sh                  # no Gradle: aapt2 + kotlinc + d8
# -> android/muse-board-release.apk (release-signed when the keystore
#    at ~/.muse-board-keystore/ exists, debug-signed otherwise)
```

`build-manual.sh` also rejects widget layouts (`res/layout/widget_*.xml`)
that use a view class home-screen widgets can't load (a plain `<View>`,
`<Space>`, `androidx.*`, …) — those build fine but fail on the phone with
"Couldn't add widget".

The manual build exists because the Gradle daemon talks to its client
over loopback TCP, which the sandbox on this VM denies for Java
processes — Gradle cannot run here at all.

First run on the phone: paste the board token once (stored in plain
SharedPreferences, private to the app). Then add the widget: long-press
the home screen → Widgets → Muse Board, or tap "Add widget to home screen"
in the app. A done task can be undone from the app's task list (or from
"Undo" right after tapping Done in the web app).
