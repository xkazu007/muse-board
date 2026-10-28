"use strict";

const $ = (id) => document.getElementById(id);
const TOKEN_KEY = "muse-board-token";

let token = localStorage.getItem(TOKEN_KEY) || "";
let focusId = null;

async function api(path, method) {
  const res = await fetch(path, {
    method: method || "GET",
    headers: { Authorization: "Bearer " + token },
  });
  if (res.status === 401) throw new Error("unauthorized");
  if (!res.ok) throw new Error("http " + res.status);
  return res.json();
}

function fmtDue(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "Africa/Casablanca",
  });
}

async function loadBoard() {
  const status = $("status");
  status.textContent = "Loading…";
  try {
    const b = await api("/api/board");
    $("date").textContent = new Date(b.date + "T12:00:00").toLocaleDateString(
      "en-GB",
      { weekday: "long", day: "numeric", month: "long" },
    );
    if (b.focus) {
      focusId = b.focus.id;
      $("focus-title").textContent = b.focus.title;
      $("focus-detail").textContent =
        (b.focus.detail ? b.focus.detail + " · " : "") + fmtDue(b.focus.due_at);
      $("btn-done").disabled = false;
      $("btn-snooze").disabled = false;
    } else {
      focusId = null;
      $("focus-title").textContent = "All clear — nothing left today.";
      $("focus-detail").textContent = "";
      $("btn-done").disabled = true;
      $("btn-snooze").disabled = true;
    }
    const hour = new Date().toLocaleString("en-GB", {
      hour: "numeric",
      hour12: false,
      timeZone: "Africa/Casablanca",
    });
    $("waiting").classList.toggle("hidden", !(parseInt(hour, 10) >= 20 && b.focus));
    const ul = $("upcoming");
    ul.innerHTML = "";
    if (b.upcoming.length === 0) {
      ul.innerHTML = "<li><span class='d'>Nothing else lined up.</span></li>";
    }
    for (const t of b.upcoming) {
      const li = document.createElement("li");
      const title = document.createElement("div");
      title.className = "t";
      title.textContent = t.title;
      const detail = document.createElement("div");
      detail.className = "d";
      detail.textContent = (t.detail ? t.detail + " · " : "") + fmtDue(t.due_at);
      li.appendChild(title);
      li.appendChild(detail);
      ul.appendChild(li);
    }
    $("done-count").textContent = "Done today: " + b.doneToday;
    status.textContent = "";
    $("board").classList.remove("hidden");
    $("token-box").classList.add("hidden");
  } catch (err) {
    if (err.message === "unauthorized") {
      $("board").classList.add("hidden");
      $("token-box").classList.remove("hidden");
      $("token-error").classList.remove("hidden");
    } else {
      status.textContent = "Couldn't reach the board. Check your connection and tap Refresh.";
    }
  }
}

$("token-save").addEventListener("click", () => {
  token = $("token-input").value.trim();
  if (!token) return;
  localStorage.setItem(TOKEN_KEY, token);
  $("token-error").classList.add("hidden");
  loadBoard();
});

$("btn-done").addEventListener("click", async () => {
  if (!focusId) return;
  try {
    await api("/api/tasks/" + focusId + "/complete", "POST");
    loadBoard();
  } catch (e) {
    $("status").textContent = "Couldn't mark it done. Try again.";
  }
});

$("btn-snooze").addEventListener("click", async () => {
  if (!focusId) return;
  try {
    await api("/api/tasks/" + focusId + "/snooze", "POST");
    loadBoard();
  } catch (e) {
    $("status").textContent = "Couldn't snooze it. Try again.";
  }
});

$("btn-refresh").addEventListener("click", loadBoard);
$("btn-logout").addEventListener("click", () => {
  localStorage.removeItem(TOKEN_KEY);
  token = "";
  $("token-input").value = "";
  $("board").classList.add("hidden");
  $("token-box").classList.remove("hidden");
});

if (token) {
  loadBoard();
} else {
  $("token-box").classList.remove("hidden");
}
