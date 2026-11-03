package com.x8bit.bitwarden.ui.platform.feature.settings.autofill

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.diegonmarcos.superapp.fleetconfig.CredentialProviderStatus
import com.x8bit.bitwarden.data.autofill.cloud.ProviderChecklist

/** Settings.Secure reads: null = not readable from this app, "" = readable and unset. */
private fun readSecure(ctx: Context, key: String): String? =
    try {
        Settings.Secure.getString(ctx.contentResolver, key) ?: ""
    } catch (_: Throwable) {
        null
    }

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun credentialProviderEnabled(ctx: Context): Boolean? = runCatching {
    ctx.getSystemService(android.credentials.CredentialManager::class.java)
        ?.isEnabledCredentialProviderService(
            ComponentName(ctx.packageName, CredentialProviderStatus.CREDENTIAL_SERVICE),
        )
}.getOrNull()

/**
 * Reads everything [ProviderChecklist] needs. Read-only: nothing here writes a setting.
 */
internal fun probeProvider(ctx: Context): ProviderChecklist.ProviderProbe {
    val autofillManager = runCatching { ctx.getSystemService(AutofillManager::class.java) }
        .getOrNull()
    val sdk = Build.VERSION.SDK_INT
    return ProviderChecklist.ProviderProbe(
        sdkInt = sdk,
        autofillSupported = runCatching { autofillManager?.isAutofillSupported }.getOrNull(),
        ownAutofillSelected = runCatching { autofillManager?.hasEnabledAutofillServices() }
            .getOrNull(),
        autofillSetting = readSecure(ctx, CredentialProviderStatus.KEY_AUTOFILL),
        credentialServiceEnabled = if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            credentialProviderEnabled(ctx)
        } else {
            null
        },
        credentialPrimarySetting = readSecure(ctx, CredentialProviderStatus.KEY_CREDENTIAL_PRIMARY),
        credentialListSetting = readSecure(ctx, CredentialProviderStatus.KEY_CREDENTIAL_LIST),
    )
}

/** Opens Android's own screen for [action] (guided only: the user flips the switch there). */
private fun openFor(ctx: Context, action: ProviderChecklist.Action) {
    val step = when (action) {
        ProviderChecklist.Action.NONE -> return
        ProviderChecklist.Action.SET_AUTOFILL_SERVICE -> CredentialProviderStatus.Step.AUTOFILL
        ProviderChecklist.Action.CREDENTIAL_PROVIDER_SETTINGS ->
            CredentialProviderStatus.Step.CREDENTIAL
    }
    CredentialProviderStatus.open(ctx, step, ctx.packageName)
}

/** Re-reads the rows every time the screen resumes (the user comes back from Android's settings). */
@Composable
private fun rememberProviderChecklist(): List<ProviderChecklist.Item> {
    val ctx = LocalContext.current
    var items by remember {
        mutableStateOf(ProviderChecklist.items(probeProvider(ctx), ctx.packageName))
    }
    LifecycleResumeEffect(Unit) {
        items = ProviderChecklist.items(probeProvider(ctx), ctx.packageName)
        onPauseOrDispose { }
    }
    return items
}

@Composable
private fun stateColor(state: ProviderChecklist.State): Color = when (state) {
    ProviderChecklist.State.OK -> BitwardenTheme.colorScheme.status.strong
    ProviderChecklist.State.MISSING -> BitwardenTheme.colorScheme.status.error
    ProviderChecklist.State.UNKNOWN -> BitwardenTheme.colorScheme.text.secondary
}

private fun stateMark(state: ProviderChecklist.State): String = when (state) {
    ProviderChecklist.State.OK -> "✓"
    ProviderChecklist.State.MISSING -> "✗"
    ProviderChecklist.State.UNKNOWN -> "?"
}

/** One green / red / grey row; tapping a row that is not green opens the screen that fixes it. */
@Composable
private fun ProviderChecklistRow(item: ProviderChecklist.Item, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tappable = item.state != ProviderChecklist.State.OK &&
        item.action != ProviderChecklist.Action.NONE
    Row(
        modifier = modifier
            .testTag("CloudVaultProviderRow_${item.id}")
            .then(if (tappable) Modifier.clickable { openFor(ctx, item.action) } else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = stateMark(item.state),
            style = BitwardenTheme.typography.titleMedium,
            color = stateColor(item.state),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = item.label,
                style = BitwardenTheme.typography.bodyLarge,
                color = BitwardenTheme.colorScheme.text.primary,
            )
            Text(
                text = if (tappable) "${item.detail} — tap to open settings" else item.detail,
                style = BitwardenTheme.typography.bodySmall,
                color = BitwardenTheme.colorScheme.text.secondary,
            )
        }
    }
}

@Composable
private fun ProviderChecklistRows(items: List<ProviderChecklist.Item>) {
    items.forEach { item ->
        ProviderChecklistRow(item = item, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Setup step: make Cloud Vault the phone's autofill service and (Android 14+) its passkeys &
 * passwords provider. One row per requirement, green / red / grey; "Open settings" opens the
 * Android screen for the first row that is not green (nothing is written from here); Skip hides
 * the step; every row re-checks on resume.
 */
@Composable
fun CloudVaultProviderStep(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val items = rememberProviderChecklist()
    var skipped by rememberSaveable { mutableStateOf(false) }
    if (skipped) return
    val complete = ProviderChecklist.isComplete(items)
    Column(modifier = modifier) {
        Text(
            text = "Make Cloud Vault your autofill & passkeys provider",
            style = BitwardenTheme.typography.titleMedium,
            color = BitwardenTheme.colorScheme.text.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProviderChecklistRows(items)
        Spacer(modifier = Modifier.height(12.dp))
        if (!complete) {
            val next = ProviderChecklist.nextAction(items)
            if (next != ProviderChecklist.Action.NONE) {
                BitwardenFilledButton(
                    label = "Open settings",
                    onClick = { openFor(ctx, next) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            BitwardenOutlinedButton(
                label = "Skip",
                onClick = { skipped = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Vault's Settings > Autofill: the same rows, persistent; each red row opens its screen. */
@Composable
fun CloudVaultProviderStatusRow(modifier: Modifier = Modifier) {
    val items = rememberProviderChecklist()
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = if (ProviderChecklist.isComplete(items)) {
                "Autofill & passkeys provider: Cloud Vault ✓"
            } else {
                "Autofill & passkeys provider: action needed"
            },
            style = BitwardenTheme.typography.titleMedium,
            color = BitwardenTheme.colorScheme.text.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        ProviderChecklistRows(items)
    }
}

/**
 * #1412 launch check: on every launcher start, if Cloud Vault is not the active Credential
 * Manager (passkeys & passwords) provider or the selected Autofill service, a dialog offers a
 * direct link to the Android screen that fixes the first red row (the credential provider
 * settings, or ACTION_REQUEST_SET_AUTOFILL_SERVICE). Rows re-check on resume, so the dialog
 * closes by itself once the user comes back with the vault selected. "Not now" hides it until
 * the next launch. Nothing is written from here.
 */
@Composable
fun CloudVaultProviderLaunchPrompt() {
    val ctx = LocalContext.current
    val items = rememberProviderChecklist()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val next = ProviderChecklist.nextAction(items)
    if (dismissed || ProviderChecklist.isComplete(items) || next == ProviderChecklist.Action.NONE) {
        return
    }
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(text = "Make Cloud Vault your passkeys & autofill provider") },
        text = {
            Column {
                Text(
                    text = "Cloud Vault is not the phone's preferred service, so passkeys and " +
                        "passwords go elsewhere. In Settings > Passwords, passkeys and " +
                        "autofill, set Preferred service = Cloud Vault.",
                )
                Spacer(modifier = Modifier.height(8.dp))
                ProviderChecklistRows(items)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { openFor(ctx, next) },
                modifier = Modifier.testTag("CloudVaultProviderLaunchPromptOpenSettings"),
            ) { Text(text = "Open settings") }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text(text = "Not now") }
        },
        modifier = Modifier.testTag("CloudVaultProviderLaunchPrompt"),
    )
}
