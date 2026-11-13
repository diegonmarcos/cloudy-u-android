package app.sterna.ui.settings

import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sterna.R
import app.sterna.fleet.CloudSecrets
import app.sterna.ui.CloudSecretField
import app.sterna.core.data.account.GatewayMode
import app.sterna.core.data.account.GatewayStatus
import app.sterna.core.jmap.GatewayCookies
import app.sterna.ui.theme.MailMetrics
import java.text.DateFormat
import java.util.Date

/** Test tags of the account Authentication section. */
object AccountAuthTags {
    const val SECTION = "account_auth_section"
    const val CHIP = "account_auth_chip"
    const val SIGN_IN = "account_auth_sign_in"
    const val SIGN_OUT = "account_auth_sign_out"
    const val FLEET_TOKEN = "account_auth_fleet_token"
    const val FLEET_SAVE = "account_auth_fleet_save"
    fun mode(m: GatewayMode) = "account_auth_mode_${m.name.lowercase()}"
}

/** The fleet's Authelia portal; the sign-in WebView stays on its domain. */
internal const val AUTHELIA_PORTAL = "https://auth.diegonmarcos.com"
internal const val AUTHELIA_DOMAIN = "diegonmarcos.com"

@Composable
fun gatewayStatusLabel(s: GatewayStatus): String = stringResource(
    when (s) {
        GatewayStatus.CONNECTED -> R.string.gw_chip_connected
        GatewayStatus.TOKEN -> R.string.gw_chip_token
        GatewayStatus.SIGNED_IN -> R.string.gw_chip_signed_in
        GatewayStatus.NEEDS_SIGN_IN -> R.string.gw_chip_needs_sign_in
        GatewayStatus.ERROR -> R.string.gw_chip_error
    },
)

/** The per-account status chip: primary when fine, error when it needs attention. */
@Composable
fun GatewayChip(status: GatewayStatus, modifier: Modifier = Modifier) =
    StatusPill(gatewayStatusLabel(status), bad = status == GatewayStatus.NEEDS_SIGN_IN || status == GatewayStatus.ERROR,
        tag = AccountAuthTags.CHIP, modifier = modifier)

/** The one pill every per-account status wears (auth, endpoint route): primary when fine, error when [bad]. */
@Composable
internal fun StatusPill(label: String, bad: Boolean, tag: String, modifier: Modifier = Modifier) {
    val ink = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.testTag(tag).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(MailMetrics.s2, ink),
    ) {
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = ink,
            modifier = Modifier.padding(horizontal = MailMetrics.s12, vertical = MailMetrics.s4),
        )
    }
}

/**
 * Account ▸ Authentication: None / Bearer token / Authelia. Bearer reuses the account's API-token
 * field above (masked, with the reveal toggle) and the Test connection button below; Authelia signs in
 * through the in-app web flow. Stateless: the screen hosts it and the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountAuthSection(
    mode: GatewayMode,
    status: GatewayStatus,
    signedInAt: Long,
    onMode: (GatewayMode) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    fleetToken: String = "",
    onFleetToken: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val modes = GatewayMode.entries
    Column(
        modifier.fillMaxWidth().testTag(AccountAuthTags.SECTION).padding(horizontal = MailMetrics.s16),
        verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            GatewayChip(status)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m, onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    modifier = Modifier.testTag(AccountAuthTags.mode(m)),
                ) {
                    Text(
                        stringResource(
                            when (m) {
                                GatewayMode.NONE -> R.string.gw_none
                                GatewayMode.BEARER -> R.string.gw_bearer
                                GatewayMode.AUTHELIA -> R.string.gw_authelia
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        when (mode) {
            GatewayMode.NONE -> Hint(R.string.gw_none_hint)
            GatewayMode.BEARER -> {
                Hint(R.string.gw_bearer_hint)
                // #1693 the gateway token is entered once in Cloud Account (App credentials) and taken from there, not typed here.
                CloudSecretField(
                    label = stringResource(R.string.gw_fleet_token_label),
                    idOrNull = CloudSecrets.GATEWAY_TOKEN_ID,
                    filled = fleetToken.isNotBlank(),
                    onTaken = { onFleetToken(it.trim()) },
                    modifier = Modifier.fillMaxWidth().testTag(AccountAuthTags.FLEET_TOKEN),
                )
            }
            GatewayMode.AUTHELIA -> {
                val signed = status == GatewayStatus.SIGNED_IN
                if (signed && signedInAt > 0) {
                    Text(
                        stringResource(
                            R.string.gw_signed_in_since,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(signedInAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Hint(R.string.gw_not_signed_in)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                    Button(onClick = onSignIn, modifier = Modifier.testTag(AccountAuthTags.SIGN_IN)) {
                        Text(stringResource(if (signed) R.string.gw_sign_in_again else R.string.gw_sign_in))
                    }
                    if (signed) {
                        OutlinedButton(onClick = onSignOut, modifier = Modifier.testTag(AccountAuthTags.SIGN_OUT)) {
                            Text(stringResource(R.string.gw_sign_out))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Hint(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The Authelia web sign-in, in-app (the same WebView-cookie capture libs:auth and cloud-browser's
 * auth mission use): the portal confined to the fleet domain; Done reads `authelia_session` out of the
 * WebView jar and hands only that value on. Never logged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AutheliaSignInDialog(onCaptured: (String) -> Unit, onDismiss: () -> Unit) {
    var missing by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { c ->
                        CloudWeb.create(c, WebProfile.APP_PAGE).apply {   // the fleet's Authelia portal
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : CloudWebClient(WebProfile.APP_PAGE) {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val host = request?.url?.host.orEmpty().lowercase()
                                    return !(host == AUTHELIA_DOMAIN || host.endsWith(".$AUTHELIA_DOMAIN"))
                                }
                            }
                            loadUrl(AUTHELIA_PORTAL)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (missing) {
                    Text(
                        stringResource(R.string.gw_no_cookie), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(MailMetrics.s8), horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Button(
                        onClick = {
                            val v = GatewayCookies.sessionValue(CookieManager.getInstance().getCookie(AUTHELIA_PORTAL).orEmpty())
                            if (v.isEmpty()) missing = true else onCaptured(v)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.gw_done)) }
                }
            }
        }
    }
}

/** Forget the session cookie in the WebView jar too, so Sign out is a real sign out. */
fun clearAutheliaWebSession() {
    runCatching {
        CookieManager.getInstance().setCookie(
            AUTHELIA_PORTAL, "${GatewayCookies.NAME}=; Max-Age=0; Domain=$AUTHELIA_DOMAIN; Path=/",
        )
        CookieManager.getInstance().flush()
    }
}
