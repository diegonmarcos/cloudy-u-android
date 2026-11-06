package com.diegonmarcos.cloudnav.tracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Settings + progress of the engine, in its own SharedPreferences file. */
class EnginePrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tracker_engine", Context.MODE_PRIVATE)

    var config: EngineConfig
        get() {
            val d = EngineConfig()
            return EngineConfig(
                enabled = sp.getBoolean("enabled", d.enabled),
                useGps = sp.getBoolean("use_gps", d.useGps),
                useCell = sp.getBoolean("use_cell", d.useCell),
                accurateByTime = sp.getBoolean("acc_time", d.accurateByTime),
                accurateEveryHours = sp.getInt("acc_hours", d.accurateEveryHours),
                accurateBySteps = sp.getBoolean("acc_steps_on", d.accurateBySteps),
                accurateEverySteps = sp.getInt("acc_steps", d.accurateEverySteps),
                cellByTime = sp.getBoolean("cell_time", d.cellByTime),
                cellEveryHours = sp.getInt("cell_hours", d.cellEveryHours),
                motionBySteps = sp.getBoolean("motion_on", d.motionBySteps),
                motionEverySteps = sp.getInt("motion_steps", d.motionEverySteps),
            ).let { if (!it.useGps && !it.useCell) it.copy(useGps = true) else it }
        }
        set(c) {
            sp.edit().putBoolean("enabled", c.enabled).putBoolean("use_gps", c.useGps).putBoolean("use_cell", c.useCell)
                .putBoolean("acc_time", c.accurateByTime).putInt("acc_hours", c.accurateEveryHours)
                .putBoolean("acc_steps_on", c.accurateBySteps).putInt("acc_steps", c.accurateEverySteps)
                .putBoolean("cell_time", c.cellByTime).putInt("cell_hours", c.cellEveryHours)
                .putBoolean("motion_on", c.motionBySteps).putInt("motion_steps", c.motionEverySteps).apply()
        }

    var state: EngineState
        get() = EngineState(sp.getLong("last_acc", 0L), sp.getLong("last_cell", 0L), sp.getLong("steps_acc", 0L), sp.getLong("steps_motion", 0L))
        set(s) { sp.edit().putLong("last_acc", s.lastAccurateMs).putLong("last_cell", s.lastCellMs)
            .putLong("steps_acc", s.stepsSinceAccurate).putLong("steps_motion", s.stepsSinceMotion).apply() }

    /** Wake-source state: moving until a time, and the geofence around the current STAY (see [WakePolicy]). */
    var wake: WakeState
        get() = WakeState(sp.getBoolean("wake_moving", false), sp.getLong("wake_until", 0L),
            Double.fromBits(sp.getLong("fence_lat", 0L)), Double.fromBits(sp.getLong("fence_lon", 0L)), Double.fromBits(sp.getLong("fence_r", 0L)))
        set(w) { sp.edit().putBoolean("wake_moving", w.moving).putLong("wake_until", w.untilMs).putLong("fence_lat", w.fenceLat.toRawBits())
            .putLong("fence_lon", w.fenceLon.toRawBits()).putLong("fence_r", w.fenceRadiusM.toRawBits()).apply() }

    /** The running step length (m), calibrated from accurate fixes. */
    var stepLengthM: Double
        get() = sp.getFloat("step_m", DeadReckoning.DEFAULT_STEP_M.toFloat()).toDouble()
        set(v) { sp.edit().putFloat("step_m", v.toFloat()).apply() }

    /** Last raw TYPE_STEP_COUNTER reading (steps since boot); -1 = none yet. */
    var lastCounter: Long
        get() = sp.getLong("last_counter", -1L)
        set(v) { sp.edit().putLong("last_counter", v).apply() }
    var lastSampleMs: Long
        get() = sp.getLong("last_sample", 0L)
        set(v) { sp.edit().putLong("last_sample", v).apply() }
}

/**
 * The engine's own database (tracker_engine.db). User data is never wiped: a schema change is a numbered
 * step in [upgrade]; downgrading keeps the file as it is.
 *
 *   step_samples  every worker run: the steps taken since the previous sample + the heading then.
 *   engine_fixes  every location the engine produced: kind gps | cell | predicted, with its accuracy / radius.
 */
class EngineDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "tracker_engine.db", null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) = upgrade(db, 0, VERSION)
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = upgrade(db, o, n)
    override fun onDowngrade(db: SQLiteDatabase, o: Int, n: Int) { /* keep the data */ }

    fun addSample(ts: Long, delta: Long, headingDeg: Double?) {
        writableDatabase.insert("step_samples", null, ContentValues().apply {
            put("ts", ts); put("delta", delta); if (headingDeg != null) put("heading", headingDeg)
        })
    }

    fun addFix(f: PlaceFix, kind: String) {
        writableDatabase.insert("engine_fixes", null, ContentValues().apply {
            put("ts", f.ts); put("lat", f.lat); put("lon", f.lon); put("acc", f.accuracyM); put("kind", kind)
        })
    }

    /** Samples as step slices (previous sample .. this one) for the on-device fallback of the Steps page. */
    fun slices(fromMs: Long, toMs: Long): List<StepSlice> {
        val out = mutableListOf<StepSlice>()
        var prev = 0L
        readableDatabase.rawQuery("SELECT ts, delta FROM step_samples WHERE ts BETWEEN ? AND ? ORDER BY ts", arrayOf(fromMs.toString(), toMs.toString())).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(0); val d = c.getLong(1)
                // a long gap (device off / engine off) is not walking time: attribute to the last 15 min only
                val start = if (prev == 0L) ts - SAMPLE_MS else maxOf(prev, ts - 2 * SAMPLE_MS)
                if (d > 0) out += StepSlice(start, ts, d)
                prev = ts
            }
        }
        return out
    }

    /** Heading legs since [sinceMs], for dead reckoning. */
    fun legsSince(sinceMs: Long): List<Leg> {
        val out = mutableListOf<Leg>()
        readableDatabase.rawQuery("SELECT delta, heading FROM step_samples WHERE ts > ? AND delta > 0 ORDER BY ts", arrayOf(sinceMs.toString())).use { c ->
            while (c.moveToNext()) if (!c.isNull(1)) out += Leg(c.getLong(0).toInt(), c.getDouble(1))
        }
        return out
    }

    fun lastMeasuredFix(): Pair<PlaceFix, Double>? =
        readableDatabase.rawQuery("SELECT ts,lat,lon,acc FROM engine_fixes WHERE kind IN ('gps','net') ORDER BY ts DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) PlaceFix(c.getLong(0), c.getDouble(1), c.getDouble(2), false, c.getDouble(3)) to c.getDouble(3) else null
        }

    fun fixes(fromMs: Long, toMs: Long): List<PlaceFix> {
        val out = mutableListOf<PlaceFix>()
        readableDatabase.rawQuery("SELECT ts,lat,lon,acc,kind FROM engine_fixes WHERE ts BETWEEN ? AND ? ORDER BY ts", arrayOf(fromMs.toString(), toMs.toString())).use { c ->
            while (c.moveToNext()) out += PlaceFix(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(4) == "predicted", c.getDouble(3), c.getString(4) ?: "")
        }
        return out
    }

    companion object {
        const val VERSION = 1
        const val SAMPLE_MS = 15 * 60_000L

        /** Numbered schema steps: [from] (exclusive) up to [to] (inclusive). Add `if (from < 2 && to >= 2)` for v2. */
        fun upgrade(db: SQLiteDatabase, from: Int, to: Int) {
            if (from < 1 && to >= 1) {
                db.execSQL("CREATE TABLE IF NOT EXISTS step_samples (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, delta INTEGER NOT NULL, heading REAL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_step_samples_ts ON step_samples(ts)")
                db.execSQL("CREATE TABLE IF NOT EXISTS engine_fixes (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, kind TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_engine_fixes_ts ON engine_fixes(ts)")
            }
        }

        @Volatile private var inst: EngineDb? = null
        fun get(ctx: Context): EngineDb = inst ?: synchronized(this) { inst ?: EngineDb(ctx.applicationContext).also { inst = it } }
    }
}
