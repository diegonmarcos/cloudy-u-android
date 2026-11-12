package com.diegonmarcos.clouddrive.configs

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.superapp.fleetendpoints.SignInContract
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * #587 Configs ▸ Sign in, #1693 AS A LINK: Cloud Account is the one app that signs in. The card keeps its place and its
 * declared ways (libs:auth's registry, read only): each way's pill opens Cloud Account at that provider's page, and the
 * light and the line are the state Cloud Account REPORTS over its provider ([AccountClient.signInStatus]). A missing or
 * silent Cloud Account is a sentence, never a crash. What a sign-in yields for Drive is applied by [DriveAuthApply] from
 * the vault road; nothing is stored here but what the engines' own stores take.
 */
@Composable
fun SignInCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ways = remember { SignIn.providers.map { it.id to it.label to cloudAccountProvider(it) } }
    var note by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<DriveAuthApply.Report?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val states by produceState<Map<String, SignInContract.Status>>(emptyMap()) {
        value = withContext(Dispatchers.IO) { ways.associate { (idLabel, provider) -> idLabel.first to AccountClient.signInStatus(ctx, provider) } }
    }
    val any = states.values.any { it.signedIn }
    DriveCard(
        stringResource(R.string.configs_sign_in), modifier,
        light = if (any) StatusLight.State.ON else StatusLight.State.UNKNOWN,
        summary = stringResource(R.string.configs_sign_in_body),
        tag = DriveTags.CONFIGS_CARD,
    ) {
        ways.forEach { (idLabel, provider) ->
            val st = states[idLabel.first]
            Text(
                (idLabel.second) + ": " + (st?.line ?: stringResource(R.string.configs_sign_in_reading)),
                Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Pill(stringResource(R.string.configs_sign_in_link, idLabel.second), { note = AccountClient.openSignIn(ctx, provider).orEmpty() },
                modifier = Modifier.padding(top = DriveMetrics.gapWide).testTag(DriveTags.CONFIGS_SIGN_IN_LINK))
        }
        Text(stringResource(R.string.configs_sign_in_applies, DriveAuthApply.applies.keys.joinToString(" · ")), Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Pill(stringResource(R.string.configs_sign_in_apply), {
            scope.launch { report = withContext(Dispatchers.IO) { applyFromCloudAccount(ctx.applicationContext) } }
        }, modifier = Modifier.padding(top = DriveMetrics.gapWide).testTag(DriveTags.CONFIGS_SIGN_IN_APPLY))
        report?.let { Text(it.text(), Modifier.padding(top = DriveMetrics.gapWide).testTag(DriveTags.CONFIGS_SIGN_IN_REPORT), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        if (note.isNotBlank()) Text(note, Modifier.padding(top = DriveMetrics.gapWide).testTag(DriveTags.CONFIGS_SIGN_IN_NOTE), style = MaterialTheme.typography.bodySmall)
    }
}

/** The provider name Cloud Account's pages and state answer to: the Authelia ways are one, every other way keeps its declared id. */
private fun cloudAccountProvider(p: SignIn.Provider): String = when (p.kind) {
    SignIn.Kind.AUTHELIA_BEARER, SignIn.Kind.AUTHELIA_WEB -> SignInContract.PROVIDER_AUTHELIA
    else -> p.id
}

/**
 * What Cloud Account holds for the sections [DriveAuthApply] declares (build.json::auth.applies), read over its provider
 * (the grant gates each path) and applied through [DriveAuthApply.apply]: the one writer. A path Cloud Account does not
 * grant or hold is a named refusal in the report, never a crash and never a value.
 */
internal fun applyFromCloudAccount(app: android.content.Context): DriveAuthApply.Report {
    val artifact = JSONObject()
    for ((section, key) in DriveAuthApply.applies) {
        val v = AccountClient.secret(app, "$section.$key")
        if (v.isBlank()) continue
        val value: Any = if (v.trimStart().startsWith("{")) runCatching { JSONObject(v) }.getOrDefault(v) else v
        artifact.put(section, (artifact.optJSONObject(section) ?: JSONObject()).put(key, value))
    }
    return runCatching { DriveAuthApply.apply(app, artifact) }.getOrElse {
        DriveAuthApply.Report(listOf(DriveAuthApply.Step("cloud-account", false, "Cloud Account answered, but applying failed (${it.javaClass.simpleName})")))
    }
}
