# Muse Board

An Android home-screen widget (plus a small web app) that shows one task
as a two-minute first step, with one big Done. Tasks are written by Muse,
an agent; the owner just starts.

- **Agents (Muse, coding agents): read [`AGENTS.md`](AGENTS.md).** It has
  the task-writing rules, the API and the repo's ground rules.
- Look and copy: [`docs/design-system.md`](docs/design-system.md)
- Why it works this way: [`docs/research.md`](docs/research.md)

## Layout

```
api/        Node + Express + Postgres API (served on :80, also serves web/)
web/        Companion web app (same board, any browser)
android/    Native Kotlin app + home-screen widget (minSdk 26, targetSdk 34)
docs/       Design system and research notes
Dockerfile  Builds the API image (node:22-alpine)
```

## Deploy

Live at https://board.mourid.tech (Dokploy app `muse-board`, Postgres
`muse-board-db`, private org). Env vars on the Dokploy app:

- `BOARD_TOKEN`: single bearer token for the API
- `DATABASE_URL`: internal Postgres connection string
- `PORT`: 80

Redeploys automatically on push to `main` (Dokploy auto-deploy). The schema
migrates itself on start (new columns are added in place).

## Android app

Native Kotlin app + AppWidgetProvider. Framework APIs only (plus the Kotlin
stdlib and kotlinx-coroutines), no AndroidX: `HttpURLConnection`,
`AlarmManager` for the 30-minute refresh and the plan reminders,
`RemoteViews` for the widget, plain `SharedPreferences` for the token and
the board cache.

The version lives in `android/gradle.properties` (`museboard.versionCode` /
`museboard.versionName`); both builds read it. Bump `versionCode` for every
APK you install, or Android refuses the update.

Build a sideloadable release APK, two ways, same code:

```
cd android
./gradlew assembleRelease          # normal machine (needs Gradle)
# -> app/build/outputs/apk/release/app-release.apk

./build-manual.sh                  # no Gradle: aapt2 + kotlinc + d8
# -> android/muse-board-release.apk (release-signed when the keystore
#    at ~/.muse-board-keystore/ exists, debug-signed otherwise)
```

`build-manual.sh` rejects widget layouts that use a view class home-screen
widgets can't load (a plain `<View>`, `<Space>`, `androidx.*`, ...). Those
build fine but fail on the phone with "Couldn't add widget". It exists
because the Gradle daemon can't run in the original build VM's sandbox.

First run on the phone: paste the board token once, allow notifications
(for plan reminders), then add the widget: long-press the home screen →
Widgets → Muse Board, or tap "Add widget to home screen" in the app.
