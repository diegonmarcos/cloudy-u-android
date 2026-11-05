package com.diegonmarcos.cloudwebserver

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    syncKeepAlive()
  }

  /** #1502 follow the switches: the foreground service and the boot receiver exist only with Server ON and Keep alive ON. */
  fun syncKeepAlive() {
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
