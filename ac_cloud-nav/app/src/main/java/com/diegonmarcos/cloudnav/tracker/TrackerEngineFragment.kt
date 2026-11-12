package com.diegonmarcos.cloudnav.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.cloudnav.configs.ConfigsFragment
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Configs > Tracker: the tracking engine's settings page ([EngineSettingsScreen]). */
class TrackerEngineFragment : KitComposeFragment() {
    private var ui by mutableStateOf(EngineUi())

    /** #1515 location / activity grants live in Configs > Perms: open it on those rows; [onResume] refreshes on return. */
    private fun askPerms(feature: String, ids: List<String>) { runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(requireContext(), feature, ids) } }

    override fun palette(): KitPalette = TrackerInk.palette()

    override fun onResume() { super.onResume(); refresh() }

    @Composable
    override fun Content() {
        EngineSettingsScreen(ui, EngineActions(
            onConfig = { c ->
                val ctx = requireContext().applicationContext
                EnginePrefs(ctx).config = c
                EngineScheduler.apply(ctx, c)
                ui = ui.copy(config = c)
            },
            onRequest = ::request,
            onLegacy = { (parentFragment as? ConfigsFragment)?.showLegacyTracker() },
        ))
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    private fun granted(): Set<Perm> = buildSet {
        val loc = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (loc) add(Perm.LOCATION)
        if (loc && (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION))) add(Perm.BACKGROUND)
        if (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION)) add(Perm.ACTIVITY)
    }

    private fun request(p: Perm) {
        val g = granted()
        when (p) {
            Perm.LOCATION -> askPerms("Tracker: location", listOf("fine_location", "coarse_location"))
            // Android only offers "all the time" after the foreground grant, as its own step
            Perm.BACKGROUND -> if (Perm.LOCATION !in g) request(Perm.LOCATION)
                else if (Build.VERSION.SDK_INT >= 29) askPerms("Tracker: background location", listOf("background_location"))
            Perm.ACTIVITY -> if (Build.VERSION.SDK_INT >= 29) askPerms("Tracker: activity", listOf("activity_recognition"))
        }
    }

    private fun refresh() {
        val ctx = requireContext().applicationContext
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        ui = ui.copy(config = EnginePrefs(ctx).config, granted = granted(),
            hasStepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null, stepLengthM = EnginePrefs(ctx).stepLengthM)
        viewLifecycleOwner.lifecycleScope.launch {
            val last = withContext(Dispatchers.IO) { EngineDb.get(ctx).fixes(System.currentTimeMillis() - 7 * 86_400_000L, System.currentTimeMillis()).lastOrNull() }
            val text = last?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.ts)) + (if (it.predicted) " (predicted)" else "") } ?: "No fix yet"
            ui = ui.copy(lastFix = text)
        }
    }
}
