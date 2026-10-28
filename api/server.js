"use strict";

/**
 * Muse Board API: one "do this now" task, what's next, and today's
 * progress, for an Android home-screen widget and a small web app. Tasks
 * are written by an agent (Muse) and acted on by the owner. See AGENTS.md
 * for how tasks should be written.
 *
 * Endpoints (all /api/* need `Authorization: Bearer <BOARD_TOKEN>`):
 *   GET   /api/board               -> { date, focus, next, upcoming[<=3],
 *                                       plans[], doneToday }
 *   GET   /api/tasks               -> full task list (ordered by position)
 *   POST  /api/tasks               -> create (fields below; title required)
 *   GET   /api/tasks/:id           -> one task
 *   PATCH /api/tasks/:id           -> edit any of the fields below
 *   POST  /api/tasks/:id           -> same as PATCH (for clients without
 *                                     PATCH, e.g. Android's HttpURLConnection)
 *   POST  /api/tasks/:id/complete  -> mark done
 *   POST  /api/tasks/:id/reopen    -> undo a "done"
 *   POST  /api/tasks/:id/snooze    -> "Later": { until? } hides the task
 *                                     until then and re-plans it for then;
 *                                     no `until` = until tomorrow 09:00
 *   GET   /health                  -> { ok: true } (no auth)
 *
 * Task fields: title, detail (notes, up to 4000 chars), first_step (the
 * 2-minute starter the widget shows big), due_at, plan_at + plan_where (the
 * "when · where" plan; the phone reminds at plan_at), position (lower =
 * sooner in the queue). Read-only: snooze_count (Laters since first_step
 * last changed; the app offers "Too big?" at 2).
 *
 * Env: BOARD_TOKEN (required), DATABASE_URL (required), PORT (default 80).
 *
 * Time: "today" is computed in Africa/Casablanca time, by Postgres (its tz
 * data), not by Node: the ICU tz data bundled with Node lags behind real
 * changes (Morocco moved to permanent UTC+0 on 2026-09-20; Node 22's 2025b
 * data still says +01). A date-time sent without a UTC offset is read as
 * Casablanca local time, so task writers never need to know the offset.
 */

const crypto = require("node:crypto");
const express = require("express");
const path = require("node:path");
const { Pool } = require("pg");

const PORT = parseInt(process.env.PORT || "80", 10);
const BOARD_TOKEN = process.env.BOARD_TOKEN || "";
const DATABASE_URL = process.env.DATABASE_URL || "";
const TZ = "Africa/Casablanca";

if (!BOARD_TOKEN) {
  console.error("FATAL: BOARD_TOKEN env var is required");
  process.exit(1);
}
if (!DATABASE_URL) {
  console.error("FATAL: DATABASE_URL env var is required");
  process.exit(1);
}

// Session time zone: offset-less date-time strings are read as Casablanca time.
const pool = new Pool({
  connectionString: DATABASE_URL,
  max: 5,
  options: `-c TimeZone=${TZ}`,
});

// CREATE for a fresh database, then ADD COLUMN IF NOT EXISTS for one made
// by an older version, then a one-off move of the old snoozed_at ("hidden
// for the rest of that day") onto snoozed_until.
const SCHEMA = `
CREATE TABLE IF NOT EXISTS tasks (
  id SERIAL PRIMARY KEY,
  title TEXT NOT NULL,
  detail TEXT,
  due_at TIMESTAMPTZ,
  position INT NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'open',
  completed_at TIMESTAMPTZ,
  snoozed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS first_step TEXT;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS plan_at TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS plan_where TEXT;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS snoozed_until TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS snooze_count INT NOT NULL DEFAULT 0;
UPDATE tasks
SET snoozed_until = ((snoozed_at AT TIME ZONE '${TZ}')::date + 1)::timestamp AT TIME ZONE '${TZ}'
WHERE snoozed_at IS NOT NULL AND snoozed_until IS NULL;
`;

// Only inserted when the table is empty. Times carry no offset, so they
// are read as Casablanca local time (see the session time zone above).
const SEED = [
  {
    title: "Watch the 7-min ML video",
    detail: "Supervised learning part 2, Coursera ML",
    first_step: "Open Coursera and press play",
    due_at: "2026-10-28T23:59:00",
    position: 1,
  },
  {
    title: "AI Practitioner live session",
    detail: "Manara, Wed Oct 28, 16:00 to 18:00",
    first_step: "Open the Manara link",
    due_at: "2026-10-28T16:00:00",
    position: 2,
  },
  {
    title: "NutriSynth prep",
    detail: "Meeting with Manal, Fri Oct 30, 12:00",
    first_step: "Write 3 questions for Manal",
    due_at: "2026-10-30T12:00:00",
    position: 3,
  },
  {
    title: "SA project",
    detail: "AWS Solutions Architect, due Nov 28",
    first_step: "Open the project brief and read the first page",
    due_at: "2026-11-28T23:59:00",
    position: 4,
  },
];

const TASK_COLUMNS =
  "id, title, detail, first_step, due_at, plan_at, plan_where, position, status, completed_at, snoozed_until, snooze_count";

async function initDb() {
  await pool.query(SCHEMA);
  const { rows } = await pool.query("SELECT COUNT(*)::int AS n FROM tasks");
  if (rows[0].n === 0) {
    for (const t of SEED) {
      await pool.query(
        `INSERT INTO tasks (title, detail, first_step, due_at, position)
         VALUES ($1, $2, $3, $4, $5)`,
        [t.title, t.detail, t.first_step, t.due_at, t.position],
      );
    }
    console.log(`seeded ${SEED.length} tasks`);
  }
}

const app = express();
app.use(express.json());
app.use((req, _res, next) => {
  console.log(`${new Date().toISOString()} ${req.method} ${req.path}`);
  next();
});

function authed(req) {
  const header = req.headers.authorization || "";
  if (!header.startsWith("Bearer ")) return false;
  const given = Buffer.from(header.slice("Bearer ".length));
  const want = Buffer.from(BOARD_TOKEN);
  return given.length === want.length && crypto.timingSafeEqual(given, want);
}

function requireAuth(req, res, next) {
  if (!authed(req)) return res.status(401).json({ error: "unauthorized" });
  return next();
}

function iso(value) {
  return value ? new Date(value).toISOString() : null;
}

function publicTask(r) {
  return {
    id: r.id,
    title: r.title,
    detail: r.detail,
    first_step: r.first_step,
    due_at: iso(r.due_at),
    plan_at: iso(r.plan_at),
    plan_where: r.plan_where,
    position: r.position,
    status: r.status,
    completed_at: iso(r.completed_at),
    snoozed_until: iso(r.snoozed_until),
    // Times "Later" was used since the first step last changed. Two or
    // more: the app offers "Too big? Make it smaller".
    snooze_count: r.snooze_count,
  };
}

// The queue: open tasks not hidden by "Later", first = the focus.
const OPEN_SQL = `
SELECT ${TASK_COLUMNS}
FROM tasks
WHERE status = 'open'
  AND (snoozed_until IS NULL OR snoozed_until <= now())
ORDER BY position ASC, id ASC
`;

// Reminders the phone should schedule: open tasks (snoozed ones included,
// since "Later" re-plans them) with a plan time in the next 36 hours.
const PLANS_SQL = `
SELECT ${TASK_COLUMNS}
FROM tasks
WHERE status = 'open'
  AND plan_at > now()
  AND plan_at <= now() + interval '36 hours'
ORDER BY plan_at ASC
LIMIT 20
`;

// Postgres error codes for a date-time it cannot read.
const BAD_DATETIME_CODES = new Set(["22007", "22008"]);

/** Editable fields: type and length limits. Lengths keep the widget
 *  readable (first_step is shown in large text, plan_where after the time). */
const FIELDS = {
  title: { kind: "text", max: 200, required: true },
  detail: { kind: "text", max: 4000 },
  first_step: { kind: "text", max: 140 },
  plan_where: { kind: "text", max: 40 },
  due_at: { kind: "time" },
  plan_at: { kind: "time" },
  position: { kind: "int" },
};

/** Checks the editable fields present in `body`. Returns an error message,
 *  or null when every present field is valid. */
function validateTaskFields(body, { creating }) {
  for (const [name, spec] of Object.entries(FIELDS)) {
    const value = body[name];
    if (value === undefined) {
      if (creating && spec.required) return `${name} is required`;
      continue;
    }
    if (spec.kind === "int") {
      if (!Number.isInteger(value) || value < 0) return `${name} must be a non-negative integer`;
      continue;
    }
    if (value === null) {
      if (spec.required) return `${name} is required`;
      continue;
    }
    if (typeof value !== "string") {
      return spec.kind === "time"
        ? `${name} must be an ISO date-time string or null`
        : `${name} must be a string or null`;
    }
    if (spec.kind === "text") {
      if (spec.required && !value.trim()) return `${name} is required`;
      if (value.length > spec.max) return `${name} is longer than ${spec.max} characters`;
    }
  }
  return null;
}

/** The value stored for a field: trimmed text, empty text as null. */
function storedValue(name, value) {
  if (value === null || FIELDS[name].kind === "int") return value;
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

function badTime(err, res) {
  if (!BAD_DATETIME_CODES.has(err.code)) return false;
  res.status(400).json({ error: "a date-time field is not a valid date-time" });
  return true;
}

function parseId(req, res) {
  const id = parseInt(req.params.id, 10);
  if (!Number.isInteger(id)) {
    res.status(400).json({ error: "bad id" });
    return null;
  }
  return id;
}

app.get("/health", (_req, res) => res.json({ ok: true, service: "muse-board" }));

app.get("/api/board", requireAuth, async (_req, res) => {
  try {
    const { rows: open } = await pool.query(OPEN_SQL);
    const focus = open.length > 0 ? publicTask(open[0]) : null;
    // `next` becomes the focus once the current one is done or snoozed;
    // the widget uses it to update instantly on Done.
    const next = open.length > 1 ? publicTask(open[1]) : null;
    const rest = open.slice(1);
    rest.sort((a, b) => {
      const da = a.due_at ? new Date(a.due_at).getTime() : Infinity;
      const db = b.due_at ? new Date(b.due_at).getTime() : Infinity;
      return da - db || a.position - b.position;
    });
    const upcoming = rest.slice(0, 3).map(publicTask);
    const { rows: plans } = await pool.query(PLANS_SQL);
    const { rows: doneRows } = await pool.query(
      `SELECT COUNT(*)::int AS n,
              to_char((now() AT TIME ZONE $1)::date, 'YYYY-MM-DD') AS today
       FROM tasks
       WHERE status = 'done'
         AND (completed_at AT TIME ZONE $1)::date = (now() AT TIME ZONE $1)::date`,
      [TZ],
    );
    res.json({
      date: doneRows[0].today,
      focus,
      next,
      upcoming,
      plans: plans.map(publicTask),
      doneToday: doneRows[0].n,
    });
  } catch (err) {
    console.error("GET /api/board failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.get("/api/tasks", requireAuth, async (_req, res) => {
  try {
    const { rows } = await pool.query(
      `SELECT ${TASK_COLUMNS} FROM tasks ORDER BY position ASC, id ASC`,
    );
    res.json({ tasks: rows.map(publicTask) });
  } catch (err) {
    console.error("GET /api/tasks failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.post("/api/tasks", requireAuth, async (req, res) => {
  const body = req.body || {};
  const invalid = validateTaskFields(body, { creating: true });
  if (invalid) return res.status(400).json({ error: invalid });
  try {
    const columns = [];
    const values = [];
    for (const name of Object.keys(FIELDS)) {
      if (body[name] === undefined) continue;
      columns.push(name);
      values.push(storedValue(name, body[name]));
    }
    if (!columns.includes("position")) {
      const { rows } = await pool.query(
        "SELECT COALESCE(MAX(position), 0)::int + 1 AS p FROM tasks",
      );
      columns.push("position");
      values.push(rows[0].p);
    }
    const placeholders = values.map((_, i) => `$${i + 1}`).join(", ");
    const { rows } = await pool.query(
      `INSERT INTO tasks (${columns.join(", ")}) VALUES (${placeholders})
       RETURNING ${TASK_COLUMNS}`,
      values,
    );
    res.status(201).json({ task: publicTask(rows[0]) });
  } catch (err) {
    if (badTime(err, res)) return;
    console.error("POST /api/tasks failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

async function updateTask(req, res) {
  const id = parseId(req, res);
  if (id === null) return;
  const body = req.body || {};
  const invalid = validateTaskFields(body, { creating: false });
  if (invalid) return res.status(400).json({ error: invalid });
  const sets = [];
  const values = [id];
  for (const name of Object.keys(FIELDS)) {
    if (body[name] === undefined) continue;
    values.push(storedValue(name, body[name]));
    sets.push(`${name} = $${values.length}`);
  }
  if (sets.length === 0) {
    return res.status(400).json({ error: "nothing to update" });
  }
  // A rewritten first step is a fresh start: "Too big?" goes away.
  if (body.first_step !== undefined) sets.push("snooze_count = 0");
  try {
    const { rows } = await pool.query(
      `UPDATE tasks SET ${sets.join(", ")} WHERE id = $1 RETURNING ${TASK_COLUMNS}`,
      values,
    );
    if (rows.length === 0) return res.status(404).json({ error: "task not found" });
    res.json({ task: publicTask(rows[0]) });
  } catch (err) {
    if (badTime(err, res)) return;
    console.error(`${req.method} /api/tasks/:id failed:`, err);
    res.status(500).json({ error: "internal error" });
  }
}

app.get("/api/tasks/:id", requireAuth, async (req, res) => {
  const id = parseId(req, res);
  if (id === null) return;
  try {
    const { rows } = await pool.query(`SELECT ${TASK_COLUMNS} FROM tasks WHERE id = $1`, [id]);
    if (rows.length === 0) return res.status(404).json({ error: "task not found" });
    res.json({ task: publicTask(rows[0]) });
  } catch (err) {
    console.error("GET /api/tasks/:id failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.patch("/api/tasks/:id", requireAuth, updateTask);
app.post("/api/tasks/:id", requireAuth, updateTask);

/** Runs a status change that only applies in `fromStatus`; 404 otherwise. */
async function changeStatus(req, res, setSql, fromStatus, label) {
  const id = parseId(req, res);
  if (id === null) return;
  try {
    const { rows } = await pool.query(
      `UPDATE tasks SET ${setSql} WHERE id = $1 AND status = '${fromStatus}'
       RETURNING ${TASK_COLUMNS}`,
      [id],
    );
    if (rows.length === 0) {
      return res.status(404).json({ error: `task not found or not ${fromStatus}` });
    }
    res.json({ task: publicTask(rows[0]) });
  } catch (err) {
    console.error(`POST /api/tasks/:id/${label} failed:`, err);
    res.status(500).json({ error: "internal error" });
  }
}

app.post("/api/tasks/:id/complete", requireAuth, (req, res) =>
  changeStatus(req, res, "status = 'done', completed_at = now()", "open", "complete"),
);

app.post("/api/tasks/:id/reopen", requireAuth, (req, res) =>
  changeStatus(req, res, "status = 'open', completed_at = NULL", "done", "reopen"),
);

// "Later". With `until` (a date-time in the next 14 days) the task is
// hidden until then and its plan moves there, so the phone reminds at that
// time: a vague "not now" becomes a concrete new plan. Without `until` it is
// hidden until tomorrow 09:00 (Casablanca) and keeps its plan.
app.post("/api/tasks/:id/snooze", requireAuth, async (req, res) => {
  const id = parseId(req, res);
  if (id === null) return;
  const { until } = req.body || {};
  if (until !== undefined && until !== null && typeof until !== "string") {
    return res.status(400).json({ error: "until must be an ISO date-time string" });
  }
  try {
    let rows;
    if (until) {
      const { rows: check } = await pool.query(
        `SELECT $1::timestamptz > now() AS future,
                $1::timestamptz <= now() + interval '14 days' AS near`,
        [until],
      );
      if (!check[0].future || !check[0].near) {
        return res.status(400).json({ error: "until must be in the future and at most 14 days away" });
      }
      ({ rows } = await pool.query(
        `UPDATE tasks SET snoozed_at = now(), snoozed_until = $2::timestamptz, plan_at = $2::timestamptz,
                          snooze_count = snooze_count + 1
         WHERE id = $1 AND status = 'open'
         RETURNING ${TASK_COLUMNS}`,
        [id, until],
      ));
    } else {
      ({ rows } = await pool.query(
        `UPDATE tasks
         SET snoozed_at = now(),
             snooze_count = snooze_count + 1,
             snoozed_until = ((now() AT TIME ZONE $2)::date + 1 + time '09:00') AT TIME ZONE $2
         WHERE id = $1 AND status = 'open'
         RETURNING ${TASK_COLUMNS}`,
        [id, TZ],
      ));
    }
    if (rows.length === 0) {
      return res.status(404).json({ error: "task not found or not open" });
    }
    res.json({ task: publicTask(rows[0]) });
  } catch (err) {
    if (badTime(err, res)) return;
    console.error("POST /api/tasks/:id/snooze failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

// Companion web app (same origin, no CORS needed).
app.use(express.static(path.join(__dirname, "web")));
app.get("/", (_req, res) =>
  res.sendFile(path.join(__dirname, "web", "index.html")),
);

async function initDbWithRetry(attempts = 20, delayMs = 3000) {
  for (let i = 1; i <= attempts; i++) {
    try {
      await initDb();
      return;
    } catch (err) {
      console.error(`database init attempt ${i}/${attempts} failed: ${err.message}`);
      if (i === attempts) throw err;
      await new Promise((r) => setTimeout(r, delayMs));
    }
  }
}

initDbWithRetry()
  .then(() => {
    app.listen(PORT, () => console.log(`muse-board listening on :${PORT}`));
  })
  .catch((err) => {
    console.error("FATAL: database init failed:", err);
    process.exit(1);
  });
