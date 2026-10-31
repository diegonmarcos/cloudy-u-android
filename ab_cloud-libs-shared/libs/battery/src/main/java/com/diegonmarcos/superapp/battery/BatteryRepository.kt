package com.diegonmarcos.superapp.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.diegonmarcos.superapp.adbdebug.ShellChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.adbdebug.ShizukuShellChannel

/**
 * The battery SoT's Android half: it READS the battery ([probe]), RECORDS the
 * history ([record]) and hands [BatteryTruth.compute] both ([report]). The
 * badge, the popup and Configs › About › Battery all call [report]; nothing
 * else in the app reads CURRENT_NOW for display or keeps a rate of its own.
 *
 * RECORDER. A sample is written when the battery service runs (every
 * ACTION_BATTERY_CHANGED it receives, coalesced, plus screen on/off), on the
 * 15-minute BatterySessionWorker tick, and whenever a surface asks for a
 * report — never on a timer of its own and never under a wakelock. A sample
 * is kept only when something moved (level, plug, status, screen) or every
 * [BatteryHistory.HEARTBEAT_MS] otherwise, so 14 days stay a few thousand rows. The history
 * lives in battery_sot.db (SQLite, like libs:battery's energy_watchdog.db).
 *
 * The "now" current is an EMA ([BatteryMath.ema]) kept in memory and rebuilt
 * from the newest recorded currents after a process restart.
 */
object BatteryRepository {

    private val lock = Any()
    private var db: Db? = null
    private var cache: ArrayList<BatterySample>? = null
    private var ema: BatteryMath.Ema? = null
    private var scale = BatteryMath.CurrentScale.UNKNOWN
    private var lastCounterUah: Long? = null
    private var cumulativeUah = 0L
    private var peakFullUah = 0L

    // ── reading ──────────────────────────────────────────────────────────

    /** The battery right now: the sticky ACTION_BATTERY_CHANGED + BatteryManager properties. */
    fun probe(ctx: Context, sticky: Intent? = null, now: Long = System.currentTimeMillis()): BatteryReading? {
        val i = sticky ?: runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scaleMax = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        fun prop(id: Int): Int? = runCatching { bm?.getIntProperty(id) }.getOrNull()?.takeIf { it != Int.MIN_VALUE }
        val counter = runCatching { bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrNull()
            ?.takeIf { it != Long.MIN_VALUE && it > 0L }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return BatteryReading(
            nowMs = now,
            levelPct = if (level >= 0 && scaleMax > 0) level * 100 / scaleMax else -1,
            status = status,
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 1),
            technology = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            tempDc = temp.takeIf { it != Int.MIN_VALUE },
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            rawCurrentNow = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            rawCurrentAvg = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            counterUah = counter,
            systemCycles = if (Build.VERSION.SDK_INT >= 34)
                i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it > 0 } else null,
            systemChargeRemainingMs = if (Build.VERSION.SDK_INT >= 28 && status == BatteryManager.BATTERY_STATUS_CHARGING)
                runCatching { bm?.computeChargeTimeRemaining() }.getOrNull() ?: -1L else -1L,
            screenOn = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive }.getOrNull(),
        )
    }

    // ── recording ────────────────────────────────────────────────────────

    /** Record the battery now (the badge's receiver, the worker tick). Returns the reading it recorded. */
    fun record(ctx: Context, sticky: Intent? = null): BatteryReading? {
        val r = probe(ctx, sticky) ?: return null
        synchronized(lock) { fold(ctx, r) }
        return r
    }

    /** Learn the scale, advance the EMA, store the sample when it carries news. Under [lock]. */
    private fun fold(ctx: Context, r: BatteryReading): List<BatterySample> {
        val list = load(ctx)
        val learned = BatteryMath.learnScale(scale, r.rawCurrentNow)
        if (learned != scale) { scale = learned; runCatching { db(ctx).putMeta(K_SCALE, learned.name) } }
        val sample = BatteryTruth.sampleOf(r, scale)
        sample.currentMa?.let { ema = BatteryMath.ema(ema, it.toDouble(), r.nowMs) }
        countCharge(ctx, r)
        if (r.levelPct in 0..100 && BatteryHistory.shouldStore(list.lastOrNull(), sample)) {
            runCatching { db(ctx).insert(sample, r.nowMs - BatteryHistory.RETENTION_MS) }
            list += sample
            val cut = r.nowMs - BatteryHistory.RETENTION_MS
            if (list.isNotEmpty() && list.first().ts < cut) list.removeAll { it.ts < cut }
        }
        return list
    }

    /**
     * The counted cycles: every CHARGE_COUNTER step taken while charging adds
     * to the charge accepted since install, and the counter at a 100% reading
     * is the "full" it is divided by (AccuBattery's method). Kept in the db
     * meta, so it survives restarts; under [lock].
     */
    private fun countCharge(ctx: Context, r: BatteryReading) {
        val c = r.counterUah ?: return
        val d = BatteryMath.chargeDeltaUah(lastCounterUah, c, r.charging || r.status == BatteryMath.STATUS_FULL)
        lastCounterUah = c
        if (d > 0L) { cumulativeUah += d; runCatching { db(ctx).putMeta(K_CUMULATIVE, cumulativeUah.toString()) } }
        if (r.levelPct >= 100 && c > peakFullUah) { peakFullUah = c; runCatching { db(ctx).putMeta(K_PEAK, c.toString()) } }
    }

    // ── the report ───────────────────────────────────────────────────────

    /** THE read every battery surface makes. Records the reading as a side effect. */
    fun report(ctx: Context, sticky: Intent? = null): BatteryReport? {
        val r = probe(ctx, sticky) ?: return null
        val slow = slowExtras(ctx, r)
        return synchronized(lock) {
            val list = fold(ctx, r)
            val sample = BatteryTruth.sampleOf(r, scale)
            val samples = if (list.lastOrNull()?.ts == sample.ts) list.toList() else list + sample
            BatteryTruth.compute(r, samples, ema, slow.copy(
                scale = scale,
                cycleEstimate = BatteryMath.countedCycles(cumulativeUah, peakFullUah),
                cumulativeChargedUah = cumulativeUah, peakFullUah = peakFullUah,
            ))
        }
    }

    /** The slow extras (rated capacity, the charger, which forks `dumpsys battery`): refreshed every [EXTRAS_TTL_MS] or on a plug change. */
    const val EXTRAS_TTL_MS = 5 * 60_000L
    @Volatile private var extrasAt = 0L
    @Volatile private var extrasPlug = -1
    @Volatile private var slow = BatteryExtras()

    private fun slowExtras(ctx: Context, r: BatteryReading): BatteryExtras {
        if (extrasAt != 0L && r.plugged == extrasPlug && r.nowMs - extrasAt in 0 until EXTRAS_TTL_MS) return slow
        val cap = runCatching { BatteryCapacity.read(ctx, peakFullUah) }.getOrNull()
        val spec = if (r.onPower) runCatching { BatteryChargerSpec.read() }.getOrNull() else null
        slow = BatteryExtras(
            ratedMah = cap?.ratedMah?.takeIf { it > 0 },
            chargerLiveW = spec?.liveInputW, chargerMaxW = spec?.maxPowerW,
            chargerSource = spec?.sourceLabel()?.takeIf { it.isNotBlank() },
        )
        extrasAt = r.nowMs; extrasPlug = r.plugged
        return slow
    }

    /** Wipe the recorded history and the counters (the debug API's battery/reset_history). */
    fun clear(ctx: Context) = synchronized(lock) {
        runCatching { db(ctx).wipe() }
        cache = ArrayList(); ema = null; cumulativeUah = 0L; peakFullUah = 0L; lastCounterUah = null
    }

    /**
     * Every raw value the battery exposes, AS REPORTED (no scaling, no sign,
     * no arithmetic): the BatteryManager properties and the sticky broadcast's
     * extras. The debug API's battery/properties and About's deep dump print
     * this; it is the only other place in the app that reads them.
     */
    fun rawDump(ctx: Context): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        fun intProp(k: String, id: Int) { out += k to runCatching { bm?.getIntProperty(id) }.getOrNull()?.toString() }
        fun longProp(k: String, id: Int) { out += k to runCatching { bm?.getLongProperty(id) }.getOrNull()?.toString() }
        intProp("bm.current_now", BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        intProp("bm.current_average", BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        intProp("bm.capacity_pct", BatteryManager.BATTERY_PROPERTY_CAPACITY)
        intProp("bm.status", BatteryManager.BATTERY_PROPERTY_STATUS)
        longProp("bm.charge_counter_uAh", BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        longProp("bm.energy_counter_nWh", BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        out += "bm.is_charging" to runCatching { bm?.isCharging }.getOrNull()?.toString()
        if (Build.VERSION.SDK_INT >= 28)
            out += "bm.charge_time_remaining_ms" to runCatching { bm?.computeChargeTimeRemaining() }.getOrNull()?.toString()
        val i = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        out += "sticky.present" to (i != null).toString()
        if (i != null) {
            fun ex(k: String, key: String) { out += k to i.getIntExtra(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.toString() }
            ex("sticky.level", BatteryManager.EXTRA_LEVEL)
            ex("sticky.scale", BatteryManager.EXTRA_SCALE)
            ex("sticky.status", BatteryManager.EXTRA_STATUS)
            ex("sticky.plugged", BatteryManager.EXTRA_PLUGGED)
            ex("sticky.health", BatteryManager.EXTRA_HEALTH)
            ex("sticky.voltage_mV", BatteryManager.EXTRA_VOLTAGE)
            ex("sticky.temperature_dC", BatteryManager.EXTRA_TEMPERATURE)
            out += "sticky.technology" to i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
            out += "sticky.present_battery" to i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false).toString()
            ex("sticky.cycle_count", "android.os.extra.CYCLE_COUNT")
            ex("sticky.charging_status", "android.os.extra.CHARGING_STATUS")
            ex("sticky.max_charging_current_uA", "android.os.extra.MAX_CHARGING_CURRENT")
            ex("sticky.max_charging_voltage_uV", "android.os.extra.MAX_CHARGING_VOLTAGE")
        }
        return out
    }

    /** The recorded history from [fromTs] (the stats page's graph and spreads). */
    fun samples(ctx: Context, fromTs: Long = 0L): List<BatterySample> =
        synchronized(lock) { load(ctx).filter { it.ts >= fromTs } }

    /**
     * The app's own privileged shell: the embedded adb channel (paired on Configs › Network › ADB Shell) or our
     * app_process server, never the third-party Shizuku rung of the ladder. Null = not paired; the surfaces then
     * open the ADB Shell page (AdbShellLink.open), the one place that pairs.
     */
    private fun channel(ctx: Context): ShellChannel? =
        runCatching { ShellChannels.active(ctx.applicationContext) }.getOrNull()?.takeUnless { it === ShizukuShellChannel }

    /** The privileged facts (sysfs capacity/cycles, dumpsys battery, per-app power) — blocking, call off the main thread. */
    fun privileged(ctx: Context): BatteryPrivileged.Facts? {
        val ch = channel(ctx) ?: return null
        return BatteryPrivileged.parse(runCatching { ch.exec(ctx.applicationContext, BatteryPrivileged.COMMAND) }.getOrNull())
    }

    /** "Channel name" when the privileged shell is up, else null. */
    fun privilegedChannel(ctx: Context): String? = channel(ctx)?.name()

    /** (package, row name) of a uid: [BatteryLabels.display] over what PackageManager knows ("uid:N" when nothing owns it). */
    fun appLabel(ctx: Context, uid: Int): Pair<String, String> {
        val pm = ctx.packageManager
        val pkgs = runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty()
        val label = pkgs.firstOrNull()?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        return (pkgs.firstOrNull() ?: "uid:$uid") to BatteryLabels.display(uid, label, pkgs)
    }

    /**
     * Per package over [from]..[to]: (active, background) ms from UsageStatsManager — time in the foreground, and time
     * with a foreground service running (API 29+; 0 before). Empty without Usage Access (the tables then print "—").
     */
    fun usageTimes(ctx: Context, from: Long, to: Long = System.currentTimeMillis()): Map<String, Pair<Long, Long>> {
        if (!EnergyWatchdog.hasUsageAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return emptyMap()
        return runCatching {
            usm.queryAndAggregateUsageStats(from, to).mapValues { (_, u) ->
                u.totalTimeInForeground to (if (Build.VERSION.SDK_INT >= 29) u.totalTimeForegroundServiceUsed else 0L)
            }
        }.getOrDefault(emptyMap())
    }

    // ── charge cycles (BatteryCycles) ────────────────────────────────────

    /**
     * One cycle snapshot: level / plug / per-uid mAh through the privileged channel (level and plug fall back
     * to the sticky broadcast when it is down), folded into the open cycle; a closed cycle is stored. Called by
     * PowerStateReceiver at every plug edge (its action is [pluggedHint]) and by BatterySessionWorker's 15-min
     * tick. Blocking.
     */
    fun snapshotCycle(ctx: Context, pluggedHint: Boolean? = null, now: Long = System.currentTimeMillis()) {
        val f = privileged(ctx)
        val r = if (f?.levelPct == null) probe(ctx, now = now) else null
        val level = f?.levelPct ?: r?.levelPct?.takeIf { it >= 0 } ?: return
        val plugged = pluggedHint ?: f?.plugged ?: ((r?.plugged ?: 0) != 0)
        val snap = BatteryCycles.Snap(now, level, plugged, f?.apps?.associate { it.uid to it.mAh })
        synchronized(lock) {
            val d = db(ctx)
            val state = BatteryCycles.State(d.openCycle(), d.meta(K_CYCLE_LAST)?.let(::decodeSnap))
            val (next, closed) = BatteryCycles.step(state, snap)
            closed?.let { d.putCycle(it) }
            next.open?.let { d.putCycle(it) }
            next.last?.let { d.putMeta(K_CYCLE_LAST, encodeSnap(it)) }
            d.pruneCycles(now - CYCLE_RETENTION_MS)
        }
    }

    /** Every recorded cycle, newest first (the open one included). */
    fun cycles(ctx: Context): List<BatteryCycles.Cycle> = synchronized(lock) { runCatching { db(ctx).cycles() }.getOrDefault(emptyList()) }

    private fun encodeSnap(s: BatteryCycles.Snap) = "${s.ts}|${s.levelPct}|${if (s.plugged) 1 else 0}|${BatteryCycles.encode(s.apps) ?: "-"}"
    private fun decodeSnap(v: String): BatteryCycles.Snap? {
        val f = v.split('|'); if (f.size != 4) return null
        return BatteryCycles.Snap(f[0].toLongOrNull() ?: return null, f[1].toIntOrNull() ?: return null, f[2] == "1",
            if (f[3] == "-") null else BatteryCycles.decode(f[3]))
    }

    // ── storage ──────────────────────────────────────────────────────────

    private fun db(ctx: Context): Db = db ?: Db(ctx.applicationContext).also { db = it }

    private fun load(ctx: Context): ArrayList<BatterySample> {
        cache?.let { return it }
        val d = db(ctx)
        val now = System.currentTimeMillis()
        val list = ArrayList(runCatching { d.since(now - BatteryHistory.RETENTION_MS) }.getOrDefault(emptyList()))
        scale = runCatching { d.meta(K_SCALE) }.getOrNull()
            ?.let { s -> BatteryMath.CurrentScale.entries.firstOrNull { it.name == s } } ?: scale
        cumulativeUah = runCatching { d.meta(K_CUMULATIVE)?.toLongOrNull() }.getOrNull() ?: cumulativeUah
        peakFullUah = runCatching { d.meta(K_PEAK)?.toLongOrNull() }.getOrNull() ?: peakFullUah
        if (ema == null) ema = BatteryHistory.replayEma(list.filter { it.ts >= now - BatteryMath.EMA_MAX_GAP_MS })
        cache = list
        return list
    }

    private const val K_SCALE = "current_scale"
    private const val K_CUMULATIVE = "cumulative_charged_uah"
    private const val K_PEAK = "peak_full_uah"
    private const val K_CYCLE_LAST = "cycle_last_snap"
    /** Cycles are kept 13 months, so the month view always holds a full year. */
    private const val CYCLE_RETENTION_MS = 400L * 24 * 3_600_000L
    private const val CYCLE_TABLE = "CREATE TABLE IF NOT EXISTS cycle (start_ts INTEGER PRIMARY KEY, end_ts INTEGER, " +
        "start_pct INTEGER, end_pct INTEGER, apps TEXT)"

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 3) {
        private val app = ctx

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sample (ts INTEGER PRIMARY KEY, level INTEGER, status INTEGER, plugged INTEGER, " +
                "ma INTEGER, mv INTEGER, temp_dc INTEGER, counter INTEGER, screen INTEGER)")
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
            db.execSQL(CYCLE_TABLE)
            runCatching { LegacyImport.sessions(app) { s -> write(db, s) } }
            runCatching { LegacyImport.counters(app) { k, v -> putMeta(db, k, v) } }
        }

        /** v1 → v2: the counted-cycle counters moved into the SoT; the samples are kept. */
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) runCatching { LegacyImport.counters(app) { k, v -> putMeta(db, k, v) } }
            if (old < 3) db.execSQL(CYCLE_TABLE)
        }

        fun wipe() { for (t in listOf("sample", "meta", "cycle")) writableDatabase.execSQL("DELETE FROM $t") }

        private fun putMeta(db: SQLiteDatabase, k: String, v: String) =
            db.execSQL("INSERT OR REPLACE INTO meta VALUES (?, ?)", arrayOf<Any?>(k, v))

        fun insert(s: BatterySample, pruneBefore: Long) {
            val db = writableDatabase
            write(db, s)
            db.execSQL("DELETE FROM sample WHERE ts < ?", arrayOf<Any?>(pruneBefore))
        }

        private fun write(db: SQLiteDatabase, s: BatterySample) = db.execSQL(
            "INSERT OR REPLACE INTO sample VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(s.ts, s.levelPct, s.status, s.plugged, s.currentMa, s.voltageMv, s.tempDc, s.counterUah,
                s.screenOn?.let { if (it) 1 else 0 }))

        fun since(fromTs: Long): List<BatterySample> {
            val out = ArrayList<BatterySample>()
            readableDatabase.rawQuery("SELECT * FROM sample WHERE ts >= ? ORDER BY ts ASC", arrayOf(fromTs.toString())).use { c ->
                fun intOrNull(i: Int) = if (c.isNull(i)) null else c.getInt(i)
                while (c.moveToNext()) out += BatterySample(
                    ts = c.getLong(0), levelPct = c.getInt(1), status = c.getInt(2), plugged = c.getInt(3),
                    currentMa = intOrNull(4), voltageMv = intOrNull(5), tempDc = intOrNull(6),
                    counterUah = if (c.isNull(7)) null else c.getLong(7),
                    screenOn = intOrNull(8)?.let { it == 1 })
            }
            return out
        }

        fun meta(k: String): String? =
            readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

        fun putMeta(k: String, v: String) =
            writableDatabase.execSQL("INSERT OR REPLACE INTO meta VALUES (?, ?)", arrayOf<Any?>(k, v))

        fun putCycle(c: BatteryCycles.Cycle) = writableDatabase.execSQL("INSERT OR REPLACE INTO cycle VALUES (?,?,?,?,?)",
            arrayOf<Any?>(c.startTs, c.endTs, c.startPct, c.endPct, BatteryCycles.encode(c.apps)))

        fun pruneCycles(before: Long) = writableDatabase.execSQL("DELETE FROM cycle WHERE start_ts < ?", arrayOf<Any?>(before))

        fun openCycle(): BatteryCycles.Cycle? = query("WHERE end_ts IS NULL ORDER BY start_ts DESC LIMIT 1").firstOrNull()

        fun cycles(): List<BatteryCycles.Cycle> = query("ORDER BY start_ts DESC")

        private fun query(tail: String): List<BatteryCycles.Cycle> {
            val out = ArrayList<BatteryCycles.Cycle>()
            readableDatabase.rawQuery("SELECT start_ts, end_ts, start_pct, end_pct, apps FROM cycle $tail", null).use { c ->
                while (c.moveToNext()) out += BatteryCycles.Cycle(c.getLong(0), if (c.isNull(1)) null else c.getLong(1),
                    c.getInt(2), c.getInt(3), BatteryCycles.decode(c.getString(4)).orEmpty())
            }
            return out
        }

        companion object { const val NAME = "battery_sot.db" }
    }

    /**
     * One-time move of what the SoT replaced: the completed sessions of the old
     * "battery_history" ledger (two samples each, start and end), the unplug
     * anchor and the counted-cycle counters of the old "battery_session" store,
     * so "since last charge", the cycle tables and the cycle count are not
     * empty on the first day. Both old stores are cleared after.
     */
    private object LegacyImport {
        private const val HISTORY = "battery_history"
        private const val SESSION = "battery_session"
        /** The old ledger never recorded the plug type: "on power, source unknown". */
        private const val PLUG_UNKNOWN = 0x80

        fun sessions(ctx: Context, write: (BatterySample) -> Unit) {
            val cut = System.currentTimeMillis() - BatteryHistory.RETENTION_MS
            val sp = ctx.getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
            val rows = (sp.getString("sessions", "") ?: "").split('\n').mapNotNull { line ->
                val f = line.split('|')
                if (f.size != 5) return@mapNotNull null
                val start = f[1].toLongOrNull() ?: return@mapNotNull null
                val end = f[2].toLongOrNull() ?: return@mapNotNull null
                val a = f[3].toIntOrNull() ?: return@mapNotNull null
                val b = f[4].toIntOrNull() ?: return@mapNotNull null
                if (end < cut || a !in 0..100 || b !in 0..100) null else listOf(f[0] == "C", start, end, a, b)
            }.sortedBy { it[1] as Long }
            for (r in rows) {
                val on = r[0] as Boolean
                val plug = if (on) PLUG_UNKNOWN else 0
                val st = if (on) BatteryMath.STATUS_CHARGING else BatteryMath.STATUS_DISCHARGING
                write(BatterySample(r[1] as Long, r[3] as Int, st, plug))
                write(BatterySample(r[2] as Long, r[4] as Int, st, plug))
            }
            val old = ctx.getSharedPreferences(SESSION, Context.MODE_PRIVATE)
            val unplugTs = old.getLong("unplug_ts", 0L)
            val unplugPct = old.getInt("unplug_pct", -1)
            if (unplugTs > cut && unplugPct in 0..100)
                write(BatterySample(unplugTs, unplugPct, BatteryMath.STATUS_DISCHARGING, 0))
            sp.edit().clear().apply()
        }

        fun counters(ctx: Context, put: (String, String) -> Unit) {
            val old = ctx.getSharedPreferences(SESSION, Context.MODE_PRIVATE)
            old.getLong("cumulative_charged_uah", 0L).takeIf { it > 0 }?.let { put(K_CUMULATIVE, it.toString()) }
            old.getLong("peak_at_full_uah", 0L).takeIf { it > 0 }?.let { put(K_PEAK, it.toString()) }
            old.edit().clear().apply()
        }
    }
}
