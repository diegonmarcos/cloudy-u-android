/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import com.diegonmarcos.superapp.perms.PermsNeeds
import com.diegonmarcos.superapp.perms.PermsLink
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.diegonmarcos.mediacenter.R

/** A centred empty or blocked state: what is missing, why, and the one or two things to do about it. Never mock content. */
@Composable
fun ShellEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

private fun allGranted(context: Context, permissions: List<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

/**
 * Shows [content] once every permission of [tab] is granted; until then the tab asks for them right
 * here (Allow, or the app's settings page when the system will not ask again) instead of showing an
 * empty library that looks like a bug.
 */
@Composable
fun PermissionGate(tab: GalleryTab, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permissions = remember(tab) { TabPermissions.forTab(tab, Build.VERSION.SDK_INT) }
    var granted by remember(permissions) { mutableStateOf(allGranted(context, permissions)) }
    // Back from the system settings page: the user may have granted them there.
    LifecycleResumeEffect(permissions) {
        granted = allGranted(context, permissions)
        onPauseOrDispose { }
    }
    if (granted) {
        content()
    } else {
        ShellEmptyState(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.shell_permission_title),
            body = stringResource(
                if (tab == GalleryTab.MAPS) R.string.shell_permission_body_maps else R.string.shell_permission_body_media
            ),
        ) {
            // #1515 granted in Configs > Perms: both buttons open the rows of this tab's permissions.
            val rows = PermsNeeds.idsFor(context, permissions)
            Button(onClick = { PermsLink.open(context, "gallery", rows) }) { Text(stringResource(R.string.shell_permission_allow)) }
            OutlinedButton(onClick = { PermsLink.open(context, "gallery", rows) }) { Text(stringResource(R.string.shell_permission_settings)) }
        }
    }
}
