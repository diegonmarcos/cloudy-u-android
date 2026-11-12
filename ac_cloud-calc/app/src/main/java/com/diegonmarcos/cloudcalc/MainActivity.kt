package com.diegonmarcos.cloudcalc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.perms.PermsHost
import com.diegonmarcos.clocktools.ClockEngine
import com.diegonmarcos.cloudcalc.engine.CalcClient
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.CONFIGS_PERMISSIONS_MODE

/** The one Activity: edge-to-edge through the lib's FleetChrome, the island clears its own inset (the cloud-c3 shape). */
class MainActivity : ComponentActivity(), PermsHost {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        val api = CalcClient(applicationContext)
        state = CalcState(getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        state.show(intent?.getStringExtra(ClockEngine.EXTRA_MODE))
        // Opening the app is a foreground moment: re-plan, so a wakeup the system dropped (a
        // force-stop clears every alarm and sends no broadcast) is set again.
        ClockEngine.reschedule(applicationContext)
        RatesWorker.schedule(applicationContext)
        setContent { CalcTheme { CalcShell(api, state) } }
    }

    private lateinit var state: CalcState

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.show(intent.getStringExtra(ClockEngine.EXTRA_MODE))
    }

    /** #1406 every "Open Permissions" (PermsLink) lands on Configs ▸ Permissions, which reads the pending request. */
    override fun openPermsPage(): Boolean {
        if (!::state.isInitialized) return false
        state.openConfigs()
        state.show(CONFIGS_PERMISSIONS_MODE)
        return true
    }

    companion object {
        const val PREFS = "cloud_calc"
    }
}
