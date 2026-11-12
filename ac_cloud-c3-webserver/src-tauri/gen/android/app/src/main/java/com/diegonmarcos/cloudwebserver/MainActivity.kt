package com.diegonmarcos.cloudwebserver

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.cloudwebserver.ui.WebserverShell
import com.diegonmarcos.cloudwebserver.ui.WebserverTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/**
 * #1682 P1 the one Activity of the native app: edge-to-edge through the fleet's FleetChrome, the Compose shell (the fleet first row,
 * second row and bottom nav from libs:ui-nav-bars over build.json::ui), and the Rust core attached off the main thread. The core
 * ([Core]) binds the servers exactly as the Tauri host did and hands the server crate this activity for its Android calls; the UI
 * reads the server's loopback admin API ([Api]). The shared-storage grant the served root needs (storage_read, build.json::perms.needs)
 * is asked the fleet way, by libs:perms' first run and Configs > Perms (the Tauri host asked it from Rust; nothing asks here).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        Core.attach(this)
        com.diegonmarcos.cloudwebserver.owner.AdbOwnerSide.install(applicationContext)   // #1682 P2b no-op unless this app owns ADB
        syncKeepAlive()
        setContent { WebserverTheme { WebserverShell() } }
    }

    override fun onDestroy() {
        Core.detach(this)
        super.onDestroy()
    }

    /** Bound by name from the Rust core (core/src/lib.rs). */
    external fun nativeAttach(activity: MainActivity): Int

    /** Bound by name from the Rust core (core/src/lib.rs). */
    external fun nativeDetach()

    /**
     * #1502 follow the switches: the foreground service and the boot receiver exist only with Server ON and Keep alive ON.
     * Called here on create and by the Rust core (power::set_sync_hook) whenever a switch changes; never throws.
     */
    fun syncKeepAlive() {
        runCatching {
            val on = PowerState.serviceAllowed(this)
            packageManager.setComponentEnabledSetting(
                ComponentName(this, BootReceiver::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            val svc = Intent(this, KeepAliveService::class.java)
            if (on) startForegroundService(svc) else stopService(svc)
        }
    }
}
