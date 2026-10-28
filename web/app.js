"use strict";

const $ = (id) => document.getElementById(id);
const TOKEN_KEY = "muse-board-token";
const TZ = "Africa/Casablanca";

let token = "";
try {
  token = localStorage.getItem(TOKEN_KEY) || "";
} catch (_) {
  // Storage blocked: the token box shows and the token lives for this visit.
}
let focusId = null;
let focusTitle = "";
let doneToday = 0;
/** The focus task's blocks as last shown, and the task they belong to. */
let focusBlocks = [];
let moreOpenFor = null;

async function api(path, method, body) {
  const res = await fetch(path, {
    method: method || "GET",
    headers: {
      Authorization: "Bearer " + token,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) throw new Error("unauthorized");
  if (!res.ok) throw new Error("http " + res.status);
  return res.json();
}

// --- Feedback: every tap answers at once ------------------------------------

/** A light tick on phones that support it (Android Chrome), with the CSS
 *  press-in on everything tappable. */
function haptic() {
  try {
    if (navigator.vibrate) navigator.vibrate(8);
  } catch (_) {}
}
document.addEventListener("click", (e) => {
  if (e.target.closest("button:not(:disabled), a, summary, label.check, .row")) haptic();
});

// --- Time, always in Casablanca (the board's zone) -------------------------

/** "2026-10-28" for a Date, in Casablanca. */
function dayKey(date) {
  return date.toLocaleDateString("en-CA", { timeZone: TZ });
}

function addDays(key, n) {
  const [y, m, d] = key.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
}

function hhmm(date) {
  return date.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: TZ });
}

/** Short and relative: "21:00", "Tomorrow 09:00", "Fri 12:00", "28 Nov". */
function fmtWhen(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  const today = dayKey(new Date());
  const day = dayKey(d);
  if (day === today) return hhmm(d);
  if (day === addDays(today, 1)) return "Tomorrow " + hhmm(d);
  const days = (Date.parse(day) - Date.parse(today)) / 86400000;
  if (days > 1 && days < 7) {
    return d.toLocaleDateString("en-GB", { weekday: "short", timeZone: TZ }) + " " + hhmm(d);
  }
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: TZ });
}

/** 15 → "15 min", 60 → "1 h", 90 → "1 h 30". */
function fmtMinutes(min) {
  if (min < 60) return min + " min";
  return Math.floor(min / 60) + " h" + (min % 60 ? " " + String(min % 60).padStart(2, "0") : "");
}

/** Only http(s) links become clickable (never javascript: or data:). */
function safeUrl(url) {
  return typeof url === "string" && /^https?:\/\//i.test(url);
}

/** The focus card's top line: "Now · 21:00 · desk · ~15 min", else the
 *  deadline. Sentence case, never caps. */
function focusMeta(t) {
  const parts = ["Now"];
  if (t.plan_at) parts.push(fmtWhen(t.plan_at));
  else if (t.due_at) parts.push("by " + fmtWhen(t.due_at));
  if (t.plan_where) parts.push(t.plan_where);
  if (t.estimate_min) parts.push("~" + fmtMinutes(t.estimate_min));
  return parts.join(" · ");
}

/** "Later" choices, as offset-less Casablanca times the API reads as local. */
function laterOptions() {
  const now = new Date();
  const hour = Number(now.toLocaleString("en-GB", { hour: "numeric", hourCycle: "h23", timeZone: TZ }));
  const today = dayKey(now);
  const inHour = new Date(now.getTime() + 3600000);
  const options = [{ label: "In 1 hour", hint: hhmm(inHour), until: inHour.toISOString() }];
  if (hour < 19) options.push({ label: "Tonight", hint: "20:00", until: today + "T20:00" });
  options.push({ label: "Tomorrow", hint: "09:00", until: addDays(today, 1) + "T09:00" });
  return options;
}

// --- Bottom sheet and snackbar ----------------------------------------------

/** Opens the bottom sheet with [rows]: { label, hint?, run }. A row closes
 *  the sheet, then runs. */
function openSheet(title, sub, rows) {
  $("sheet-title").textContent = title;
  $("sheet-sub").textContent = sub || "";
  const box = $("sheet-rows");
  box.replaceChildren();
  for (const r of rows) {
    const b = document.createElement("button");
    b.className = "sheet-row";
    const label = document.createElement("span");
    label.textContent = r.label;
    b.append(label);
    if (r.hint) {
      const hint = document.createElement("span");
      hint.className = "hint";
      hint.textContent = r.hint;
      b.append(hint);
    }
    b.addEventListener("click", () => {
      closeSheet();
      r.run();
    });
    box.append(b);
  }
  $("sheet").classList.remove("hidden");
  box.firstChild && box.firstChild.focus();
}

function closeSheet() {
  $("sheet").classList.add("hidden");
}

$("sheet").addEventListener("click", (e) => {
  if (e.target.hasAttribute("data-close")) closeSheet();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") closeSheet();
});

let toastTimer = null;

/** A snackbar at the bottom for a few seconds, with an optional action. */
function toast(text, actionLabel, action) {
  const box = $("toast");
  $("toast-text").textContent = text;
  const button = $("toast-action");
  button.classList.toggle("hidden", !actionLabel);
  button.textContent = actionLabel || "";
  button.onclick = actionLabel
    ? () => {
        box.classList.add("hidden");
        action();
      }
    : null;
  // Restart the pop-in animation.
  box.classList.add("hidden");
  void box.offsetWidth;
  box.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.add("hidden"), 6000);
}

// --- Rendering --------------------------------------------------------------

function showBoard(show) {
  $("board").classList.toggle("hidden", !show);
  $("token-box").classList.toggle("hidden", show);
}

/** One task row in a list card: title, first step, time and estimate. */
function taskRow(t) {
  const row = document.createElement("div");
  row.className = "row";
  const main = document.createElement("div");
  main.className = "row-main";
  const title = document.createElement("div");
  title.className = "row-title";
  title.textContent = t.title;
  main.append(title);
  if (t.first_step) {
    const sub = document.createElement("div");
    sub.className = "row-sub";
    sub.textContent = "Start with: " + t.first_step;
    main.append(sub);
  }
  row.append(main);
  const when = document.createElement("span");
  when.className = "row-when";
  when.textContent = [fmtWhen(t.plan_at || t.due_at), t.estimate_min ? "~" + fmtMinutes(t.estimate_min) : ""]
    .filter(Boolean)
    .join("\n");
  row.append(when);
  if (safeUrl(t.url)) {
    const go = document.createElement("a");
    go.className = "row-link";
    go.href = t.url;
    go.target = "_blank";
    go.rel = "noopener noreferrer";
    go.textContent = "↗";
    go.setAttribute("aria-label", "Open link for " + t.title);
    row.append(go);
  }
  return row;
}

async function loadBoard() {
  try {
    const b = await api("/api/board");
    doneToday = b.doneToday;
    const day = new Date(b.date + "T12:00:00Z").toLocaleDateString("en-GB", {
      weekday: "long", day: "numeric", month: "long", timeZone: "UTC",
    });
    $("date").textContent = b.doneToday ? day + " · " + b.doneToday + " done" : day;
    const f = b.focus;
    focusId = f ? f.id : null;
    focusTitle = f ? f.first_step || f.title : "";
    if (f) {
      $("focus-meta").textContent = focusMeta(f);
      $("focus-step").textContent = f.first_step || f.title;
      $("focus-task").textContent = f.first_step ? f.title : f.detail || "";
    } else {
      $("focus-meta").textContent = "Now";
      $("focus-step").textContent = "All clear. Nothing left today.";
      $("focus-task").textContent = "";
    }
    // Go-to link (issue #12): a small secondary action, never the main one.
    const openLink = $("focus-open");
    if (f && safeUrl(f.url)) {
      openLink.href = f.url;
      openLink.classList.remove("hidden");
    } else {
      openLink.classList.add("hidden");
      openLink.removeAttribute("href");
    }
    showBlocks(f);
    $("btn-done").disabled = !f;
    $("btn-later").classList.toggle("hidden", !f);

    // Then: one card, or one per group (issue #12), ungrouped last.
    const box = $("upcoming");
    box.replaceChildren();
    $("then-title").classList.toggle("hidden", b.upcoming.length === 0);
    const grouped = b.upcoming.some((t) => t.group);
    const ordered = grouped
      ? [...b.upcoming].sort((x, y) => (x.group ? 0 : 1) - (y.group ? 0 : 1))
      : b.upcoming;
    let card = null;
    let lastGroup;
    for (const t of ordered) {
      if (!card || (grouped && t.group !== lastGroup)) {
        if (grouped) {
          const h = document.createElement("h3");
          h.className = "group-title";
          h.textContent = t.group || "Tasks";
          box.append(h);
        }
        card = document.createElement("div");
        card.className = "list";
        box.append(card);
        lastGroup = t.group;
      }
      card.append(taskRow(t));
    }
    showBoard(true);
  } catch (err) {
    if (err.message === "unauthorized") {
      showBoard(false);
      $("token-error").classList.remove("hidden");
    } else {
      toast("Couldn't reach the board. Check your connection.", "Retry", loadBoard);
    }
  }
}

// --- Blocks (Muse's content for a task) --------------------------------------

/** Text with its web links made clickable, built from nodes (never HTML). */
function linkified(text) {
  const p = document.createElement("p");
  let last = 0;
  for (const m of text.matchAll(/https?:\/\/[^\s<>"]+/gi)) {
    const url = m[0].replace(/[.,;:!?)\]]+$/, "");
    p.append(text.slice(last, m.index));
    const a = document.createElement("a");
    a.href = url;
    a.target = "_blank";
    a.rel = "noopener noreferrer";
    a.textContent = url.replace(/^https?:\/\//i, "");
    p.append(a);
    last = m.index + url.length;
  }
  p.append(text.slice(last));
  return p;
}

/** The focus card's "More": hidden when the task has no blocks; stays
 *  open across refreshes while it's the same task. */
function showBlocks(task) {
  const more = $("focus-more");
  const box = $("focus-blocks");
  focusBlocks = task && Array.isArray(task.blocks) ? task.blocks : [];
  box.replaceChildren();
  focusBlocks.forEach((b, i) => {
    if (b.type === "heading") {
      const h = document.createElement("h3");
      h.textContent = b.text;
      box.append(h);
    } else if (b.type === "text") {
      box.append(linkified(b.text));
    } else if (b.type === "button" && safeUrl(b.url)) {
      const a = document.createElement("a");
      a.className = "block-btn";
      a.href = b.url;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      a.textContent = b.label + " ↗";
      box.append(a);
    } else if (b.type === "check") {
      const label = document.createElement("label");
      label.className = "check";
      const input = document.createElement("input");
      input.type = "checkbox";
      input.checked = !!b.done;
      input.addEventListener("change", () => tickBlock(task.id, i, input));
      const span = document.createElement("span");
      span.textContent = b.text;
      label.append(input, span);
      box.append(label);
    } else if (b.type === "toggle") {
      const d = document.createElement("details");
      d.className = "toggle";
      const s = document.createElement("summary");
      s.textContent = b.text;
      d.append(s, linkified(b.body));
      box.append(d);
    }
    // Unknown types are skipped, so newer content never shows broken.
  });
  const has = box.childElementCount > 0;
  more.classList.toggle("hidden", !has);
  if (!task || moreOpenFor !== task.id) more.open = false;
}

$("focus-more").addEventListener("toggle", () => {
  moreOpenFor = $("focus-more").open ? focusId : null;
});

/** Ticks a checklist item at once, then saves the whole list. */
async function tickBlock(taskId, index, input) {
  const blocks = focusBlocks.map((b, i) => (i === index ? { ...b, done: input.checked } : b));
  focusBlocks = blocks;
  try {
    await api("/api/tasks/" + taskId, "PATCH", { blocks });
  } catch (e) {
    input.checked = !input.checked;
    focusBlocks = focusBlocks.map((b, i) => (i === index ? { ...b, done: input.checked } : b));
    toast("Couldn't save that. Try again.");
  }
}

// --- Events -----------------------------------------------------------------

$("token-save").addEventListener("click", () => {
  token = $("token-input").value.trim();
  if (!token) return;
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch (_) {}
  $("token-error").classList.add("hidden");
  loadBoard();
});

$("btn-done").addEventListener("click", async () => {
  if (!focusId) return;
  const doneId = focusId;
  try {
    await api("/api/tasks/" + doneId + "/complete", "POST");
    if (navigator.vibrate) navigator.vibrate([12, 60, 18]);
    await loadBoard();
    toast("Done. " + doneToday + " today.", "Undo", async () => {
      try {
        await api("/api/tasks/" + doneId + "/reopen", "POST");
        toast("Back on your board.");
        loadBoard();
      } catch (e) {
        toast("Couldn't undo it. Try again.");
      }
    });
  } catch (e) {
    toast("Couldn't mark it done. Try again.");
  }
});

$("btn-later").addEventListener("click", () => {
  if (!focusId) return;
  const id = focusId;
  openSheet(
    "Come back to it…",
    focusTitle,
    laterOptions().map((opt) => ({
      label: opt.label,
      hint: opt.hint,
      run: async () => {
        try {
          await api("/api/tasks/" + id + "/snooze", "POST", { until: opt.until });
          toast("Moved. Reminder at " + opt.hint + ".");
          loadBoard();
        } catch (e) {
          toast("Couldn't move it. Try again.");
        }
      },
    })),
  );
});

$("btn-menu").addEventListener("click", () => {
  const rows = [
    { label: "Refresh", run: loadBoard },
    { label: "Theme", hint: THEME_NAMES[currentTheme()], run: cycleTheme },
  ];
  if (token) {
    rows.push({
      label: "Change token",
      run: () => {
        try {
          localStorage.removeItem(TOKEN_KEY);
        } catch (_) {}
        token = "";
        $("token-input").value = "";
        showBoard(false);
      },
    });
  }
  openSheet("Muse Board", "", rows);
});

// --- Theme (issue #9): auto → light → dark, remembered on this device --------

const THEME_KEY = "muse-board-theme";
const THEMES = ["auto", "light", "dark"];
const THEME_NAMES = { auto: "Auto", light: "Light", dark: "Dark" };

function currentTheme() {
  const t = document.documentElement.dataset.theme;
  return t === "light" || t === "dark" ? t : "auto";
}

function cycleTheme() {
  const next = THEMES[(THEMES.indexOf(currentTheme()) + 1) % THEMES.length];
  if (next === "auto") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = next;
  try {
    if (next === "auto") localStorage.removeItem(THEME_KEY);
    else localStorage.setItem(THEME_KEY, next);
  } catch (_) {}
  toast("Theme: " + THEME_NAMES[next]);
}

if (token) {
  loadBoard();
} else {
  showBoard(false);
}
