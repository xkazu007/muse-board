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
- `POST /api/tasks/:id/complete` — mark done
- `POST /api/tasks/:id/snooze` — "not now": hidden from focus until tomorrow

"Today" is computed in Africa/Casablanca time. The database seeds itself
with the real task list on first boot when the table is empty.

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

Build a sideloadable release APK — two ways, same code:

```
cd android
./gradlew assembleRelease          # normal machine (needs Gradle)
# -> app/build/outputs/apk/release/app-release.apk

./build-manual.sh                  # no Gradle: aapt2 + kotlinc + d8
# -> android/muse-board-release.apk (release-signed when the keystore
#    at ~/.muse-board-keystore/ exists, debug-signed otherwise)
```

The manual build exists because the Gradle daemon talks to its client
over loopback TCP, which the sandbox on this VM denies for Java
processes — Gradle cannot run here at all.

First run on the phone: paste the board token once (stored in plain
SharedPreferences, private to the app). Then add the widget: long-press
the home screen → Widgets → Muse Board.
