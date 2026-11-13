package com.diegonmarcos.superapp.system

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import android.util.DisplayMetrics
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import org.json.JSONObject
import java.util.Locale

/**
 * Quick Actions > UI display controls: Cloud-superapp Scale, Font Size, Zoom Size. Declared in
 * build.json::ui.launcher_settings.display_controls; nothing about the ranges is restated here.
 *
 *  - Cloud-superapp Scale: THIS app's smallest width in dp ([swOverride]). [SystemDisplay.wrap] turns it into a densityDpi for the
 *    process, so it needs no ADB and no grant. 0 = no override (the Scale slider's factor rules).
 *  - Font Size: `settings system font_scale`. Read with Settings.System; written with Settings.System when WRITE_SETTINGS is
 *    granted, else through the ADB shell channel.
 *  - Zoom Size: `wm density` (Settings.Secure display_density_forced is what it persists; read-only here, because writing that key
 *    does not make the WindowManager re-lay-out, so a write would be a lie). Written through the ADB shell channel only.
 *
 * Without a path a write returns [Write.NeedsAdb] carrying the exact command to run, never an exception.
 */
object QuickDisplay {

    data class Preset(val label: String, val value: Int)
    data class ScaleSpec(
        val label: String, val subtitle: String, val unit: String, val center: Int, val min: Int, val max: Int,
        val tickMinor: Int, val tickMajor: Int, val presets: List<Preset>,
    )
    data class StepSpec(val label: String, val subtitle: String, val key: String, val default: Float, val steps: List<Float>, val names: List<String>) {
        fun nameOf(i: Int): String = names.getOrNull(i) ?: String.format(Locale.US, "x%.2f", steps[i])
    }

    sealed class Write {
        object Done : Write()
        /** No privileged path: [command] is what to run over adb. */
        data class NeedsAdb(val command: String) : Write()
        data class Failed(val note: String) : Write()
    }

    private val root: JSONObject get() = LauncherSettingsPrefs.Config.displayControls

    val scaleSpec: ScaleSpec by lazy { parseScale(root.optJSONObject("scale") ?: JSONObject()) }
    val fontSpec: StepSpec by lazy { parseSteps(root.optJSONObject("font") ?: JSONObject()) }
    val zoomSpec: StepSpec by lazy { parseSteps(root.optJSONObject("zoom") ?: JSONObject()) }

    fun parseScale(o: JSONObject): ScaleSpec {
        val ps = o.optJSONArray("presets")
        val center = o.optInt("center", 411)
        return ScaleSpec(
            o.optString("label", "Display Scale"), o.optString("subtitle"), o.optString("unit", "dp"),
            center, o.optInt("min", center - 150), o.optInt("max", center + 150),
            o.optInt("tick_minor", 10), o.optInt("tick_major", 50),
            (0 until (ps?.length() ?: 0)).map { ps!!.getJSONObject(it).let { p -> Preset(p.optString("label"), p.optInt("value")) } },
        )
    }

    fun parseSteps(o: JSONObject): StepSpec {
        val st = o.optJSONArray("steps"); val nm = o.optJSONArray("names")
        return StepSpec(
            o.optString("label"), o.optString("subtitle"), o.optString("key"), o.optDouble("default", 1.0).toFloat(),
            (0 until (st?.length() ?: 0)).map { st!!.getDouble(it).toFloat() },
            (0 until (nm?.length() ?: 0)).map { nm!!.getString(it) },
        )
    }

    // ── pure arithmetic ──────────────────────────────────────────────────

    /** densityDpi that makes a screen whose short side is [shortPx] px exactly [swDp] dp wide. */
    fun densityForSw(shortPx: Int, swDp: Int): Int = Math.round(shortPx * 160f / swDp.coerceAtLeast(1))

    /** The smallest width in dp of a screen whose short side is [shortPx] px at [densityDpi]. */
    fun swForDensity(shortPx: Int, densityDpi: Int): Int = Math.round(shortPx * 160f / densityDpi.coerceAtLeast(1))

    /** Index of the declared step nearest [value]. */
    fun nearest(steps: List<Float>, value: Float): Int =
        steps.indices.minByOrNull { Math.abs(steps[it] - value) } ?: 0

    fun densityForFactor(stableDpi: Int, factor: Float): Int = Math.round(stableDpi * factor)

    fun fontCommand(f: Float) = String.format(Locale.US, "settings put system font_scale %.2f", f)
    fun densityCommand(dpi: Int) = "wm density $dpi"

    // ── this app's smallest width (Cloud-superapp Scale) ─────────────────

    private const val PREFS = "display_controls"
    private const val KEY_SW = "sw_dp"

    fun swOverride(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SW, 0)
    fun setSwOverride(ctx: Context, dp: Int) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SW, dp).apply() }

    private fun shortPx(dm: DisplayMetrics) = minOf(dm.widthPixels, dm.heightPixels)

    /** Smallest width this app is drawing at right now, from the context's own metrics. */
    fun appSw(ctx: Context): Int = ctx.resources.displayMetrics.let { swForDensity(shortPx(it), it.densityDpi) }

    /** Smallest width of the DEVICE (system metrics, not wrapped by this app). */
    fun deviceSw(): Int = Resources.getSystem().displayMetrics.let { swForDensity(shortPx(it), it.densityDpi) }

    // ── live device reads (no permission) ────────────────────────────────

    fun fontScale(ctx: Context): Float = Settings.System.getFloat(ctx.contentResolver, "font_scale", 1f)

    /** The device's current density (system metrics). */
    fun deviceDensity(): Int = Resources.getSystem().displayMetrics.densityDpi

    /** The density the device ships with; Zoom Size steps are factors of this. */
    fun stableDensity(): Int = DisplayMetrics.DENSITY_DEVICE_STABLE

    /** `settings secure display_density_forced`, or null when no override is set. */
    fun forcedDensity(ctx: Context): Int? =
        runCatching { Settings.Secure.getString(ctx.contentResolver, "display_density_forced") }.getOrNull()?.trim()?.toIntOrNull()

    // ── privileged writes (call off the main thread) ─────────────────────

    private fun shell(ctx: Context, cmd: String): String? =
        runCatching { BgHost.shell(ctx)?.exec(cmd) }.getOrNull()

    fun writeFont(ctx: Context, f: Float): Write {
        if (Settings.System.canWrite(ctx)) {
            val ok = runCatching { Settings.System.putFloat(ctx.contentResolver, "font_scale", f) }.getOrDefault(false)
            if (ok) return Write.Done
        }
        if (BgHost.shell(ctx) == null) return Write.NeedsAdb("adb shell " + fontCommand(f))
        shell(ctx, fontCommand(f)) ?: return Write.Failed("the shell did not answer")
        return if (Math.abs(fontScale(ctx) - f) < 0.01f) Write.Done else Write.Failed("the shell ran it but font_scale is ${fontScale(ctx)}")
    }

    fun writeDensity(ctx: Context, dpi: Int): Write {
        if (BgHost.shell(ctx) == null) return Write.NeedsAdb("adb shell " + densityCommand(dpi))
        shell(ctx, densityCommand(dpi)) ?: return Write.Failed("the shell did not answer")
        return Write.Done
    }
}
