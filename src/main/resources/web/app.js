// dkLedger web dashboard. Author: direk james
// Read-only: every request is a GET to /api/... Data is inserted with textContent only.
"use strict";

(function () {
  const SVG = "http://www.w3.org/2000/svg";
  const $ = (id) => document.getElementById(id);
  let days = 7;
  let me = null;
  let refreshTimer = null;
  let lastOverview = null;
  let lastSources = null;

  try {
    const saved = parseInt(localStorage.getItem("dkledger.days"), 10);
    if ([1, 7, 30].includes(saved)) days = saved;
  } catch (e) { /* storage blocked: keep the default */ }

  // ------------------------------------------------------------------ formatting

  const money = (v) => {
    if (v == null || isNaN(v)) return "–";
    const a = Math.abs(v);
    const s = a >= 100 ? Math.round(a).toLocaleString("en-US") : a.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return (v < 0 ? "-$" : "$") + s;
  };
  const compact = (v) => {
    if (v == null || isNaN(v)) return "–";
    const a = Math.abs(v);
    let s;
    if (a >= 1e9) s = (a / 1e9).toFixed(a >= 1e10 ? 0 : 1) + "B";
    else if (a >= 1e6) s = (a / 1e6).toFixed(a >= 1e7 ? 0 : 1) + "M";
    else if (a >= 1e3) s = (a / 1e3).toFixed(a >= 1e4 ? 0 : 1) + "K";
    else s = a >= 100 ? Math.round(a).toString() : a.toFixed(a === Math.round(a) ? 0 : 2);
    return (v < 0 ? "-$" : "$") + s.replace(".0B", "B").replace(".0M", "M").replace(".0K", "K");
  };
  const count = (v) => (v == null ? "–" : Number(v).toLocaleString("en-US"));
  const pct = (v) => (v == null || isNaN(v) ? "–" : (v * 100).toFixed(Math.abs(v) < 0.1 ? 1 : 0) + "%");
  const signedPct = (v) => (v == null || isNaN(v) ? "not enough data yet" : (v >= 0 ? "+" : "−") + pct(Math.abs(v)));
  const hours = (m) => (m < 60 ? m + " min" : (m / 60).toFixed(1) + " h");
  const dateTime = (t) => new Date(t).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  const date = (t) => new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  const periodName = () => (days === 1 ? "today" : days + " days");

  // ------------------------------------------------------------------ DOM helpers (textContent only)

  function el(tag, attrs, ...children) {
    const e = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v == null) continue;
      if (k === "class") e.className = v;
      else if (k === "text") e.textContent = v;
      else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v);
    }
    for (const c of children) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
    return e;
  }
  function svg(tag, attrs, ...children) {
    const e = document.createElementNS(SVG, tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v == null) continue;
      if (k === "class") e.setAttribute("class", v);
      else if (k === "text") e.textContent = v;
      else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v);
    }
    for (const c of children) if (c != null) e.append(c);
    return e;
  }
  const clear = (node) => { while (node.firstChild) node.removeChild(node.firstChild); return node; };
  const empty = (text) => el("div", { class: "empty", text });

  function table(columns, rows) {
    const head = el("tr", null, ...columns.map((c) => el("th", { class: c.num ? "num" : null, text: c.label })));
    const body = rows.map((r) => el("tr", null, ...columns.map((c) => {
      const v = c.value(r);
      return el("td", { class: (c.num ? "num " : "") + (c.muted ? "muted" : "") }, v instanceof Node ? v : String(v));
    })));
    return el("div", { class: "table-wrap" }, el("table", null, el("thead", null, head), el("tbody", null, ...body)));
  }

  // ------------------------------------------------------------------ tooltip

  const tip = $("tooltip");
  function showTip(x, y, title, rows) {
    clear(tip);
    tip.append(el("div", { class: "tt-title", text: title }));
    for (const [label, value, key] of rows) {
      tip.append(el("div", { class: "tt-row" }, el("span", null, key ? el("span", { class: "tt-key" }) : null, label), el("span", { class: "tt-value", text: value })));
    }
    tip.hidden = false;
    const r = tip.getBoundingClientRect();
    let left = x + 14, top = y + 14;
    if (left + r.width > window.innerWidth - 8) left = x - r.width - 14;
    if (top + r.height > window.innerHeight - 8) top = y - r.height - 14;
    tip.style.left = Math.max(8, left) + "px";
    tip.style.top = Math.max(8, top) + "px";
  }
  const hideTip = () => { tip.hidden = true; };

  // ------------------------------------------------------------------ data

  async function get(path) {
    const res = await fetch("/api/" + path, { credentials: "same-origin", headers: { Accept: "application/json" } });
    if (res.status === 401) { signedOut(); throw new Error("signin"); }
    if (res.status === 404) return null;
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  function signedOut(reason) {
    $("app").hidden = true;
    $("who").hidden = true;
    $("signin").hidden = false;
    if (reason === "expired") $("signin-text").textContent = "That sign-in link was already used or is older than 5 minutes. Type /ledger web in game for a new one.";
    else if (reason === "busy") $("signin-text").textContent = "Too many sign-in attempts. Wait a minute, then type /ledger web in game for a new link.";
    if (refreshTimer) clearInterval(refreshTimer);
  }

  async function start() {
    const params = new URLSearchParams(location.search);
    const signin = params.get("signin");
    if (signin) history.replaceState(null, "", "/");
    try {
      me = await get("me");
    } catch (e) {
      signedOut(signin);
      return;
    }
    $("version").textContent = "v" + me.version;
    $("who-name").textContent = me.name;
    $("who").hidden = false;
    $("signin").hidden = true;
    $("app").hidden = false;
    setPeriodButtons();
    await loadAll();
    refreshTimer = setInterval(loadAll, 60_000);
  }

  function setPeriodButtons() {
    for (const b of document.querySelectorAll(".seg[data-days]")) b.setAttribute("aria-pressed", String(Number(b.dataset.days) === days));
  }

  async function loadAll() {
    const main = $("app");
    main.classList.add("loading"); // keep the frame while refetching
    try {
      const [overview, sources, top, alerts, items, reasons] = await Promise.all([
        get("overview?days=" + days), get("sources?days=" + days), get("top?days=" + days),
        get("alerts"), get("items?days=" + days), get("reasons?days=" + days)]);
      lastOverview = overview;
      lastSources = sources;
      renderOverview(overview);
      renderSources(sources);
      renderTop(top);
      renderAlerts(alerts);
      renderItems(items);
      renderReasons(reasons);
      $("updated").textContent = "Updated " + new Date().toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
    } catch (e) {
      if (e.message !== "signin") $("updated").textContent = "Couldn't refresh (" + e.message + "). Retrying in a minute.";
    } finally {
      main.classList.remove("loading");
    }
  }

  // ------------------------------------------------------------------ overview

  function tile(label, value, note) {
    return el("div", { class: "tile" }, el("div", { class: "label", text: label }), el("div", { class: "value", text: value, title: value }),
      note ? el("div", { class: "note", text: note }) : null);
  }

  function renderOverview(o) {
    const s = o.supply;
    $("hero-total").textContent = s.time ? money(s.total) : "–";
    $("hero-sub").textContent = s.time
      ? "24 hours " + signedPct(s.change24h) + " · 7 days " + signedPct(s.change7d) + " · wallets " + compact(s.wallet) + ", banks " + compact(s.bank)
      : "No count yet. The first one is taken a minute after the server starts.";
    const tiles = clear($("tiles"));
    tiles.append(
      tile("Per active player", compact(s.perActive), count(o.players.active) + " active in 24 h"),
      tile("Richest 10% hold", pct(s.topShare), "of all money"),
      tile("Open alerts", count(o.openAlerts), o.openAlerts ? "see Alerts below" : "all clear"),
      tile("Money created", compact(o.created), periodName() + ", by faucets"),
      tile("Money removed", compact(o.removed), periodName() + ", by sinks and fees"),
      tile("Players counted", count(o.players.accounts), "with a balance"));
    $("supply-sub").textContent = "Wallets and banks together, counted every hour. " + (days === 1 ? "Last 24 hours." : "Last " + days + " days.");
    renderSupplyChart(o.history);
    $("supply-table").replaceChildren(table([
      { label: "Time", value: (p) => dateTime(p.t) },
      { label: "Total", num: true, value: (p) => money(p.total) },
      { label: "Wallets", num: true, value: (p) => money(p.wallet) },
      { label: "Banks", num: true, value: (p) => money(p.bank) },
      { label: "Active players", num: true, value: (p) => count(p.active) }], o.history.slice().reverse()));
  }

  function niceMax(v) {
    if (v <= 0) return 1;
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
    return 10 * p;
  }

  function renderSupplyChart(points) {
    const box = clear($("supply-chart"));
    if (!points || points.length < 2) {
      box.append(empty("Not enough counts yet for a chart. One is taken every hour."));
      return;
    }
    const W = Math.max(320, box.clientWidth || 800), H = 240;
    const m = { l: 52, r: 72, t: 12, b: 26 };
    const xs = points.map((p) => p.t), ys = points.map((p) => p.total);
    const x0 = xs[0], x1 = xs[xs.length - 1];
    let lo = Math.min(...ys), hi = Math.max(...ys);
    // Start at zero unless the line would be flat; then zoom to the range with some room.
    if (lo > 0 && (hi - lo) / hi < 0.15) { const pad = (hi - lo) * 0.5 || hi * 0.05; lo = Math.max(0, lo - pad); hi = hi + pad; } else lo = 0;
    const step = niceMax((hi - lo) / 4);
    lo = Math.floor(lo / step) * step;
    hi = Math.ceil(hi / step) * step;
    if (hi === lo) hi = lo + step;
    const X = (t) => m.l + ((t - x0) / Math.max(1, x1 - x0)) * (W - m.l - m.r);
    const Y = (v) => m.t + (1 - (v - lo) / (hi - lo)) * (H - m.t - m.b);

    const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": "Money supply over time, " + money(ys[0]) + " to " + money(ys[ys.length - 1]) });
    for (let v = lo; v <= hi + step / 2; v += step) {
      root.append(svg("line", { class: v === lo ? "base-line" : "grid-line", x1: m.l, x2: W - m.r, y1: Y(v), y2: Y(v) }));
      root.append(svg("text", { class: "axis-text", x: m.l - 8, y: Y(v) + 4, "text-anchor": "end", text: compact(v) }));
    }
    const ticks = W < 520 ? 2 : W < 800 ? 3 : 5; // fewer dates on small screens so they never overlap
    for (let i = 0; i <= ticks; i++) {
      const t = x0 + ((x1 - x0) * i) / ticks;
      const label = days === 1 ? new Date(t).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })
        : new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric" });
      root.append(svg("text", { class: "axis-text", x: X(t), y: H - 6, "text-anchor": i === 0 ? "start" : i === ticks ? "end" : "middle", text: label }));
    }
    const line = points.map((p, i) => (i ? "L" : "M") + X(p.t).toFixed(1) + "," + Y(p.total).toFixed(1)).join("");
    root.append(svg("path", { class: "series-area", d: line + `L${X(x1).toFixed(1)},${Y(lo)}L${X(x0).toFixed(1)},${Y(lo)}Z` }));
    root.append(svg("path", { class: "series-line", d: line }));
    const last = points[points.length - 1];
    root.append(svg("circle", { class: "end-dot", cx: X(last.t), cy: Y(last.total), r: 4 }));
    root.append(svg("text", { class: "end-label", x: X(last.t) + 8, y: Y(last.total) + 4, text: compact(last.total) }));

    // Crosshair: snaps to the nearest count; the tooltip lists everything at that time.
    const cross = svg("line", { class: "crosshair", y1: m.t, y2: H - m.b, visibility: "hidden" });
    const dot = svg("circle", { class: "end-dot", r: 4, visibility: "hidden" });
    root.append(cross, dot);
    const hit = svg("rect", { x: m.l, y: m.t, width: W - m.l - m.r, height: H - m.t - m.b, fill: "transparent", tabindex: 0, "aria-label": "Money supply chart: move over it for values" });
    root.append(hit);
    const showAt = (clientX, clientY) => {
      const rect = root.getBoundingClientRect();
      const t = x0 + ((clientX - rect.left) * (W / rect.width) - m.l) / (W - m.l - m.r) * (x1 - x0);
      let best = points[0];
      for (const p of points) if (Math.abs(p.t - t) < Math.abs(best.t - t)) best = p;
      cross.setAttribute("x1", X(best.t)); cross.setAttribute("x2", X(best.t)); cross.setAttribute("visibility", "visible");
      dot.setAttribute("cx", X(best.t)); dot.setAttribute("cy", Y(best.total)); dot.setAttribute("visibility", "visible");
      showTip(clientX, clientY, dateTime(best.t), [["Total", money(best.total), true], ["Wallets", money(best.wallet)], ["Banks", money(best.bank)], ["Active players", count(best.active)]]);
    };
    hit.addEventListener("pointermove", (e) => showAt(e.clientX, e.clientY));
    hit.addEventListener("pointerleave", () => { hideTip(); cross.setAttribute("visibility", "hidden"); dot.setAttribute("visibility", "hidden"); });
    hit.addEventListener("focus", () => { const r = root.getBoundingClientRect(); showAt(r.right - 10, r.top + 20); });
    hit.addEventListener("blur", () => { hideTip(); cross.setAttribute("visibility", "hidden"); dot.setAttribute("visibility", "hidden"); });
    box.append(root);
  }

  // ------------------------------------------------------------------ sources (diverging bars)

  function renderSources(list) {
    const legend = clear($("sources-legend"));
    legend.append(el("span", null, el("span", { class: "key key-in" }), "Adds money (net)"), el("span", null, el("span", { class: "key key-out" }), "Removes money (net)"));
    const box = clear($("sources-chart"));
    const rows = (list || []).filter((s) => Math.abs(s.net) >= 0.01).slice(0, 14);
    if (!rows.length) { box.append(empty("No money moved " + (days === 1 ? "today" : "in the last " + days + " days") + " yet.")); return; }
    const W = Math.max(320, box.clientWidth || 800), rowH = 30, labelW = Math.min(220, W * 0.32), valueW = 78;
    const H = rows.length * rowH + 8;
    const maxAbs = Math.max(...rows.map((r) => Math.abs(r.net)));
    const hasNeg = rows.some((r) => r.net < 0), hasPos = rows.some((r) => r.net > 0);
    const plotL = labelW + (hasNeg ? valueW : 8), plotR = W - (hasPos ? valueW : 8);
    const zero = hasNeg && hasPos ? plotL + (plotR - plotL) / 2 : hasNeg ? plotR : plotL;
    const scale = (hasNeg && hasPos ? (plotR - plotL) / 2 : plotR - plotL) / maxAbs;
    const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, role: "img", "aria-label": "Net money by source" });
    root.append(svg("line", { class: "zero-line", x1: zero, x2: zero, y1: 0, y2: H - 4 }));
    rows.forEach((r, i) => {
      const y = i * rowH + 4, mid = y + rowH / 2;
      const len = Math.max(2, Math.abs(r.net) * scale);
      const bh = Math.min(18, rowH - 8);
      const g = svg("g", { class: "bar-row", tabindex: 0, "aria-label": r.name + ": " + money(r.net) });
      g.append(svg("rect", { x: 0, y, width: W, height: rowH, fill: "transparent" })); // the whole row is the hit target
      g.append(svg("text", { class: "bar-name", x: 0, y: mid - 1, text: r.name.length > 30 ? r.name.slice(0, 29) + "…" : r.name }));
      g.append(svg("text", { class: "bar-kind", x: 0, y: mid + 12, text: r.kind }));
      const pos = r.net >= 0;
      const x = pos ? zero : zero - len;
      // 4px rounded data end, square at the baseline.
      const rad = Math.min(4, len / 2);
      const d = pos
        ? `M${x},${mid - bh / 2}h${len - rad}a${rad},${rad} 0 0 1 ${rad},${rad}v${bh - 2 * rad}a${rad},${rad} 0 0 1 -${rad},${rad}h-${len - rad}z`
        : `M${x + len},${mid - bh / 2}h-${len - rad}a${rad},${rad} 0 0 0 -${rad},${rad}v${bh - 2 * rad}a${rad},${rad} 0 0 0 ${rad},${rad}h${len - rad}z`;
      g.append(svg("path", { class: "bar " + (pos ? "bar-in" : "bar-out"), d }));
      g.append(svg("text", { class: "bar-value", x: pos ? x + len + 6 : x - 6, y: mid + 4, "text-anchor": pos ? "start" : "end", text: (pos ? "+" : "") + compact(r.net) }));
      const tipRows = [["Net", money(r.net)], ["In", money(r.in)], ["Out", money(r.out)], ["Kind", r.kind]];
      g.addEventListener("pointermove", (e) => showTip(e.clientX, e.clientY, r.name, tipRows));
      g.addEventListener("pointerleave", hideTip);
      g.addEventListener("focus", () => { const b = g.getBoundingClientRect(); showTip(b.left + labelW, b.bottom, r.name, tipRows); });
      g.addEventListener("blur", hideTip);
      root.append(g);
    });
    box.append(root);
    const more = (list || []).length - rows.length;
    if (more > 0) box.append(el("div", { class: "sub", text: more + " more source(s) moved under $0.01 net or are past the top 14." }));
  }

  // ------------------------------------------------------------------ tables

  function playerLink(name) {
    return el("button", { type: "button", class: "linkish", text: name, onclick: () => lookUp(name) });
  }

  function renderTop(list) {
    const box = clear($("top"));
    if (!list || !list.length) { box.append(empty("Nobody earned anything " + (days === 1 ? "today" : "in this period") + " yet.")); return; }
    box.append(table([
      { label: "#", value: (r) => String(list.indexOf(r) + 1), muted: true },
      { label: "Player", value: (r) => playerLink(r.name) },
      { label: "Earned", num: true, value: (r) => money(r.earned) },
      { label: "Spent", num: true, value: (r) => money(r.spent) }], list));
  }

  let alertFilter = "open";
  function renderAlerts(list) {
    const box = clear($("alerts"));
    const all = list || [];
    const open = all.filter((a) => a.open);
    const shown = alertFilter === "open" ? open : all;
    const filter = el("div", { class: "alert-filter" },
      el("button", { type: "button", class: "seg", "aria-pressed": String(alertFilter === "open"), text: "Open (" + open.length + ")", onclick: () => { alertFilter = "open"; renderAlerts(list); } }),
      el("button", { type: "button", class: "seg", "aria-pressed": String(alertFilter === "all"), text: "All (" + all.length + ")", onclick: () => { alertFilter = "all"; renderAlerts(list); } }));
    box.append(filter);
    if (!shown.length) { box.append(empty(alertFilter === "open" ? "No open alerts." : "No alerts in the last 30 days.")); return; }
    for (const a of shown.slice(0, 40)) {
      const sevName = a.severity === "CRITICAL" ? "Critical" : a.severity === "WARNING" ? "Warning" : "Info";
      const item = el("div", { class: "alert" + (a.open ? "" : " resolved") },
        el("span", { class: "sev sev-" + a.severity }, el("span", { class: "icon", "aria-hidden": "true" }), sevName),
        el("div", { class: "alert-title", text: a.title }),
        el("div", { class: "alert-meta", text: "#" + a.id + " · " + a.check + " · " + dateTime(a.time) + (a.open ? "" : " · resolved by " + (a.resolvedBy || "?") + (a.note ? ": " + a.note : "")) }),
        a.detail ? el("details", null, el("summary", { text: "Details" }), el("pre", { text: a.detail })) : null);
      box.append(item);
    }
  }

  function renderItems(list) {
    const box = clear($("items"));
    if (me && me.tracking && !me.tracking.items) { box.append(empty("Item tracking is off (tracking.items in config.yml).")); return; }
    if (!list || !list.length) { box.append(empty("No shop trades " + (days === 1 ? "today" : "in this period") + " yet.")); return; }
    box.append(table([
      { label: "Item", value: (r) => r.item.replace(/_/g, " ") },
      { label: "Sold", num: true, value: (r) => count(r.sold) },
      { label: "Money paid out", num: true, value: (r) => money(r.soldMoney) },
      { label: "Avg sell", num: true, value: (r) => (r.sold ? money(r.soldMoney / r.sold) : "–"), muted: true },
      { label: "Bought", num: true, value: (r) => count(r.bought) },
      { label: "Money paid in", num: true, value: (r) => money(r.boughtMoney) },
      { label: "Avg buy", num: true, value: (r) => (r.bought ? money(r.boughtMoney / r.bought) : "–"), muted: true }], list.slice(0, 30)));
  }

  function renderReasons(list) {
    const box = clear($("reasons"));
    if (!list || !list.length) { box.append(empty("No reasons yet. They appear once dk plugins report them through dkLedger's API.")); return; }
    box.append(table([
      { label: "Source", value: (r) => r.source },
      { label: "Reason", value: (r) => r.reason },
      { label: "In", num: true, value: (r) => money(r.in) },
      { label: "Out", num: true, value: (r) => money(r.out) },
      { label: "Net", num: true, value: (r) => money(r.in - r.out) }], list.slice(0, 30)));
  }

  // ------------------------------------------------------------------ player

  async function lookUp(name) {
    name = (name || "").trim();
    if (!name) return;
    $("player-name").value = name;
    const box = $("player");
    box.classList.add("loading");
    try {
      const p = await get("player?name=" + encodeURIComponent(name));
      renderPlayer(p, name);
      $("player-card").scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (e) {
      if (e.message !== "signin") box.replaceChildren(empty("Couldn't look that up (" + e.message + ")."));
    } finally {
      box.classList.remove("loading");
    }
  }

  function list(title, lines, none) {
    return el("div", null, el("h3", { text: title }), lines && lines.length ? el("ul", { class: "plain" }, ...lines.map((l) => el("li", { text: l }))) : empty(none));
  }

  function renderPlayer(p, name) {
    const box = clear($("player"));
    if (!p) { box.append(empty(name + " has never joined this server.")); return; }
    if (p.frozen) box.append(el("div", { class: "frozen", text: "Frozen: " + (p.frozenReason || "no reason given") }));
    const a = p.activity;
    const played = a ? a.activeMinutes + a.afkMinutes : 0;
    const grid = el("div", { class: "player-grid" },
      tile("Earned today", money(p.earnedToday)),
      tile("Earned in 7 days", money(p.earned7d), played >= 60 ? money(p.earned7d / (played / 60)) + " per hour played" : null),
      tile("First joined", a && a.firstJoin ? date(a.firstJoin) : "unknown", a && a.firstJoin ? Math.floor((Date.now() - a.firstJoin) / 86400000) + " days ago" : null),
      tile("Played in 7 days", a ? hours(a.activeMinutes) + " active" : "–", a ? hours(a.afkMinutes) + " AFK · " + a.sessions + " joins on " + a.daysPlayed + " days" : null));
    box.append(el("h3", { text: p.name }), grid);
    const left = el("div", null,
      el("h3", { text: "Money by source (7 days)" }),
      p.sources.length ? table([
        { label: "Source", value: (r) => r.name },
        { label: "In", num: true, value: (r) => money(r.in) },
        { label: "Out", num: true, value: (r) => money(r.out) }], p.sources) : empty("No money moved in 7 days."),
      el("h3", { text: "Items traded (7 days)" }),
      p.items.length ? table([
        { label: "Item", value: (r) => r.item.replace(/_/g, " ") },
        { label: "Sold", num: true, value: (r) => count(r.sold) + " for " + money(r.soldMoney) },
        { label: "Bought", num: true, value: (r) => count(r.bought) + " for " + money(r.boughtMoney) }], p.items) : empty("No shop trades in 7 days."));
    const right = el("div", null,
      list("Newest entries", p.recent, "No entries yet."),
      list("Progress", a ? a.progress : [], "No progress recorded yet."),
      list("Staff notes", p.notes, "No notes."));
    box.append(el("div", { class: "player-cols" }, left, right));
  }

  // ------------------------------------------------------------------ events

  document.addEventListener("DOMContentLoaded", () => {
    for (const b of document.querySelectorAll(".seg[data-days]")) {
      b.addEventListener("click", () => {
        days = Number(b.dataset.days);
        try { localStorage.setItem("dkledger.days", String(days)); } catch (e) { /* ignore */ }
        setPeriodButtons();
        loadAll();
      });
    }
    $("player-form").addEventListener("submit", (e) => { e.preventDefault(); lookUp($("player-name").value); });
    $("logout").addEventListener("click", async () => {
      try { await fetch("/logout", { method: "POST", credentials: "same-origin" }); } catch (e) { /* ignore */ }
      signedOut();
      $("signin-text").textContent = "Signed out. Type /ledger web in game to sign in again.";
    });
    let resizeTimer = null;
    // Charts are drawn to the page width: redraw them (from the data already here) when it changes.
    window.addEventListener("resize", () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        if (lastOverview) renderSupplyChart(lastOverview.history);
        if (lastSources) renderSources(lastSources);
      }, 200);
    });
    start();
  });
})();
