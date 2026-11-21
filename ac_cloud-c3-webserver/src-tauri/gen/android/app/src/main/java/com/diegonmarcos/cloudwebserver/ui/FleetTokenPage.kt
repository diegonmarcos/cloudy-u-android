package com.diegonmarcos.cloudwebserver.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.cloudwebserver.FleetTokens
import java.text.DateFormat
import java.util.Date

/**
 * Configs > Fleet token (owner decision on #1835): this app owns the fleet bearer token the mesh API / MCP and every fleet member
 * authenticate with. Status shows set / not set, the LAST 4 characters and the set date; Paste, Generate (256-bit hex), Rotate and Clear
 * change it; Reveal and Copy hand out the full value only after the owner's device credential. Nothing here logs the value.
 * There is no vault button: the repo declares no phone-to-vault delivery path, and none is invented here.
 */
@Composable
fun FleetTokenPage() {
    val ctx = LocalContext.current
    val vault = remember { FleetTokens.of(ctx) }
    var tick by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }   // "reveal" | "copy": waiting for the device credential
    val st = remember(tick) { vault.status() }

    val cred = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val what = pending; pending = null
        if (r.resultCode == Activity.RESULT_OK) when (what) {
            "reveal" -> revealed = vault.reveal()
            "copy" -> vault.reveal()?.let { copyText(ctx, "fleet token", it) }
        } else toast(ctx, "Device credential not confirmed")
    }
    fun gated(what: String) {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val i = if (km.isDeviceSecure) km.createConfirmDeviceCredentialIntent("Fleet token", "Confirm your device credential to use the full token") else null
        if (i == null) toast(ctx, "Set a screen lock first: the full token is only shown behind your device credential")
        else { pending = what; cred.launch(i) }
    }
    LaunchedEffect(revealed) { if (revealed != null) { kotlinx.coroutines.delay(20_000); revealed = null } }

    PageColumn("c3_cfg_fleet_token") {
        Card("Fleet token", "c3_fleet_token") {
            Kv("Status", if (st.isSet) "set" else "not set", up = st.isSet)
            Kv("Value", if (st.isSet) "••••" + st.last4 else "-", mono = true)
            Kv("Set on", st.setAtMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "-")
            revealed?.let { Kv("Revealed (hides in 20 s)", it, mono = true) }
            Acts {
                Act("Paste", "c3_fleet_token_paste") {
                    val raw = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
                    if (vault.set(raw.orEmpty())) { tick++; toast(ctx, "Token saved") } else toast(ctx, "The clipboard does not hold a token")
                }
                Act("Generate", "c3_fleet_token_generate", enabled = !st.isSet) { vault.generate(); tick++; toast(ctx, "Generated a 256-bit token") }
                Act("Rotate", "c3_fleet_token_rotate", enabled = st.isSet) { vault.rotate(); revealed = null; tick++; toast(ctx, "Rotated: peers read the new value on their next request") }
            }
            Acts {
                Act("Reveal", "c3_fleet_token_reveal", enabled = st.isSet) { if (revealed != null) revealed = null else gated("reveal") }
                Act("Copy", "c3_fleet_token_copy", enabled = st.isSet) { gated("copy") }
                Act("Clear", "c3_fleet_token_clear", enabled = st.isSet) { vault.clear(); revealed = null; tick++; toast(ctx, "Token cleared") }
            }
            Note("Encrypted at rest with a key in the Android Keystore. The mesh listener and every fleet member read it from this app; the SuperApp's About field is a read-only mirror.")
        }
    }
}
