// cloud-board-bridge.js (#1787, own code, not part of the vendored Quickdraw engine): saves and loads Cloud Canvas boards through the
// app's web message listener `CloudBoard` (BoardScreen.kt, allowed only for https://appassets.androidplatform.net, main frame only).
// Messages are JSON strings.
//   page -> app  {op:"ready"}                         the editor is mounted; the app answers with a load
//                {op:"save", id, snapshot}            the document changed (debounced) or a flush was asked
//                {op:"error", message}                a load or save failed in the page
//   app -> page  {op:"load", id, snapshot|null}       show this board (null: a new, empty one)
//                {op:"flush"}                         save now (before a board switch or when the app goes to the background)
// A snapshot is Quickdraw's `editor.store.getSnapshot()`: {document: {store: {<id>: record}}}.
(function () {
  "use strict";
  var SAVE_DELAY_MS = 800;
  var bridge = window.CloudBoard;
  var editor = null, boardId = null, blank = null, applying = false, timer = null, dirty = false;

  function send(o) { if (bridge) bridge.postMessage(JSON.stringify(o)); }

  function save() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (!editor || !boardId || !dirty) return;
    dirty = false;
    try { send({ op: "save", id: boardId, snapshot: JSON.stringify(editor.store.getSnapshot()) }); }
    catch (e) { send({ op: "error", message: "save: " + e }); }
  }

  function load(m) {
    save();
    boardId = m.id;
    applying = true;
    try { editor.store.loadSnapshot(m.snapshot ? JSON.parse(m.snapshot) : blank, "remote"); }
    catch (e) { send({ op: "error", message: "load: " + e }); }
    finally { applying = false; dirty = false; }
  }

  if (bridge) bridge.onmessage = function (ev) {
    var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m.op === "load" && editor) load(m);
    else if (m.op === "flush") save();
  };

  function attach() {
    editor = window.quickdrawEditor;
    if (!editor || !window.quickdrawReady) { setTimeout(attach, 50); return; }
    // the empty document a new board starts from, taken at mount before anything is drawn
    blank = editor.store.getSnapshot();
    editor.store.listen(function () {
      if (applying) return;
      dirty = true;
      if (timer) clearTimeout(timer);
      timer = setTimeout(save, SAVE_DELAY_MS);
    }, { source: "user" });
    document.addEventListener("visibilitychange", function () { if (document.visibilityState === "hidden") save(); });
    window.addEventListener("pagehide", save);
    send({ op: "ready" });
  }
  attach();
})();
