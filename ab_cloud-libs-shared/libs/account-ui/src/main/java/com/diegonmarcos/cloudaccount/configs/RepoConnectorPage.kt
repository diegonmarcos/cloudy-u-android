package com.diegonmarcos.cloudaccount.configs

import androidx.compose.runtime.Composable
import com.diegonmarcos.cloudlib.gitui.GitConnectPanel
import com.diegonmarcos.superapp.accountui.AccountUiHost

/**
 * #1445 Configs > Repos: the owner's private repos declared in account-ui.json::configs_sources.connectors, pulled on
 * open, edited here and committed + pushed on save. The page holds NO connect logic: it hands the declaration to
 * libs:git-ui's GitConnectPanel (the one GitHub connect Cloud Drive also uses) with the host's GitHub sign-in as the
 * token. Nothing private is in the app; the files live in the repo.
 */
@Composable
fun RepoConnectorPage() {
    GitConnectPanel(ConfigsSources.baked.connectors, hostToken = { ctx -> AccountUiHost.current.ghToken(ctx) })
}
