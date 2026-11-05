#!/usr/bin/env bash
# Tester (#804): Auto update ON + Wi-Fi = ONE deterministic chain —
# refresh → Download ALL → install (libs, apps, the host last) → clear —
# persisted after every step, so a restart resumes where it stopped.
#
# The owner's report: phone on Wi-Fi, Auto update ON + Wi-Fi only ON, updates
# pending, and the Store did nothing. The pass ran only at app start and every
# six hours (joining Wi-Fi was not a trigger), capped its DOWNLOADS at three
# without a privileged channel, drew nothing, and kept its state in memory.
#
# The behaviour is proven in Robolectric (StoreAutoTest: real downloads against
# a counting server, a "process death" at a persisted checkpoint, the resume).
# What a Robolectric run cannot see is the WIRING — a trigger that stops being
# registered, a worker that stops reaching the chain, a phase run out of order
# by an edit that leaves every scenario test's own setup intact. So this guards
# the wiring and PROVES ITSELF BY MUTATION: each mutant is a realistic
# regression applied to the real source text, and the validator must go RED on
# every one of them.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIB="$ROOT/ab_cloud-libs-shared/libs"
exec python3 - "$LIB" <<'PY'
import re, sys
lib = sys.argv[1]
A = lib + "/appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
U = lib + "/updater/src/main/java/com/diegonmarcos/superapp/updater/"
FILES = {"auto": A + "StoreAuto.kt", "worker": A + "ConstellationWorker.kt", "api": A + "StoreDebugApi.kt",
         "stages": A + "StoreStages.kt", "page": A + "StoreCloudFragment.kt", "fleet": U + "Fleet.kt"}
P = F = 0
def ok(m):
    global P; P += 1; print("  PASS: " + m)
def bad(m):
    global F; F += 1; print("  FAIL: " + m)
def code(t):  # comments say what was removed and why; never let them satisfy a check
    return "\n".join(l.split("//")[0] for l in re.sub(r"/\*.*?\*/", "", t, flags=re.S).splitlines())
src = {k: open(p, encoding="utf-8").read() for k, p in FILES.items()}

def fun(t, name, indent="    "):
    """The body of `fun <name>(`, up to the next member at the same indent."""
    m = re.search(r"\n%s(?:private |internal )?fun %s\(.*?(?=\n%s(?:private |internal |@|fun |class |const |val |var |object )|\n%s\}|\Z)"
                  % (indent, re.escape(name), indent, indent[:-4]), t, re.S)
    return m.group(0) if m else ""

def ordered(body, *parts):
    at = [body.find(p) for p in parts]
    return all(i >= 0 for i in at) and at == sorted(at)

def auto(s): return code(s["auto"])

CHECKS = [
    ("the chain runs refresh → download → install → clear, in that order",
     lambda s: ordered(fun(auto(s), "run"), "if (s.phase == REFRESH) refresh(", "if (s.phase == DOWNLOAD) download(",
                       "if (s.phase == INSTALL) install(", "if (s.phase != CLEAR) break",
                       "clear(ctx, s, byId, selfAfter)")),
    ("a persisted mid-chain phase is RESUMED, never refreshed again",
     lambda s: ordered(fun(auto(s), "run"), "prev != null && prev.phase in PHASES ->", "else -> State(REFRESH")),
    ("Download all installs nothing — every fetch precedes every install",
     lambda s: "StoreStages.download(ctx, app)" in fun(auto(s), "download")
               and "StoreStages.install(" not in fun(auto(s), "download")
               and "StoreStages.install(ctx, app, remoteOf(item))" in fun(auto(s), "install")),
    ("a download that will not fit is never started (storage check first)",
     lambda s: ordered(fun(auto(s), "download"), "StoreStages.room(ctx)", "if (want > room) {", "StoreStages.download(ctx, app)")),
    ("one package failing never stops the rest (the only early return is a user cancel)",
     lambda s: fun(auto(s), "download").count("return") >= 1
               and fun(auto(s), "download").count("return") == fun(auto(s), "download").count("return stop(ctx, s, \"cancelled")),
    ("libs before apps, the host last",
     lambda s: "s.queue.sortBy { rank(it.kind) }" in fun(auto(s), "refresh")
               and re.search(r'"lib" -> 0;\s*HOST -> 2;\s*else -> 1', fun(auto(s), "rank"))),
    ("every transition is persisted with commit(), before the work it names",
     lambda s: ".commit()" in fun(auto(s), "save") and ".apply()" not in fun(auto(s), "save")
               and ordered(fun(auto(s), "download"), "save(ctx, s)\n            checkpoint(DOWNLOAD", "StoreStages.download(")
               and ordered(fun(auto(s), "install"), "save(ctx, s)\n            checkpoint(INSTALL", "StoreStages.install(")),
    ("the host's hand-off is persisted BEFORE it (installing this app kills the process)",
     lambda s: ordered(fun(auto(s), "clear"), "s.phase = DONE", "save(ctx, s)", "selfUpdate(ctx)")),
    ("a restart mid-install asks the device before installing again",
     lambda s: ordered(fun(auto(s), "install"), "if (item.status == INSTALLING) {", "now != item.before) { landed(item)",
                       "StoreStages.install(")),
    ("no privileged channel: installs held to the budget, the rest resume next pass",
     lambda s: ordered(fun(auto(s), "install"), "val budget = installBudget(ctx)", "if (started >= budget) return stop(")),
    ("both workers' unattended pass IS the chain (Fleet.autoChain)",
     lambda s: "chain?.invoke(ctx, apps, owner) ?: runBatch(ctx, apps, Mode.AUTO, limit, owner)" in code(s["fleet"])
               and "Fleet.autoChain = { c, apps, owner -> pass(c, apps, owner) }" in fun(auto(s), "attach")),
    ("joining Wi-Fi is a trigger: the default network turning unmetered kicks the chain",
     lambda s: ordered(fun(auto(s), "attach"), "registerDefaultNetworkCallback(", "NET_CAPABILITY_NOT_METERED",
                       "if (wifiEdge(was, now))", "kick(app, TRIGGER_WIFI)")
               and "= now && was == false" in fun(auto(s), "wifiEdge")),
    ("app start attaches the chain and its Wi-Fi trigger",
     lambda s: "StoreAuto.attach(context)" in fun(code(s["worker"]), "start", "        ")),
    ("the worker passes its trigger to the chain",
     lambda s: 'owner = "$TAG:$trigger"' in code(s["worker"])),
    ("the periodic job and every kick require storage-not-low",
     lambda s: "setRequiresStorageNotLow(true)" in fun(code(s["worker"]), "constraints", "        ")
               and "val constraints = constraints(context)" in fun(code(s["worker"]), "start", "        ")
               and ".setConstraints(constraints(context))" in fun(code(s["worker"]), "kick", "        ")),
    ("a Store refresh is a trigger",
     lambda s: "ConstellationWorker.kick(ctx, StoreAuto.TRIGGER_STORE_REFRESH)" in fun(code(s["page"]), "checkAll")),
    ("/api/store/auto answers the chain's persisted phase, queue, current and last error",
     lambda s: '"auto" -> if (q["pkg"].isNullOrEmpty()) auto(ctx, q["run"] == "1")' in code(s["api"])
               and "StoreAuto.json(ctx)" in fun(code(s["api"]), "auto")
               and "load(ctx)" in fun(auto(s), "json")),
    ("the batch report survives the app restarting itself",
     lambda s: "keepBatch(ctx, json(run()))" in code(s["api"]) and '"batch" -> (lastBatch(ctx)' in code(s["api"])
               and ".commit()" in fun(code(s["api"]), "keepBatch")),
    ("the Store bar shows the chain's phase in front of the package line",
     lambda s: "val phase = StoreAuto.label() ?: return progressOf(state, job)" in fun(code(s["stages"]), "progress")),
]

def validate(s):
    return [name for name, check in CHECKS if not check(s)]

print("== T1: the auto chain is wired, ordered, persisted and triggered ==")
v = validate(src)
for name, _ in CHECKS:
    (bad if name in v else ok)(name)

print("== T2: the validator sees each regression (mutation) ==")
MUTANTS = [
    ("install runs before Download all finished", "auto",
     "                if (s.phase == DOWNLOAD) download(ctx, s, byId)\n                if (s.phase == INSTALL) install(ctx, s, byId)\n",
     "                if (s.phase == INSTALL) install(ctx, s, byId)\n                if (s.phase == DOWNLOAD) download(ctx, s, byId)\n"),
    ("a restart refreshes from scratch", "auto", "prev != null && prev.phase in PHASES ->", "false ->"),
    ("the download phase installs each app as it lands", "auto",
     "val st = try { StoreStages.download(ctx, app) }", "val st = try { StoreStages.install(ctx, app, r) }"),
    ("the storage check is dropped", "auto", "if (want > room) {", "if (false) {"),
    ("a full disk stops the whole chain", "auto",
     "s.lastError = \"${item.id}: ${item.error}\"\n                save(ctx, s); continue",
     "s.lastError = \"${item.id}: ${item.error}\"\n                save(ctx, s); return"),
    ("apps install in manifest order (no lib-first)", "auto", "s.queue.sortBy { rank(it.kind) }", ""),
    ("state written with apply() (lost if the process dies next)", "auto",
     "putString(KEY, toJson(s).toString()).commit()", "putString(KEY, toJson(s).toString()).apply()"),
    ("the self-update is handed off before the state is saved", "auto",
     "        save(ctx, s)\n        runCatching { onPending(ctx, pending(s)) }\n        if (host != null && selfAfter) runCatching { selfUpdate(ctx) }",
     "        runCatching { onPending(ctx, pending(s)) }\n        if (host != null && selfAfter) runCatching { selfUpdate(ctx) }\n        save(ctx, s)"),
    ("a resumed install re-installs what already landed", "auto",
     "if (now != null && now != item.before) { landed(item)", "if (false) { landed(item)"),
    ("the session budget is ignored", "auto", "if (started >= budget) return stop(", "if (false) return stop("),
    ("autoPass ignores the chain", "fleet",
     "chain?.invoke(ctx, apps, owner) ?: runBatch(ctx, apps, Mode.AUTO, limit, owner)",
     "runBatch(ctx, apps, Mode.AUTO, limit, owner)"),
    ("joining Wi-Fi kicks nothing", "auto", "kick(app, TRIGGER_WIFI)", "Unit"),
    ("the first network reading counts as Wi-Fi appearing", "auto", "= now && was == false", "= now && was != true"),
    ("app start never attaches the chain", "worker",
     "            StoreAuto.attach(context) { c, t -> kick(c, t) }\n", ""),
    ("storage-not-low dropped", "worker", "            setRequiresStorageNotLow(true)\n", ""),
    ("a Store refresh triggers nothing", "page",
     "        if (force) ConstellationWorker.kick(ctx, StoreAuto.TRIGGER_STORE_REFRESH)\n", ""),
    ("/api/store/auto goes back to the per-app alias", "api",
     '"auto" -> if (q["pkg"].isNullOrEmpty()) auto(ctx, q["run"] == "1").toString() else verb(ctx, q)',
     '"auto" -> verb(ctx, q)'),
    ("the batch report goes back into a field", "api", "keepBatch(ctx, json(run()))", "json(run())"),
    ("the bar loses the phase", "stages",
     "val phase = StoreAuto.label() ?: return progressOf(state, job)",
     "val phase = (null as String?) ?: return progressOf(state, job)"),
]
for name, key, old, new in MUTANTS:
    if src[key].count(old) != 1:
        bad("mutant '%s' no longer applies — its anchor moved; re-aim it" % name); continue
    m = dict(src); m[key] = src[key].replace(old, new)
    (ok if validate(m) else bad)(("mutation goes RED: " if validate(m) else "mutation stayed GREEN: ") + name)

print("== RESULT(#804 store auto chain): %d passed, %d failed ==" % (P, F))
sys.exit(1 if F else 0)
PY
