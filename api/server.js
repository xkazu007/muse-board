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
 *   GET   /api/activity            -> what changed, oldest first:
 *                                     ?since=<event id>&exclude=<source>
 *   GET   /api/suggestions         -> Muse's suggestions (?status=pending)
 *   POST  /api/suggestions         -> Muse suggests a change (title, detail,
 *                                     task_id + changes, and/or new_tasks)
 *   POST  /api/suggestions/:id/apply   -> the owner applies it
 *   POST  /api/suggestions/:id/dismiss -> the owner dismisses it
 *   GET   /health                  -> { ok: true } (no auth)
 *
 * Writers say who they are with `X-Board-Client: app | web | muse`; the
 * activity feed records it, so Muse can tell the owner's changes from its
 * own.
 *
 * Task fields: title, detail (notes, up to 4000 chars), first_step (the
 * 2-minute starter the widget shows big), due_at, plan_at + plan_where (the
 * "when · where" plan; the phone reminds at plan_at), position (lower =
 * sooner in the queue), url (go-to link, http/https), group (short
 * label; the app groups its list by it), blocks (what the detail page shows,
 * written by Muse: text, headings, link buttons, checklist items, toggles;
 * see BLOCK_TYPES), estimate_min (realistic minutes for the whole task,
 * shown as "~15 min"). Read-only: snooze_count (Laters since first_step
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
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS url TEXT;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS task_group TEXT;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS blocks JSONB;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS estimate_min INT;
CREATE TABLE IF NOT EXISTS activity (
  id BIGSERIAL PRIMARY KEY,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  task_id INT,
  kind TEXT NOT NULL,
  source TEXT,
  data JSONB
);
CREATE TABLE IF NOT EXISTS suggestions (
  id SERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status TEXT NOT NULL DEFAULT 'pending',
  resolved_at TIMESTAMPTZ,
  task_id INT,
  title TEXT NOT NULL,
  detail TEXT,
  changes JSONB,
  new_tasks JSONB
);
DELETE FROM activity WHERE at < now() - interval '180 days';
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
  "id, title, detail, first_step, due_at, plan_at, plan_where, position, status, completed_at, snoozed_until, snooze_count, url, task_group, blocks, estimate_min";

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
    url: r.url,
    group: r.task_group,
    blocks: r.blocks || [],
    estimate_min: r.estimate_min,
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
  // Go-to link: where the first step happens (the lesson, the Zoom room).
  // Format only (http / https), like Notion; the destination isn't checked.
  url: { kind: "url", max: 2000 },
  // Optional group ("Coursera ML", "Manara"): the app's list is grouped by
  // it. Stored as task_group ("group" is an SQL keyword).
  group: { kind: "text", max: 40, column: "task_group" },
  due_at: { kind: "time" },
  plan_at: { kind: "time" },
  position: { kind: "int" },
  // The detail page's content, an ordered list (see BLOCK_TYPES).
  blocks: { kind: "blocks" },
  // Realistic time for the whole task, in minutes (shown as "~15 min").
  estimate_min: { kind: "minutes", max: 1440 },
};

/** Block types and the text each carries (key -> max length). A button
 *  also has a `url` (http / https), a check a `done` flag the owner ticks. */
const BLOCK_TYPES = {
  heading: { text: 80 },
  text: { text: 2000 },
  button: { label: 40 },
  check: { text: 200 },
  toggle: { text: 120, body: 2000 },
};
const MAX_BLOCKS = 40;

/** `{ blocks }` cleaned up (trimmed, unknown keys dropped), or `{ error }`. */
function cleanBlocks(value) {
  if (!Array.isArray(value)) return { error: "blocks must be an array or null" };
  if (value.length > MAX_BLOCKS) return { error: `blocks has more than ${MAX_BLOCKS} items` };
  const blocks = [];
  for (const [i, b] of value.entries()) {
    const at = `blocks[${i}]`;
    if (!b || typeof b !== "object" || Array.isArray(b)) return { error: `${at} must be an object` };
    const texts = BLOCK_TYPES[b.type];
    if (!texts) return { error: `${at}.type must be one of ${Object.keys(BLOCK_TYPES).join(", ")}` };
    const clean = { type: b.type };
    for (const [key, max] of Object.entries(texts)) {
      if (typeof b[key] !== "string" || !b[key].trim()) return { error: `${at}.${key} is required` };
      clean[key] = b[key].trim();
      if (clean[key].length > max) return { error: `${at}.${key} is longer than ${max} characters` };
    }
    if (b.type === "button") {
      const url = typeof b.url === "string" ? b.url.trim() : "";
      if (!/^https?:\/\/\S+$/i.test(url) || url.length > 2000) {
        return { error: `${at}.url must start with http:// or https://` };
      }
      clean.url = url;
    }
    if (b.type === "check") {
      if (b.done !== undefined && typeof b.done !== "boolean") return { error: `${at}.done must be true or false` };
      clean.done = b.done === true;
    }
    blocks.push(clean);
  }
  return { blocks };
}

/** Checks the editable fields present in `body`. Returns an error message,
 *  or null when every present field is valid. */
function validateTaskFields(body, { creating }) {
  for (const [name, spec] of Object.entries(FIELDS)) {
    const value = body[name];
    if (value === undefined) {
      if (creating && spec.required) return `${name} is required`;
      continue;
    }
    if (spec.kind === "blocks") {
      if (value === null) continue;
      const { error } = cleanBlocks(value);
      if (error) return error;
      continue;
    }
    if (spec.kind === "int") {
      if (!Number.isInteger(value) || value < 0) return `${name} must be a non-negative integer`;
      continue;
    }
    if (spec.kind === "minutes") {
      if (value !== null && (!Number.isInteger(value) || value < 1 || value > spec.max)) {
        return `${name} must be whole minutes from 1 to ${spec.max}, or null`;
      }
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
    if (spec.kind === "url" && value.trim()) {
      if (value.length > spec.max) return `${name} is longer than ${spec.max} characters`;
      if (!/^https?:\/\/\S+$/i.test(value.trim())) return `${name} must start with http:// or https://`;
    }
  }
  return null;
}

/** The value stored for a field: trimmed text, empty text as null. */
function storedValue(name, value) {
  if (value === null || FIELDS[name].kind === "int" || FIELDS[name].kind === "minutes") return value;
  // JSON text for the jsonb column (pg would send a JS array as a Postgres
  // array); an empty list is stored as null.
  if (FIELDS[name].kind === "blocks") {
    const { blocks } = cleanBlocks(value);
    return blocks.length ? JSON.stringify(blocks) : null;
  }
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

// --- Activity feed -----------------------------------------------------------
//
// Every write is logged (task created / updated / completed / reopened /
// snoozed, suggestion applied / dismissed) with who made it, so Muse can
// keep its own per-task notes current and notice patterns. Kept 180 days.

/** Who is writing, from `X-Board-Client` ("app", "web", "muse"), or null. */
function sourceOf(req) {
  const value = String(req.headers["x-board-client"] || "").trim().toLowerCase();
  return /^[a-z][a-z0-9-]{0,19}$/.test(value) ? value : null;
}

async function logActivity(db, taskId, kind, source, data) {
  await db.query(
    "INSERT INTO activity (task_id, kind, source, data) VALUES ($1, $2, $3, $4)",
    [taskId, kind, source, data ? JSON.stringify(data) : null],
  );
}

/** What an update really changed: `changes` (field -> new value) and, for
 *  checklist blocks, which items were ticked / unticked. Null if nothing. */
function describeUpdate(before, after, body) {
  const changes = {};
  for (const name of Object.keys(FIELDS)) {
    if (body[name] === undefined || name === "blocks") continue;
    const column = FIELDS[name].column || name;
    const a = FIELDS[name].kind === "time" ? iso(before[column]) : before[column];
    const b = FIELDS[name].kind === "time" ? iso(after[column]) : after[column];
    if (a !== b) changes[name] = b;
  }
  const data = Object.keys(changes).length ? { changes } : {};
  if (body.blocks !== undefined) Object.assign(data, describeBlocks(before.blocks || [], after.blocks || []));
  return Object.keys(data).length ? data : null;
}

function describeBlocks(a, b) {
  const doneByText = (list) => new Map(list.filter((x) => x.type === "check").map((x) => [x.text, x.done]));
  const was = doneByText(a);
  const ticked = [];
  const unticked = [];
  for (const [text, done] of doneByText(b)) {
    if (was.has(text) && was.get(text) !== done) (done ? ticked : unticked).push(text);
  }
  const shape = (list) => JSON.stringify(list.map((x) => ({ ...x, done: undefined })));
  const out = {};
  if (ticked.length) out.ticked = ticked;
  if (unticked.length) out.unticked = unticked;
  if (shape(a) !== shape(b)) out.blocks_edited = true;
  return out;
}

// --- Writing tasks (shared by the task routes and applied suggestions) ------

/** Inserts a validated task body; returns the new row. */
async function insertTask(db, body) {
  const columns = [];
  const values = [];
  for (const name of Object.keys(FIELDS)) {
    if (body[name] === undefined) continue;
    columns.push(FIELDS[name].column || name);
    values.push(storedValue(name, body[name]));
  }
  if (!columns.includes("position")) {
    const { rows } = await db.query("SELECT COALESCE(MAX(position), 0)::int + 1 AS p FROM tasks");
    columns.push("position");
    values.push(rows[0].p);
  }
  const placeholders = values.map((_, i) => `$${i + 1}`).join(", ");
  const { rows } = await db.query(
    `INSERT INTO tasks (${columns.join(", ")}) VALUES (${placeholders}) RETURNING ${TASK_COLUMNS}`,
    values,
  );
  return rows[0];
}

/** Applies a validated, non-empty update; returns { before, after } or
 *  null for an unknown id. Run inside a transaction ([db] = a client). */
async function updateTaskRow(db, id, body) {
  const { rows: old } = await db.query(`SELECT ${TASK_COLUMNS} FROM tasks WHERE id = $1 FOR UPDATE`, [id]);
  if (old.length === 0) return null;
  const sets = [];
  const values = [id];
  for (const name of Object.keys(FIELDS)) {
    if (body[name] === undefined) continue;
    values.push(storedValue(name, body[name]));
    sets.push(`${FIELDS[name].column || name} = $${values.length}`);
  }
  // A rewritten first step is a fresh start: "Too big?" goes away.
  if (body.first_step !== undefined) sets.push("snooze_count = 0");
  const { rows } = await db.query(
    `UPDATE tasks SET ${sets.join(", ")} WHERE id = $1 RETURNING ${TASK_COLUMNS}`,
    values,
  );
  return { before: old[0], after: rows[0] };
}

/** Runs [work] in a transaction on one pooled client. */
async function inTransaction(work) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await work(client);
    await client.query("COMMIT");
    return result;
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

/** Only the editable fields of [body] (unknown keys dropped). */
function pickFields(body) {
  const out = {};
  for (const name of Object.keys(FIELDS)) if (body[name] !== undefined) out[name] = body[name];
  return out;
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
    const { rows: pending } = await pool.query(`${SUGGESTION_SQL} WHERE s.status = 'pending' ORDER BY s.id LIMIT 1`);
    res.json({
      date: doneRows[0].today,
      focus,
      next,
      upcoming,
      plans: plans.map(publicTask),
      doneToday: doneRows[0].n,
      // Muse's oldest open suggestion, one at a time (or null).
      suggestion: pending.length ? publicSuggestion(pending[0]) : null,
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
    const row = await insertTask(pool, body);
    await logActivity(pool, row.id, "created", sourceOf(req), { fields: pickFields(body) });
    res.status(201).json({ task: publicTask(row) });
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
  if (Object.keys(pickFields(body)).length === 0) {
    return res.status(400).json({ error: "nothing to update" });
  }
  try {
    const result = await inTransaction(async (db) => {
      const done = await updateTaskRow(db, id, body);
      if (!done) return null;
      const data = describeUpdate(done.before, done.after, body);
      if (data) await logActivity(db, id, "updated", sourceOf(req), data);
      return done.after;
    });
    if (!result) return res.status(404).json({ error: "task not found" });
    res.json({ task: publicTask(result) });
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
    await logActivity(pool, id, label === "complete" ? "completed" : "reopened", sourceOf(req), null);
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
    await logActivity(pool, id, "snoozed", sourceOf(req), {
      until: iso(rows[0].snoozed_until),
      snooze_count: rows[0].snooze_count,
    });
    res.json({ task: publicTask(rows[0]) });
  } catch (err) {
    if (badTime(err, res)) return;
    console.error("POST /api/tasks/:id/snooze failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

// --- Activity and suggestions routes -----------------------------------------

app.get("/api/activity", requireAuth, async (req, res) => {
  const since = req.query.since === undefined ? null : Number(req.query.since);
  if (since !== null && (!Number.isInteger(since) || since < 0)) {
    return res.status(400).json({ error: "since must be an event id (a whole number)" });
  }
  const limit = Math.min(Math.max(parseInt(req.query.limit || "100", 10) || 100, 1), 500);
  const exclude = req.query.exclude ? String(req.query.exclude).toLowerCase() : null;
  try {
    // With `since`: the events after it, oldest first. Without: the latest
    // [limit] events, still oldest first.
    const { rows } = await pool.query(
      `SELECT * FROM (
         SELECT a.id, a.at, a.task_id, t.title AS task_title, a.kind, a.source, a.data
         FROM activity a LEFT JOIN tasks t ON t.id = a.task_id
         WHERE ($1::bigint IS NULL OR a.id > $1)
           AND ($2::text IS NULL OR a.source IS DISTINCT FROM $2)
         ORDER BY a.id ${since === null ? "DESC" : "ASC"}
         LIMIT $3
       ) e ORDER BY id ASC`,
      [since, exclude, limit],
    );
    const events = rows.map((r) => ({
      id: Number(r.id),
      at: iso(r.at),
      task_id: r.task_id,
      task_title: r.task_title,
      kind: r.kind,
      source: r.source,
      data: r.data,
    }));
    res.json({ events, last_id: events.length ? events[events.length - 1].id : since });
  } catch (err) {
    console.error("GET /api/activity failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

const SUGGESTION_SQL = `
SELECT s.*, t.title AS task_title
FROM suggestions s LEFT JOIN tasks t ON t.id = s.task_id`;

function publicSuggestion(r) {
  return {
    id: r.id,
    status: r.status,
    created_at: iso(r.created_at),
    resolved_at: iso(r.resolved_at),
    task_id: r.task_id,
    task_title: r.task_title,
    title: r.title,
    detail: r.detail,
    changes: r.changes,
    new_tasks: r.new_tasks,
  };
}

const MAX_NEW_TASKS = 5;

/** Checks a suggestion body; returns an error message or null. */
async function validateSuggestion(body) {
  if (typeof body.title !== "string" || !body.title.trim()) return "title is required";
  if (body.title.trim().length > 120) return "title is longer than 120 characters";
  if (body.detail != null && (typeof body.detail !== "string" || body.detail.length > 500)) {
    return "detail must be text up to 500 characters";
  }
  const hasChanges = body.changes != null;
  const hasNew = body.new_tasks != null;
  if (!hasChanges && !hasNew) return "a suggestion needs changes (with task_id) or new_tasks";
  if (hasChanges) {
    if (!Number.isInteger(body.task_id)) return "changes need a task_id";
    if (typeof body.changes !== "object" || Array.isArray(body.changes)) return "changes must be an object";
    if (Object.keys(pickFields(body.changes)).length === 0) return "changes has no task fields";
    const invalid = validateTaskFields(body.changes, { creating: false });
    if (invalid) return "changes: " + invalid;
    const { rows } = await pool.query("SELECT 1 FROM tasks WHERE id = $1", [body.task_id]);
    if (rows.length === 0) return "task_id is not a task";
  }
  if (hasNew) {
    if (!Array.isArray(body.new_tasks) || body.new_tasks.length === 0 || body.new_tasks.length > MAX_NEW_TASKS) {
      return `new_tasks must be a list of 1 to ${MAX_NEW_TASKS} tasks`;
    }
    for (const [i, t] of body.new_tasks.entries()) {
      if (!t || typeof t !== "object" || Array.isArray(t)) return `new_tasks[${i}] must be an object`;
      const invalid = validateTaskFields(t, { creating: true });
      if (invalid) return `new_tasks[${i}]: ${invalid}`;
    }
  }
  return null;
}

app.post("/api/suggestions", requireAuth, async (req, res) => {
  const body = req.body || {};
  try {
    const invalid = await validateSuggestion(body);
    if (invalid) return res.status(400).json({ error: invalid });
    const { rows } = await pool.query(
      `INSERT INTO suggestions (task_id, title, detail, changes, new_tasks)
       VALUES ($1, $2, $3, $4, $5) RETURNING id`,
      [
        Number.isInteger(body.task_id) ? body.task_id : null,
        body.title.trim(),
        body.detail ? body.detail.trim() : null,
        body.changes != null ? JSON.stringify(pickFields(body.changes)) : null,
        body.new_tasks != null ? JSON.stringify(body.new_tasks.map(pickFields)) : null,
      ],
    );
    const { rows: full } = await pool.query(`${SUGGESTION_SQL} WHERE s.id = $1`, [rows[0].id]);
    res.status(201).json({ suggestion: publicSuggestion(full[0]) });
  } catch (err) {
    if (badTime(err, res)) return;
    console.error("POST /api/suggestions failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.get("/api/suggestions", requireAuth, async (req, res) => {
  const status = String(req.query.status || "pending");
  if (!["pending", "applied", "dismissed", "all"].includes(status)) {
    return res.status(400).json({ error: "status must be pending, applied, dismissed or all" });
  }
  try {
    const { rows } = await pool.query(
      `${SUGGESTION_SQL} WHERE ($1 = 'all' OR s.status = $1) ORDER BY s.id DESC LIMIT 100`,
      [status],
    );
    res.json({ suggestions: rows.map(publicSuggestion) });
  } catch (err) {
    console.error("GET /api/suggestions failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

/** Applies a pending suggestion in one transaction: its changes to its
 *  task, then its new tasks. 404 if it isn't pending (or the task is gone). */
app.post("/api/suggestions/:id/apply", requireAuth, async (req, res) => {
  const id = parseId(req, res);
  if (id === null) return;
  const source = sourceOf(req);
  try {
    const result = await inTransaction(async (db) => {
      const { rows } = await db.query(
        "SELECT * FROM suggestions WHERE id = $1 AND status = 'pending' FOR UPDATE",
        [id],
      );
      if (rows.length === 0) return { missing: "suggestion not found or not pending" };
      const sug = rows[0];
      const created = [];
      if (sug.changes) {
        const done = await updateTaskRow(db, sug.task_id, sug.changes);
        if (!done) return { missing: "the suggested task no longer exists" };
        const data = describeUpdate(done.before, done.after, sug.changes);
        if (data) await logActivity(db, sug.task_id, "updated", source, { ...data, suggestion_id: id });
      }
      for (const t of sug.new_tasks || []) {
        const row = await insertTask(db, t);
        created.push(row.id);
        await logActivity(db, row.id, "created", source, { fields: t, suggestion_id: id });
      }
      await db.query("UPDATE suggestions SET status = 'applied', resolved_at = now() WHERE id = $1", [id]);
      await logActivity(db, sug.task_id, "suggestion_applied", source, { suggestion_id: id, title: sug.title, created });
      return { created };
    });
    if (result.missing) return res.status(404).json({ error: result.missing });
    const { rows } = await pool.query(`${SUGGESTION_SQL} WHERE s.id = $1`, [id]);
    res.json({ suggestion: publicSuggestion(rows[0]), created: result.created });
  } catch (err) {
    if (badTime(err, res)) return;
    console.error("POST /api/suggestions/:id/apply failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.post("/api/suggestions/:id/dismiss", requireAuth, async (req, res) => {
  const id = parseId(req, res);
  if (id === null) return;
  try {
    const { rows } = await pool.query(
      `UPDATE suggestions SET status = 'dismissed', resolved_at = now()
       WHERE id = $1 AND status = 'pending' RETURNING task_id, title`,
      [id],
    );
    if (rows.length === 0) return res.status(404).json({ error: "suggestion not found or not pending" });
    await logActivity(pool, rows[0].task_id, "suggestion_dismissed", sourceOf(req), {
      suggestion_id: id,
      title: rows[0].title,
    });
    const { rows: full } = await pool.query(`${SUGGESTION_SQL} WHERE s.id = $1`, [id]);
    res.json({ suggestion: publicSuggestion(full[0]) });
  } catch (err) {
    console.error("POST /api/suggestions/:id/dismiss failed:", err);
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
