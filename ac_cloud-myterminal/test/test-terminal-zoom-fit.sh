#!/usr/bin/env bash
# Tester: a terminal pane always fills its box, and two fingers change its font size.
#
# THE BUG THIS PINS. Opening the left sidebar (☰) and closing it again left the terminal
# truncated: it kept the narrow grid, and its shell kept the narrow window size, for good. The
# grid was recomputed only on a window "resize", a pane focus, a split or a close — and the
# sidebar toggle is none of those: it resizes #terms inside an unchanged window. A pane fitted
# while the sidebar was open (the keyboard came up, a tap focused it) therefore never learned the
# space came back. Now each pane watches ITS OWN box (ResizeObserver → fit → term.onResize →
# Transport.ptyResize → TIOCSWINSZ on the session side), whatever moved it: sidebar, rotation,
# keyboard, split/unsplit, multi-window.
#
# THE FEATURE. Pinch on a pane scales the font like Termux's ScaleGestureDetector: whole pixels,
# clamped to 6..32, one size for every pane, remembered across launches; the pane refits live and
# the PTY gets the final grid once, when the fingers lift. Two-finger events never reach xterm
# (no scroll, no selection, no tap, no long-press menu); one finger still scrolls. The tap menu
# gains "Reset font size".
#
# Everything is proved by RUNNING the shipped js/termtext.js and js/term.js under node, against a
# stub DOM, a fake xterm whose cell size follows its font, a fake FitAddon that measures the
# pane's box the way the real one does, and a fake session (Transport) recording every resize:
#   Z1  clamp: 6..32, whole pixels, garbage falls back to the default; pinch = start × scale
#   Z2  the grid for a box and a font (TermText.grid, and the live pane after a pinch)
#   Z3  persistence: the pinched size is stored, a fresh page's new pane opens at it
#   Z4  the PTY is resized once after a pinch, with the final grid, and never mid-gesture
#   Z5  the PTY is resized after a layout change of the pane's own box (no window resize)
#   Z6  sidebar open → close restores the full grid and the full PTY size (RED on the old code)
#   Z7  a pinch fires no tap, long-press menu, link or selection; one finger still reaches xterm
#   Z8  the tap menu keeps its items and offers Reset font size, which resets and refits
#   Z10 the size is a declared fleet-config preference: bridged to the native store (ide_prefs
#       terminal_font_size) so "apply all" carries it to a new phone; localStorage stays the
#       fallback and a size found only there is migrated once
#   Z9  wiring: the sidebar toggle refits, .pane takes two fingers from the page, scripts in order
#   MUT each property broken on a copy must turn its check red (incl. the old fit-on-resize-only code)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
command -v node >/dev/null || { echo "ERROR: node required" >&2; exit 2; }
FE="$APP/hub/src/main/assets/frontend"

exec node - "$FE" "$APP" <<'JS'
const fs = require("fs"), path = require("path"), vm = require("vm");
const [FE, APP] = process.argv.slice(2);
const SRC = {
  text: fs.readFileSync(path.join(FE, "js/termtext.js"), "utf8"),
  term: fs.readFileSync(path.join(FE, "js/term.js"), "utf8"),
  boot: fs.readFileSync(path.join(FE, "js/boot.js"), "utf8"),
  css: fs.readFileSync(path.join(FE, "css/style.css"), "utf8"),
  html: fs.readFileSync(path.join(FE, "index.html"), "utf8"),
  bridge: fs.readFileSync(path.join(APP, "hub/src/main/java/com/diegonmarcos/ide/TerminalBridge.kt"), "utf8"),
  prefs: fs.readFileSync(path.join(APP, "hub/src/main/java/com/diegonmarcos/ide/IdePrefs.kt"), "utf8"),
  fleet: fs.readFileSync(path.join(APP, "../fleet-private-data/fleet-config.json"), "utf8"),
};
let pass = 0, fail = 0;
const ok = (m) => { pass++; console.log("  PASS: " + m); };
const bad = (m) => { fail++; console.log("  FAIL: " + m); };
const check = (c, m) => (c ? ok(m) : bad(m));

// cell metrics of the fake renderer: a monospace cell is 0.6 × 1.2 of the font
const CELL = (font) => ({ w: font * 0.6, h: font * 1.2 });
// What xterm's FitAddon does with the pane's box (written here independently of TermText.grid).
const fitGrid = (w, h, font) => ({ cols: Math.max(2, Math.floor(w / CELL(font).w)), rows: Math.max(1, Math.floor(h / CELL(font).h)) });

function makeStorage() { const m = new Map(); return { m, getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k) }; }

// A page: term.js + termtext.js in a fresh context sharing [storage] with earlier pages.
function page(textSrc, termSrc, storage, native) {
  const observers = [];
  const raf = [];
  const el = (tag) => ({ tag, id: "", className: "", textContent: "", title: "", hidden: false, style: {}, children: [], parent: null, dataset: {},
    _cap: {}, _bub: {}, box: { w: 0, h: 0 }, tabIndex: 0,
    classList: { _s: new Set(), toggle(c, on) { if (on === undefined ? !this._s.has(c) : on) this._s.add(c); else this._s.delete(c); }, contains(c) { return this._s.has(c); } },
    append(...cs) { cs.forEach((c) => this.appendChild(c)); },
    appendChild(c) { c.parent = this; this.children.push(c); return c; },
    addEventListener(ev, f, o) { const cap = o === true || !!(o && o.capture); const t = cap ? this._cap : this._bub; (t[ev] = t[ev] || []).push(f); },
    fire(ev, arg) { const e2 = arg || mkEvent(); (this._bub[ev] || []).forEach((f) => f(e2)); (this._cap[ev] || []).forEach((f) => f(e2)); },
    remove() { if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this); this.parent = null; },
    getBoundingClientRect() { return { width: 200, height: 200, left: 0, top: 0 }; },
    contains(x) { for (let n = x; n; n = n.parent) if (n === this) return true; return false; },
    querySelector() { return null; },
    focus() {},
    set innerHTML(v) { this.children = []; },
  });
  const body = el("body");
  const find = (n, id) => { if (n.id === id) return n; for (const c of n.children) { const f = find(c, id); if (f) return f; } return null; };
  const document = { body, createElement: el, getElementById: (id) => find(body, id), addEventListener() {}, querySelectorAll: () => [] };
  class ResizeObserver {
    constructor(cb) { this.cb = cb; this.els = []; }
    observe(e) { this.els.push(e); observers.push({ el: e, ro: this }); }
    disconnect() { this.els = []; }
  }
  const sent = { resize: [], start: [], write: [] };
  class Terminal {
    constructor(o) { this.options = Object.assign({}, o); this.cols = 80; this.rows = 24; this._r = []; this._sel = false; this.element = null;
      this.parser = { registerOscHandler() {} }; this.buffer = { active: { length: 1, viewportY: 0, getLine: () => ({ isWrapped: false, translateToString: () => "$ " }) } }; }
    loadAddon(a) { a.activate(this); }
    open(host) { this.element = { parentElement: host }; }
    onResize(cb) { this._r.push(cb); }
    onData() {} onTitleChange() {} attachCustomKeyEventHandler() {} registerLinkProvider() {} registerMarker() { return null; }
    resize(c, r) { if (c === this.cols && r === this.rows) return; this.cols = c; this.rows = r; this._r.forEach((f) => f({ cols: c, rows: r })); }
    hasSelection() { return this._sel; } clearSelection() { this._sel = false; } getSelection() { return ""; }
    focus() {} dispose() {} paste() {} clear() {}
  }
  class FitAddon { activate(t) { this.t = t; }
    fit() { const h = this.t.element && this.t.element.parentElement; if (!h || !h.box.w || !h.box.h) return;
      const g = fitGrid(h.box.w, h.box.h, this.t.options.fontSize); this.t.resize(g.cols, g.rows); } }
  const copies = [];
  const sb = {
    document, console, Date, JSON, Math, Promise, Set, Map, Number, String, Object, Array,
    setTimeout: (f) => raf.push(f), clearTimeout() {},
    requestAnimationFrame: (f) => raf.push(f),
    ResizeObserver, Terminal,
    FitAddon: { FitAddon }, SearchAddon: { SearchAddon: class { activate() {} } },
    Tabs: { setTitle() {}, close() {} },
    Transport: {
      ptyStart: (id, c, r) => { sent.start.push([id, c, r]); return Promise.resolve(); },
      ptyWrite: (id, d) => sent.write.push([id, d]),
      ptyResize: (id, c, r) => sent.resize.push([id, c, r]),
      ptyKill() {}, onPty: () => Promise.resolve(() => {}), onPtyExit: () => Promise.resolve(() => {}),
    },
    localStorage: storage,
  };
  sb.window = sb; sb.innerWidth = 800; sb.innerHeight = 600;
  sb.AndroidTerm = { clipboardSet: (t) => copies.push(t), clipboardHas: () => false, openUrl: (u) => copies.push("open:" + u) };
  // native: the bridge's font-size store ({ v }, or { v, throws } for a bridge that refuses)
  if (native) {
    sb.AndroidTerm.getFontSize = () => { if (native.throws) throw new Error("bridge refused"); return native.v; };
    sb.AndroidTerm.setFontSize = (n) => { if (native.throws) throw new Error("bridge refused"); native.v = n; };
  }
  vm.createContext(sb);
  vm.runInContext(textSrc, sb);
  vm.runInContext(termSrc + "\n;this.MYK = MYK;", sb);
  const MYK = sb.MYK;
  MYK.config = { font: { size: 11 } };
  MYK._urlAt = () => "https://example.com/x";   // a link lies under every finger: a stray tap would copy it
  // Layout moved the pane's box: what the browser's ResizeObserver reports.
  const setBox = (host, w, h) => { host.box = { w, h }; for (const o of observers) if (o.el === host && o.ro.els.includes(host)) o.ro.cb([{ contentRect: { width: w, height: h } }]); };
  const flush = () => { for (let i = 0; i < 20 && raf.length; i++) raf.splice(0).forEach((f) => f()); };
  return { MYK, sb, sent, setBox, flush, document, copies, el };
}

function mkEvent(extra) {
  const e = Object.assign({ _stop: false, _prevented: false, cancelable: true, clientX: 10, clientY: 10 }, extra || {});
  e.preventDefault = () => { e._prevented = true; }; e.stopPropagation = () => { e._stop = true; };
  return e;
}
// Dispatch below the pane (at xterm's own element): host capture → xterm → host bubble, a stop
// ends it. atHost: at the target itself both phases run whatever stopPropagation says.
function dispatch(host, type, extra, xterm, atHost) {
  const e = mkEvent(extra);
  for (const f of host._cap[type] || []) f(e);
  if (!atHost) { if (e._stop) return e; xterm.push(type + ":" + (e.touches ? e.touches.length : 0)); }
  for (const f of host._bub[type] || []) f(e);
  return e;
}
const touches = (...pts) => pts.map(([x, y]) => ({ clientX: x, clientY: y }));

async function newPane(P, w, h) {
  const container = P.el("div");
  const id = await P.MYK.makePane("t1", container);
  const p = P.MYK.panes.get(id);
  P.setBox(p.host, w, h);
  P.MYK.focusPane(id);   // Tabs.newTab → activate → focusPane, as the app does
  P.flush();
  return { id, p };
}

async function verdict(textSrc, termSrc) {
  const r = {};
  const T = (() => { const s = { module: { exports: {} } }; vm.runInNewContext(textSrc, s); return s.module.exports; })();
  r.z1 = T.clampFont(3) === 6 && T.clampFont(40) === 32 && T.clampFont(12.4) === 12 && T.clampFont(12.6) === 13
    && T.clampFont("abc", 11) === 11 && T.clampFont(undefined, 14) === 14 && T.clampFont(-5, 11) === 11 && T.clampFont("18") === 18
    && T.pinchFont(11, 2) === 22 && T.pinchFont(11, 10) === 32 && T.pinchFont(11, 0.1) === 6 && T.pinchFont(11, 1.04) === 11
    && T.pinchFont(11, NaN) === 11 && T.pinchFont(11, 0) === 11 && T.FONT_MIN === 6 && T.FONT_MAX === 32;
  const g1 = T.grid ? T.grid(660, 480, 6.6, 13.2) : null, g2 = T.grid ? T.grid(1, 1, 6, 12) : null;
  r.z2pure = !!g1 && g1.cols === 100 && g1.rows === 36 && g2.cols === 2 && g2.rows === 1 && T.grid(10, 10, 0, 1) === null;

  // Z6: the sidebar. #terms is 1000 wide with it closed, 760 with it open (240px sidebar).
  const P3 = page(textSrc, termSrc, makeStorage());
  const s = await newPane(P3, 1000, 600);
  const wide = fitGrid(1000, 600, 11), narrow = fitGrid(760, 600, 11);
  r.z6start = s.p.term.cols === wide.cols;
  P3.sent.resize.length = 0;
  // open it: the box narrows and the keyboard comes up (window resize → boot.js fits the tab)
  P3.setBox(s.p.host, 760, 600);
  P3.MYK._fitTab("t1");
  P3.flush();
  r.z6open = s.p.term.cols === narrow.cols;
  // close it: the box is wide again, and NOTHING else happens — no window resize, no tap
  P3.setBox(s.p.host, 1000, 600);
  P3.flush();
  const last6 = P3.sent.resize[P3.sent.resize.length - 1];
  r.z6 = s.p.term.cols === wide.cols && s.p.term.rows === wide.rows && !!last6 && last6[1] === wide.cols && last6[2] === wide.rows;
  r.z6detail = JSON.stringify({ cols: s.p.term.cols, want: wide.cols, sent: P3.sent.resize });
  P3.setBox(s.p.host, 0, 0); P3.flush();
  r.z6hidden = s.p.term.cols === wide.cols;
  s.p.host.box = { w: 760, h: 600 };
  let refitted = true;
  try { P3.MYK.refitAll(); P3.flush(); } catch (e) { refitted = false; r.crash = (r.crash || "") + " refitAll: " + e.message; }
  r.z6refitAll = refitted && s.p.term.cols === narrow.cols;

  try {
    const storage = makeStorage();
    const P = page(textSrc, termSrc, storage);
    const { id, p } = await newPane(P, 660, 480);
    const full11 = fitGrid(660, 480, 11);
    r.z5start = p.term.cols === full11.cols && p.term.rows === full11.rows;
    P.sent.resize.length = 0;
    P.setBox(p.host, 330, 480); P.flush();
    const half = fitGrid(330, 480, 11);
    const lastR = P.sent.resize[P.sent.resize.length - 1];
    r.z5 = !!lastR && lastR[0] === id && lastR[1] === half.cols && lastR[2] === half.rows && p.term.cols === half.cols;
    r.z5detail = JSON.stringify(P.sent.resize);
    P.setBox(p.host, 660, 480); P.flush();
    r.z5back = p.term.cols === full11.cols;

    // Z4 + Z7: a pinch out to 2× (11 → 22)
    const xterm = [];
    P.sent.resize.length = 0; P.copies.length = 0;
    const h = p.host;
    dispatch(h, "touchmove", { touches: touches([100, 100]) }, xterm);
    r.z7one = xterm.includes("touchmove:1");
    xterm.length = 0;
    dispatch(h, "touchstart", { touches: touches([100, 100], [200, 100]) }, xterm);
    dispatch(h, "touchmove", { touches: touches([80, 100], [230, 100]) }, xterm);
    const mid = p.term.options.fontSize;
    p.term._sel = true;   // xterm started a selection under the fingers
    dispatch(h, "contextmenu", {}, xterm, true);
    dispatch(h, "contextmenu", {}, xterm);
    dispatch(h, "touchmove", { touches: touches([50, 100], [250, 100]) }, xterm);
    const midResizes = P.sent.resize.length;
    dispatch(h, "touchend", { touches: touches([50, 100]) }, xterm);
    P.flush();
    dispatch(h, "click", {}, xterm, true);
    dispatch(h, "click", {}, xterm);
    const g22 = fitGrid(660, 480, 22);
    r.z4mid = midResizes === 0;
    r.z4 = P.sent.resize.length >= 1 && P.sent.resize.every(([i, c, rr]) => i === id && c === g22.cols && rr === g22.rows);
    r.z4detail = JSON.stringify({ midResizes, sent: P.sent.resize, want: g22 });
    r.z2live = p.term.options.fontSize === 22 && p.term.cols === g22.cols && p.term.rows === g22.rows && mid > 11 && mid < 22;
    r.z7xterm = !xterm.some((x) => /^touch/.test(x) && !/:1$/.test(x)) && !xterm.some((x) => /^(contextmenu|click)/.test(x));
    r.z7menu = !P.document.getElementById("pane-menu");
    r.z7tap = P.copies.length === 0;
    r.z7sel = !p.term.hasSelection();
    r.z7detail = JSON.stringify({ xterm, copies: P.copies, menu: !!P.document.getElementById("pane-menu"), sel: p.term.hasSelection() });
    r.z3stored = storage.getItem(P.MYK.FONT_KEY) === "22";
    const P2 = page(textSrc, termSrc, storage);
    const n2 = await newPane(P2, 660, 480);
    r.z3restore = n2.p.term.options.fontSize === 22 && n2.p.term.cols === g22.cols && P2.MYK.fontSize() === 22;
    r.z3clamp = P2.MYK.setFontSize(99) === 32 && storage.getItem(P2.MYK.FONT_KEY) === "32" && P2.MYK.setFontSize(1) === 6;
    storage.setItem(P2.MYK.FONT_KEY, "abc");
    r.z3garbage = P2.MYK.fontSize() === 11;
    storage.setItem(P2.MYK.FONT_KEY, "22");
    P.MYK.activePane = id;
    P.MYK.showPaneMenu(10, 10);
    const menu = P.document.getElementById("pane-menu");
    const labels = menu ? menu.children.map((c) => (c.children[0] && c.children[0].textContent) || c.textContent) : [];
    const need = ["Copy All", "Copy Last", "Paste", "Paste & Run", "URL — Select ▸", "Split", "Close Pane", "Reset font size"];
    r.z8items = need.every((n) => labels.includes(n));
    r.z8got = JSON.stringify(labels);
    const resetRow = menu && menu.children.find((c) => c.children[0] && c.children[0].textContent === "Reset font size");
    r.z8hint = !!resetRow && !resetRow.className.includes("disabled") && resetRow.children[1].textContent === "22px → 11px";
    P.sent.resize.length = 0;
    if (resetRow) resetRow.fire("click");
    P.flush();
    const g11 = fitGrid(660, 480, 11);
    r.z8reset = p.term.options.fontSize === 11 && storage.getItem(P.MYK.FONT_KEY) === null && p.term.cols === g11.cols
      && P.sent.resize.some(([i, c, rr]) => i === id && c === g11.cols && rr === g11.rows);
    P.MYK.showPaneMenu(10, 10);
    const menu2 = P.document.getElementById("pane-menu");
    const resetRow2 = menu2 && menu2.children.find((c) => c.children[0] && c.children[0].textContent === "Reset font size");
    r.z8disabled = !!resetRow2 && resetRow2.className.includes("disabled");
  } catch (e) { r.crash = (r.crash || "") + " " + e.message; }

  // Z10: the native store. A pinch lands in it (and still in localStorage, the fallback).
  try {
    const nat = { v: 0 }, ls = makeStorage();
    const A = page(textSrc, termSrc, ls, nat);
    const a = await newPane(A, 660, 480);
    A.MYK.setFontSize(20);
    r.z10write = nat.v === 20 && ls.getItem(A.MYK.FONT_KEY) === "20";
    // a NEW phone: the native store came over with "apply all", localStorage is empty
    const B = page(textSrc, termSrc, makeStorage(), { v: 20 });
    const b = await newPane(B, 660, 480);
    r.z10newphone = B.MYK.fontSize() === 20 && b.p.term.options.fontSize === 20;
    // the native value wins over a stale localStorage one
    const lsC = makeStorage(); lsC.setItem("myk-term-font-size", "15");
    r.z10wins = page(textSrc, termSrc, lsC, { v: 24 }).MYK.fontSize() === 24;
    // a size only in localStorage (set before this build) is migrated into the native store
    const natD = { v: 0 }, lsD = makeStorage(); lsD.setItem("myk-term-font-size", "18");
    const D = page(textSrc, termSrc, lsD, natD);
    r.z10migrate = D.MYK.fontSize() === 18 && natD.v === 18 && lsD.getItem("myk-term-font-size") === "18";
    // nothing stored anywhere: the default, and nothing invented in the native store
    const natE = { v: 0 };
    r.z10none = page(textSrc, termSrc, makeStorage(), natE).MYK.fontSize() === 11 && natE.v === 0;
    // reset forgets it natively too
    A.MYK.resetFontSize();
    r.z10reset = nat.v === 0 && ls.getItem(A.MYK.FONT_KEY) === null && A.MYK.fontSize() === 11;
    // a bridge that refuses: localStorage carries it, nothing throws
    const natF = { v: 0, throws: true }, lsF = makeStorage();
    const F = page(textSrc, termSrc, lsF, natF);
    F.MYK.setFontSize(16);
    r.z10refuse = F.MYK.fontSize() === 16 && lsF.getItem("myk-term-font-size") === "16";
    // no bridge method at all (an older shell): localStorage only
    const lsG = makeStorage(); lsG.setItem("myk-term-font-size", "13");
    r.z10nobridge = page(textSrc, termSrc, lsG).MYK.fontSize() === 13;
  } catch (e) { r.crash = (r.crash || "") + " z10: " + e.message; }
  // wiring: the Kotlin side and the fleet-config declaration
  r.z10kotlin = /@JavascriptInterface\s+fun getFontSize\(\)[^\n]*IdePrefs\.terminalFontSize/.test(SRC.bridge)
    && /@JavascriptInterface\s+fun setFontSize\(px: Int\)[^\n]*IdePrefs\.setTerminalFontSize/.test(SRC.bridge)
    && /KEY_FONT_SIZE = "terminal_font_size"/.test(SRC.prefs) && /FONT_MIN = 6/.test(SRC.prefs) && /FONT_MAX = 32/.test(SRC.prefs);
  try {
    const ide = JSON.parse(SRC.fleet).stores.ide_prefs;
    r.z10decl = !!ide && ide.class === "config" && ide.kind === "prefs" && /terminal_font_size/.test(ide.doc)
      && !(ide.keys && ide.keys.terminal_font_size);   // a key narrowed to device would NOT migrate
  } catch (e) { r.z10decl = false; }
  return r;
}

(async () => {
  const r = await verdict(SRC.text, SRC.term);
  if (r.crash) bad("the shipped code is missing what this tester drives:" + r.crash);
  console.log("== Z1: font size clamp =="); check(r.z1, "Z1 sizes are whole pixels in 6..32, garbage falls back to the default, a pinch is start × scale");
  console.log("== Z2: grid for a box and a font ==");
  check(r.z2pure, "Z2 TermText.grid: floor(box / cell), at least 2 × 1, none without a cell size");
  check(r.z2live, "Z2 after a pinch to 22px the pane's grid is the one a 22px cell gives its box (and grew live, mid-gesture)");
  console.log("== Z3: persistence ==");
  check(r.z3stored, "Z3 the pinched size is stored (one global setting)");
  check(r.z3restore, "Z3 a fresh page's new pane opens at the stored size, refitted");
  check(r.z3clamp, "Z3 a stored size is clamped (99 → 32, 1 → 6)");
  check(r.z3garbage, "Z3 a stored size that is not a number reads as the default");
  console.log("== Z4: PTY resize after a pinch ==");
  check(r.z4mid, "Z4 the PTY is not resized mid-gesture " + (r.z4mid ? "" : r.z4detail));
  check(r.z4, "Z4 when the fingers lift the PTY gets the final grid " + (r.z4 ? "" : r.z4detail));
  console.log("== Z5: PTY resize after a layout change ==");
  check(r.z5start, "Z5 a new pane fits its box");
  check(r.z5, "Z5 the pane's box changes with no window event → the pane refits and the PTY gets the new grid " + (r.z5 ? "" : r.z5detail));
  check(r.z5back, "Z5 and grows back when the box does");
  console.log("== Z6: sidebar open → close ==");
  check(r.z6start && r.z6open, "Z6 with the sidebar open (and the keyboard up) the pane has the narrow grid");
  check(r.z6, "Z6 closing the sidebar restores the full grid and the full PTY size " + (r.z6 ? "" : r.z6detail));
  check(r.z6hidden, "Z6 a hidden tab's pane (0×0) keeps its grid");
  check(r.z6refitAll, "Z6 refitAll refits every pane (the toggle's fallback)");
  console.log("== Z7: a pinch is not a tap ==");
  check(r.z7one, "Z7 one finger still reaches xterm (the scrollback scrolls)");
  check(r.z7xterm, "Z7 no two-finger touch, tap or long-press reaches xterm " + (r.z7xterm ? "" : r.z7detail));
  check(r.z7menu, "Z7 two fingers held still do not open the pane menu " + (r.z7menu ? "" : r.z7detail));
  check(r.z7tap, "Z7 the finger lifting last does not tap (no copy, no link) " + (r.z7tap ? "" : r.z7detail));
  check(r.z7sel, "Z7 a selection the pinch started is dropped");
  console.log("== Z8: the tap menu ==");
  check(r.z8items, "Z8 every item kept, plus Reset font size " + r.z8got);
  check(r.z8hint, "Z8 Reset font size says what it does (22px → 11px)");
  check(r.z8reset, "Z8 Reset font size goes back to the default, forgets the stored size, refits and resizes the PTY");
  check(r.z8disabled, "Z8 at the default size it is disabled");
  console.log("== Z10: a declared fleet-config preference ==");
  check(r.z10write, "Z10 a size is written to the native store (and still to localStorage)");
  check(r.z10newphone, "Z10 a new phone (native store applied, localStorage empty) opens panes at the size");
  check(r.z10wins, "Z10 the native size wins over a stale localStorage one");
  check(r.z10migrate, "Z10 a size only in localStorage is migrated into the native store, localStorage kept");
  check(r.z10none, "Z10 nothing stored: the default, nothing invented natively");
  check(r.z10reset, "Z10 Reset forgets the size natively and in localStorage");
  check(r.z10refuse, "Z10 a bridge that refuses falls back to localStorage without throwing");
  check(r.z10nobridge, "Z10 a shell without the bridge methods reads localStorage");
  check(r.z10kotlin, "Z10 TerminalBridge exposes get/setFontSize to IdePrefs terminal_font_size, clamped 6..32");
  check(r.z10decl, "Z10 fleet-config.json declares it in ide_prefs (class config, not narrowed to device)");
  console.log("== Z9: wiring ==");
  check(/btn-sidebar"\)\.addEventListener\("click", \(\) => \{[^}]*classList\.toggle\("hidden"\);\s*MYK\.refitAll\(\);/.test(SRC.boot), "Z9 the sidebar toggle refits the panes");
  check(/visualViewport/.test(SRC.boot) && /orientationchange/.test(SRC.boot), "Z9 keyboard (visualViewport) and rotation refit too");
  check(/\.pane \{[^}]*touch-action: pan-x pan-y;[^}]*\}/.test(SRC.css) && /\.pane \{[^}]*overflow: hidden;/.test(SRC.css), "Z9 .pane keeps panning for one finger and gives two to the pinch");
  check(SRC.html.indexOf('src="js/termtext.js"') > 0 && SRC.html.indexOf('src="js/termtext.js"') < SRC.html.indexOf('src="js/term.js"'), "Z9 index.html loads termtext.js before term.js");

  console.log("== MUT ==");
  const muts = [
    ["term", "THE OLD CODE: no per-pane size watch (fit on window resize only)", "    this._watchSize(id, host);\n", "", (x) => x.z6 && x.z5],
    ["term", "the PTY learns every mid-pinch grid", "if (p && p.pinch) { p.pinch.pending = true; return; }", "", (x) => x.z4mid],
    ["term", "no PTY resize when the fingers lift", "if (g.pending || g.size !== g.size0) Transport.ptyResize(id, term.cols, term.rows);", "", (x) => x.z4],
    ["term", "the size is not stored", "else s.setItem(this.FONT_KEY, String(v));", "", (x) => x.z3stored && x.z3restore],
    ["term", "a new pane ignores the stored size", "fontSize: this.fontSize(),", "fontSize: 11,", (x) => x.z3restore],
    ["term", "two fingers reach xterm", "    swallow(e);\n      if (!e.touches || e.touches.length < 2) return;", "    if (!e.touches || e.touches.length < 2) return;", (x) => x.z7xterm],
    ["term", "a long-press during a pinch opens the menu", "if (this._pinching()) return;   // two fingers", "if (0) return;   // two fingers", (x) => x.z7menu && x.z7tap],
    ["term", "the last finger up taps", "if (this._pinching() || Date.now() - this._lastLinkAct", "if (Date.now() - this._lastLinkAct", (x) => x.z7tap && x.z7xterm],
    ["term", "no Reset font size in the menu", 'row("Reset font size"', 'if (0) row("Reset font size"', (x) => x.z8items],
    ["term", "the size is not written natively", "if (a) a.setFontSize(dflt ? 0 : v);", "", (x) => x.z10write && x.z10newphone],
    ["term", "the native size is ignored on read", "if (nat) return nat;", "", (x) => x.z10newphone && x.z10wins],
    ["term", "no migration of the localStorage size", "if (nat === 0 && size !== def) {", "if (0) {", (x) => x.z10migrate],
    ["term", "reset keeps the native size", "a.setFontSize(dflt ? 0 : v)", "a.setFontSize(v)", (x) => x.z10reset],
    ["term", "a refusing bridge throws out of the read", "try { const n = Number(a.getFontSize()); return n > 0 ? TermText.clampFont(n) : 0; } catch (e) { return null; }", "const n = Number(a.getFontSize()); return n > 0 ? TermText.clampFont(n) : 0;", (x) => x.z10refuse],
    ["text", "no clamp", "return Math.min(this.FONT_MAX, Math.max(this.FONT_MIN, v));", "return v;", (x) => x.z1],
  ];
  let hollow = 0;
  for (const [which, label, from, to, green] of muts) {
    const src = which === "text" ? SRC.text : SRC.term;
    if (!src.includes(from)) { bad("MUT stale: '" + label + "' — its text is no longer in the source"); hollow++; continue; }
    const m = src.replace(from, to);
    let rr;
    try { rr = await verdict(which === "text" ? m : SRC.text, which === "term" ? m : SRC.term); } catch (e) { rr = null; }
    if (rr && green(rr)) { bad("MUT hollow: '" + label + "' leaves the tester green"); hollow++; }
    else console.log("  MUT-RED    " + label);
  }
  if (!hollow) ok("MUT " + muts.length + " mutations, every one red (incl. the old fit-on-window-resize-only code)");
  console.log("\n── terminal zoom + fit: " + pass + " passed, " + fail + " failed ──");
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.log("  FAIL: tester crashed: " + (e && e.stack)); process.exit(1); });
JS
