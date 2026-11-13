package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.compose.runtime.Composable
import com.diegonmarcos.superapp.accountui.AccountUiHost
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.AccountProfilePage
import com.diegonmarcos.superapp.profile.GhEngine
import com.diegonmarcos.superapp.profile.RunbookPage
import com.diegonmarcos.superapp.profile.ShellChannels

/** #1439 what libs:account-ui's shared pages need from Cloud Account: the gh engine's token, the privileged shell, the Runbook setup, the channel bar, Others > Me. */
object AccountUiHostImpl : AccountUiHost {
    override fun ghToken(ctx: Context): String? = AccountModel.ghHost()?.let { h -> runCatching { GhEngine(ctx).token(h) }.getOrNull() }
    override fun appCredential(ctx: Context, id: String): String? = runCatching { com.diegonmarcos.superapp.profile.AppCredentialsStore.get(com.diegonmarcos.superapp.settings.AccountVault(ctx), id) }.getOrNull()?.takeIf { it.isNotBlank() }
    override fun shell(ctx: Context, cmd: String): String? = ShellChannels.active(ctx)?.exec(ctx, cmd)
    @Composable override fun RunbookSetup(go: (String, String) -> Unit) = RunbookPage(go)
    @Composable override fun ChannelBar(onChange: () -> Unit) = ShellBrokerPanel(onChange)
    @Composable override fun MePage(go: (String, String) -> Unit) = AccountProfilePage(go)
}
