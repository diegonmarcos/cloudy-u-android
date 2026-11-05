package com.diegonmarcos.clouddrive.configs

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight

/**
 * #1216 the former Configs ▸ Others page, now the last section of Configs ▸ General (the page is gone): the
 * removable-storage SAF grant (the volumes themselves are browsed from Explorer), the privileged shell and About.
 */
@Composable
fun OthersSection(prefs: DrivePrefs, actions: DriveActions, rcloneVersion: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snap by prefs.snapshot.collectAsState()
    val hasAccess = remember { Places.hasAllFilesAccess(ctx) }
    val volumes = remember(snap, hasAccess) { Places.discovered(ctx, snap, hasAccess).filter { it.kind == Places.Kind.PATH } }
    Column(modifier.fillMaxWidth()) {
        run {
            val granted = snap.treeGrantUri != null
            DriveCard(
                stringResource(R.string.configs_removable), light = StatusLight.of(granted, observed = false),
                summary = if (granted) stringResource(R.string.files_place_granted, snap.treeGrantName ?: "") else stringResource(R.string.configs_removable_none),
                tag = DriveTags.CONFIGS_CARD,
            ) {
                Text(
                    stringResource(R.string.configs_removable_volumes, volumes.size),
                    Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillRow {
                    Pill(stringResource(R.string.configs_grant_tree), { actions.requestTreeGrant() }, filled = !granted)
                    if (granted) Pill(stringResource(R.string.configs_forget_grant), { prefs.forgetTreeGrant() })
                }
            }
        }
        // #1510 the privileged shell is the SuperApp's broker: this app is a client (libs:shell-client) and only says whether it is armed.
        ShellBrokerCard()
        run {
            DriveCard(stringResource(R.string.configs_about), summary = BuildConfig.VERSION_NAME, tag = DriveTags.CONFIGS_CARD) {
                AboutRow(stringResource(R.string.configs_version), BuildConfig.VERSION_NAME + " · " + BuildConfig.VERSION_CODE)
                AboutRow(stringResource(R.string.configs_built), BuildConfig.BUILD_TIMESTAMP)
                // On-device provenance: the exact commit this APK was built from. Short on screen,
                // the full 40-hex sha to the clipboard on tap — "which commit is this build?" is
                // answerable from the phone with no adb and no release-shelf digest to decode.
                val copied = stringResource(R.string.configs_commit_copied)
                AboutRow(stringResource(R.string.configs_commit), BuildConfig.GIT_SHORT_SHA, tag = DriveTags.ABOUT_COMMIT) {
                    actions.copyText(BuildConfig.GIT_SHA)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                }
                AboutRow(stringResource(R.string.configs_engines), stringResource(R.string.configs_engines_value, rcloneVersion ?: "?"))
                AboutRow(stringResource(R.string.sync_store), Places.initialLocations().first.path)
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, tag: String? = null, onClick: (() -> Unit)? = null) {
    var m = Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide)
    if (onClick != null) m = m.clickable(onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(DriveMetrics.padWide))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
