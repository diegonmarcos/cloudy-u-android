package com.diegonmarcos.cloudstore

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.StoreDensity
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.install.SilentUpdate

/**
 * Silent updates (native, Android 12+): the master switch and what this phone can actually do. The lines are
 * [SilentUpdate.statusLines] (pure): API level, update-owner status, and the limits stated plainly. The switch is
 * [AutoUpdatePrefs.silent], the same one the install session reads.
 */
object SilentUpdatesCard {
    const val SWITCH_TAG = "configs_silent_updates"
}

@Composable
fun SilentUpdatesCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(AutoUpdatePrefs.silent(ctx)) }
    val owner = remember { SilentUpdate.updateOwner(ctx, ctx.packageName) }
    Text("Silent updates", fontSize = StoreDensity.T_TITLE.sp, color = MaterialTheme.colorScheme.onSurface)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Update fleet apps without the system dialog", fontSize = StoreDensity.T_BODY.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Switch(on, { on = it; AutoUpdatePrefs.setSilent(ctx, it) }, Modifier.testTag(SilentUpdatesCard.SWITCH_TAG))
    }
    SilentUpdate.statusLines(Build.VERSION.SDK_INT, on, owner, ctx.packageName).forEach {
        Text(it, fontSize = StoreDensity.T_CAPTION.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
