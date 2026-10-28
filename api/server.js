"use strict";

/**
 * Muse Board API — small task board for Zakaria's Android home-screen widget.
 *
 * Endpoints (all /api/* need `Authorization: Bearer <BOARD_TOKEN>`):
 *   GET  /api/board              -> { date, focus, upcoming[<=3], doneToday }
 *   GET  /api/tasks              -> full task list (ordered by position)
 *   POST /api/tasks              -> { title, detail?, due_at? } creates a task
 *   POST /api/tasks/:id/complete -> mark done (completed_at = now)
 *   POST /api/tasks/:id/snooze   -> "not now": hidden from focus until tomorrow
 *   GET  /health                 -> { ok: true } (no auth)
 *
 * Env: BOARD_TOKEN (required), DATABASE_URL (required), PORT (default 80).
 * "Today" is computed in Africa/Casablanca time.
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

const pool = new Pool({ connectionString: DATABASE_URL, max: 5 });

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
`;

// Minimal, factual seed from Zakaria's real task list (2026-10-27).
// Only inserted when the table is empty.
const SEED = [
  {
    title: "Watch the 7-min ML video",
    detail: "Supervised learning part 2 — Coursera ML",
    due_at: "2026-10-28T23:59:00+01:00",
    position: 1,
  },
  {
    title: "AI Practitioner live session",
    detail: "Manara — Wed Oct 28, 16:00–18:00",
    due_at: "2026-10-28T16:00:00+01:00",
    position: 2,
  },
  {
    title: "NutriSynth prep",
    detail: "Meeting with Manal — Fri Oct 30, 12:00",
    due_at: "2026-10-30T12:00:00+01:00",
    position: 3,
  },
  {
    title: "SA project",
    detail: "AWS Solutions Architect — due Nov 28",
    due_at: "2026-11-28T23:59:00+01:00",
    position: 4,
  },
];

async function initDb() {
  await pool.query(SCHEMA);
  const { rows } = await pool.query("SELECT COUNT(*)::int AS n FROM tasks");
  if (rows[0].n === 0) {
    for (const t of SEED) {
      await pool.query(
        "INSERT INTO tasks (title, detail, due_at, position) VALUES ($1, $2, $3, $4)",
        [t.title, t.detail, t.due_at, t.position],
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

function publicTask(r) {
  return {
    id: r.id,
    title: r.title,
    detail: r.detail,
    due_at: r.due_at ? new Date(r.due_at).toISOString() : null,
    position: r.position,
    status: r.status,
    completed_at: r.completed_at ? new Date(r.completed_at).toISOString() : null,
  };
}

// Open tasks, excluding ones snoozed earlier today (Casablanca date).
const OPEN_SQL = `
SELECT id, title, detail, due_at, position, status, completed_at
FROM tasks
WHERE status = 'open'
  AND (snoozed_at IS NULL
       OR (snoozed_at AT TIME ZONE $1)::date < (now() AT TIME ZONE $1)::date)
ORDER BY position ASC
`;

app.get("/health", (_req, res) => res.json({ ok: true, service: "muse-board" }));

app.get("/api/board", requireAuth, async (_req, res) => {
  try {
    const { rows: open } = await pool.query(OPEN_SQL, [TZ]);
    const focus = open.length > 0 ? publicTask(open[0]) : null;
    const rest = open.slice(1);
    rest.sort((a, b) => {
      const da = a.due_at ? new Date(a.due_at).getTime() : Infinity;
      const db = b.due_at ? new Date(b.due_at).getTime() : Infinity;
      return da - db || a.position - b.position;
    });
    const upcoming = rest.slice(0, 3).map(publicTask);
    const { rows: doneRows } = await pool.query(
      `SELECT COUNT(*)::int AS n FROM tasks
       WHERE status = 'done'
         AND (completed_at AT TIME ZONE $1)::date = (now() AT TIME ZONE $1)::date`,
      [TZ],
    );
    const today = new Date().toLocaleDateString("en-CA", { timeZone: TZ });
    res.json({ date: today, focus, upcoming, doneToday: doneRows[0].n });
  } catch (err) {
    console.error("GET /api/board failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.get("/api/tasks", requireAuth, async (_req, res) => {
  try {
    const { rows } = await pool.query(
      "SELECT id, title, detail, due_at, position, status, completed_at FROM tasks ORDER BY position ASC, id ASC",
    );
    res.json({ tasks: rows.map(publicTask) });
  } catch (err) {
    console.error("GET /api/tasks failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.post("/api/tasks", requireAuth, async (req, res) => {
  const { title, detail, due_at } = req.body || {};
  if (!title || typeof title !== "string") {
    return res.status(400).json({ error: "title is required" });
  }
  try {
    const { rows: maxRows } = await pool.query(
      "SELECT COALESCE(MAX(position), 0)::int AS m FROM tasks",
    );
    const { rows } = await pool.query(
      "INSERT INTO tasks (title, detail, due_at, position) VALUES ($1, $2, $3, $4) RETURNING id, title, detail, due_at, position, status, completed_at",
      [title, detail || null, due_at || null, maxRows[0].m + 1],
    );
    res.status(201).json({ task: publicTask(rows[0]) });
  } catch (err) {
    console.error("POST /api/tasks failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.post("/api/tasks/:id/complete", requireAuth, async (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (!Number.isInteger(id)) return res.status(400).json({ error: "bad id" });
  try {
    const { rows } = await pool.query(
      `UPDATE tasks SET status = 'done', completed_at = now()
       WHERE id = $1 AND status = 'open'
       RETURNING id, title, detail, due_at, position, status, completed_at`,
      [id],
    );
    if (rows.length === 0) {
      return res.status(404).json({ error: "task not found or already done" });
    }
    res.json({ task: publicTask(rows[0]) });
  } catch (err) {
    console.error("POST /api/tasks/:id/complete failed:", err);
    res.status(500).json({ error: "internal error" });
  }
});

app.post("/api/tasks/:id/snooze", requireAuth, async (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (!Number.isInteger(id)) return res.status(400).json({ error: "bad id" });
  try {
    const { rows } = await pool.query(
      `UPDATE tasks SET snoozed_at = now()
       WHERE id = $1 AND status = 'open'
       RETURNING id, title, detail, due_at, position, status, completed_at`,
      [id],
    );
    if (rows.length === 0) {
      return res.status(404).json({ error: "task not found or already done" });
    }
    res.json({ task: publicTask(rows[0]) });
  } catch (err) {
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
