package com.diegonmarcos.clouddrive.configs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1510 Cloud Drive owns no privileged channel: Manage > Apps reads other apps' state through the SuperApp's shell broker
 * (libs:shell-client). This card says whether the broker is armed and, when it is not, opens the SuperApp's re-arm flow.
 */
@Composable
fun ShellBrokerCard() {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<ShellStatus?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { status = withContext(Dispatchers.IO) { ShellClient.status(ctx) } }
    val s = status
    DriveCard(
        stringResource(R.string.configs_shell_title), light = StatusLight.of(s?.armed),
        summary = s?.line() ?: stringResource(R.string.configs_shell_checking), tag = DriveTags.CONFIGS_CARD,
    ) {
        PillRow {
            if (s?.armed != true) Pill(stringResource(R.string.configs_shell_arm), { ShellClient.openArmPage(ctx) }, filled = true)
            Pill(stringResource(R.string.configs_shell_refresh), { tick++ })
        }
    }
}
