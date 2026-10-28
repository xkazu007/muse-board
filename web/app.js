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

/** Only http(s) links become clickable (never javascript: or data:). */
function safeUrl(url) {
  return typeof url === "string" && /^https?:\/\//i.test(url);
}

/** The focus card's top line: "Now · 21:00 · desk", else the deadline. */
function focusMeta(t) {
  const parts = ["Now"];
  if (t.plan_at) parts.push(fmtWhen(t.plan_at));
  else if (t.due_at) parts.push("by " + fmtWhen(t.due_at));
  if (t.plan_where) parts.push(t.plan_where);
  return parts.join(" · ");
}

/** "Later" choices, as offset-less Casablanca times the API reads as local. */
function laterOptions() {
  const now = new Date();
  const hour = Number(now.toLocaleString("en-GB", { hour: "numeric", hourCycle: "h23", timeZone: TZ }));
  const today = dayKey(now);
  const options = [{ label: "In 1 hour", until: new Date(now.getTime() + 3600000).toISOString() }];
  if (hour < 19) options.push({ label: "Tonight 20:00", until: today + "T20:00" });
  options.push({ label: "Tomorrow 09:00", until: addDays(today, 1) + "T09:00" });
  return options;
}

// --- Rendering --------------------------------------------------------------

function showBoard(show) {
  $("board").classList.toggle("hidden", !show);
  $("token-box").classList.toggle("hidden", show);
}

function closeLater() {
  $("later-menu").classList.add("hidden");
  $("btn-later").setAttribute("aria-expanded", "false");
}

async function loadBoard() {
  const status = $("status");
  try {
    const b = await api("/api/board");
    $("date").textContent = new Date(b.date + "T12:00:00Z").toLocaleDateString("en-GB", {
      weekday: "short", day: "numeric", month: "short", timeZone: "UTC",
    });
    closeLater();
    const f = b.focus;
    focusId = f ? f.id : null;
    if (f) {
      $("focus-meta").textContent = focusMeta(f);
      $("focus-step").textContent = f.first_step || f.title;
      $("focus-task").textContent = f.first_step ? f.title : f.detail || "";
    } else {
      $("focus-open").classList.add("hidden");
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
    $("btn-later").disabled = !f;

    const ul = $("upcoming");
    ul.replaceChildren();
    if (b.upcoming.length === 0) {
      const li = document.createElement("li");
      li.className = "muted";
      li.textContent = "Nothing else lined up.";
      ul.appendChild(li);
    }
    // Groups (issue #12): headings when any upcoming task has one,
    // ungrouped ones last under "Tasks".
    const grouped = b.upcoming.some((t) => t.group);
    const ordered = grouped
      ? [...b.upcoming].sort((x, y) => (x.group ? 0 : 1) - (y.group ? 0 : 1))
      : b.upcoming;
    let lastGroup;
    for (const t of ordered) {
      if (grouped && t.group !== lastGroup) {
        const heading = document.createElement("li");
        heading.className = "group-heading";
        heading.textContent = t.group || "Tasks";
        ul.appendChild(heading);
        lastGroup = t.group;
      }
      const li = document.createElement("li");
      const title = document.createElement("span");
      title.textContent = t.title;
      const when = document.createElement("span");
      when.className = "when";
      when.textContent = fmtWhen(t.plan_at || t.due_at);
      li.append(title);
      if (safeUrl(t.url)) {
        const go = document.createElement("a");
        go.className = "row-link";
        go.href = t.url;
        go.target = "_blank";
        go.rel = "noopener noreferrer";
        go.textContent = "↗";
        go.setAttribute("aria-label", "Open link for " + t.title);
        li.append(go);
      }
      li.append(when);
      ul.appendChild(li);
    }
    $("done-count").textContent = b.doneToday + " done today";
    showBoard(true);
  } catch (err) {
    if (err.message === "unauthorized") {
      showBoard(false);
      $("token-error").classList.remove("hidden");
    } else {
      status.textContent = "Couldn't reach the board. Check your connection and tap Refresh.";
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
    $("status").textContent = "Couldn't save that. Try again.";
  }
}

/** "Done. Undo": the status line's undo reopens the task. */
function showUndo(taskId) {
  const status = $("status");
  status.textContent = "Done. ";
  const undo = document.createElement("button");
  undo.className = "link";
  undo.textContent = "Undo";
  undo.addEventListener("click", async () => {
    try {
      await api("/api/tasks/" + taskId + "/reopen", "POST");
      status.textContent = "";
      loadBoard();
    } catch (e) {
      status.textContent = "Couldn't undo it. Try again.";
    }
  });
  status.appendChild(undo);
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
    await loadBoard();
    showUndo(doneId);
  } catch (e) {
    $("status").textContent = "Couldn't mark it done. Try again.";
  }
});

$("btn-later").addEventListener("click", () => {
  const menu = $("later-menu");
  const opening = menu.classList.contains("hidden");
  if (!opening) return closeLater();
  const box = $("later-options");
  box.replaceChildren();
  for (const opt of laterOptions()) {
    const chip = document.createElement("button");
    chip.className = "chip";
    chip.textContent = opt.label;
    chip.addEventListener("click", async () => {
      if (!focusId) return;
      try {
        await api("/api/tasks/" + focusId + "/snooze", "POST", { until: opt.until });
        $("status").textContent = "Moved. You'll get a reminder then.";
        loadBoard();
      } catch (e) {
        $("status").textContent = "Couldn't move it. Try again.";
      }
    });
    box.appendChild(chip);
  }
  menu.classList.remove("hidden");
  $("btn-later").setAttribute("aria-expanded", "true");
});

$("later-cancel").addEventListener("click", closeLater);
$("btn-refresh").addEventListener("click", () => {
  $("status").textContent = "";
  loadBoard();
});
$("btn-logout").addEventListener("click", () => {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch (_) {}
  token = "";
  $("token-input").value = "";
  showBoard(false);
});

// --- Theme (issue #9): auto → light → dark, remembered on this device --------

const THEME_KEY = "muse-board-theme";
const THEMES = ["auto", "light", "dark"];

function currentTheme() {
  const t = document.documentElement.dataset.theme;
  return t === "light" || t === "dark" ? t : "auto";
}

function showTheme(mode) {
  const button = $("theme-toggle");
  button.dataset.mode = mode;
  button.setAttribute("aria-label", "Theme: " + mode);
  button.title = "Theme: " + mode;
}

$("theme-toggle").addEventListener("click", () => {
  const next = THEMES[(THEMES.indexOf(currentTheme()) + 1) % THEMES.length];
  if (next === "auto") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = next;
  try {
    if (next === "auto") localStorage.removeItem(THEME_KEY);
    else localStorage.setItem(THEME_KEY, next);
  } catch (_) {}
  showTheme(next);
});
showTheme(currentTheme());

if (token) {
  loadBoard();
} else {
  showBoard(false);
}
