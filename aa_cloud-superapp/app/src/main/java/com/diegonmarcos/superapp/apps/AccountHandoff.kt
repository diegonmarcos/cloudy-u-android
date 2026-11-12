package com.diegonmarcos.superapp.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient

/**
 * #867 Account is its own app now: Cloud Account (ac_cloud-account), built from the same
 * libs:account this app hosts.
 *
 * The Configs Account entry launches it (build.json: action extapp:cloud-account, with
 * ui.external_apps[id=cloud-account] offering the APK when it is absent). This hand-off covers
 * the page route that stays (SectionPages, config/profile): with Cloud Account installed it is
 * a button that opens it, otherwise one Install Cloud Account button (the same fleet install
 * the Store hand-off uses). Cloud Account redesign task 3 deleted the embedded Account page
 * (ProfileFragment) from libs:account, so there is no embedded fallback any more; task 8
 * finishes the SuperApp side (the profile.* declarations).
 *
 * #1693 the data follows the same rule: this app keeps no copy of Cloud Account's stores; what it
 * needs (a granted secret, the profile, sign-in state) it asks Cloud Account for over its provider
 * ([AccountClient]).
 */
object AccountHandoff {
    const val PKG = AccountClient.PKG
    private const val ACTION_OPEN = "$PKG.OPEN"

    fun installed(ctx: Context): Boolean = AccountClient.installed(ctx)

    /** Opens Cloud Account. False when it is not installed or will not start. */
    fun open(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(ACTION_OPEN).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)   // not installed / refused

    /** Opens Cloud Account, or starts its fleet install when it is absent. Returns the line to show. */
    fun openOrInstall(ctx: Context): String? =
        if (open(ctx)) null
        else com.diegonmarcos.superapp.launcher.AppInstall.start(ctx, PKG, LABEL).message

    private const val LABEL = "Cloud Account"

    /** The fragment SectionPages puts where the Account page was. */
    fun page(): Fragment = Page()

    class Page : Fragment() {
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            // A page saved by an older build restores the embedded Account child it held; that
            // child no longer exists, so any restored child goes before the view is built.
            if (childFragmentManager.fragments.isNotEmpty())
                childFragmentManager.beginTransaction()
                    .apply { childFragmentManager.fragments.forEach(::remove) }.commitNow()
        }

        override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
            val installed = installed(requireContext())
            return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
                setContent {
                    com.diegonmarcos.superapp.uikit.KitDenseMaterial(androidx.compose.material3.darkColorScheme()) {
                        androidx.compose.material3.Surface { if (installed) OpenCloudAccount() else InstallCloudAccount() }
                    }
                }
            }
        }
    }

    @Composable
    private fun InstallCloudAccount() {
        val ctx = LocalContext.current
        var message by remember { mutableStateOf<String?>(null) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Account is its own app now: Cloud Account.\nSign-in, the device profile, backups and setup live there.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            com.diegonmarcos.superapp.uikit.KitButton(onClick = {
                message = com.diegonmarcos.superapp.launcher.AppInstall.start(ctx, PKG, LABEL).message
            }) { Text("Install Cloud Account") }
            message?.let { Text(it, Modifier.padding(top = 12.dp), textAlign = TextAlign.Center) }
        }
    }

    @Composable
    private fun OpenCloudAccount() {
        val ctx = LocalContext.current
        var failed by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Account is its own app now: Cloud Account.\nSign-in, the device profile, backups and setup live there.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            com.diegonmarcos.superapp.uikit.KitButton(onClick = { failed = !open(ctx) }) { Text("Open Cloud Account") }
            if (failed) Text("Cloud Account did not open - is it still installed?", Modifier.padding(top = 12.dp))
        }
    }
}
