package com.diegonmarcos.superapp.accountui

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudaccount.autofill.AutofillProfilesPage
import com.diegonmarcos.cloudaccount.autofill.AutofillRulesPage
import com.diegonmarcos.cloudaccount.autofill.AutofillSnippetsPage
import com.diegonmarcos.cloudaccount.configs.CloudAppsPage
import com.diegonmarcos.cloudaccount.configs.ContainersPage
import com.diegonmarcos.cloudaccount.configs.DesktopTreePage
import com.diegonmarcos.cloudaccount.configs.InfraPage
import com.diegonmarcos.cloudaccount.configs.PhoneAppsPage
import com.diegonmarcos.cloudaccount.configs.RepoConnectorPage
import com.diegonmarcos.cloudaccount.configs.RunbookTogglesPage
import com.diegonmarcos.cloudaccount.personal.PersonalEntriesPage
import com.diegonmarcos.cloudaccount.personal.PersonalKind
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1439 libs:account-ui: the profile structure Cloud Account and Cloud Me share, declared ONCE in this lib's
 * account-ui.json (baked as BuildConfig.ACCOUNT_UI_B64): Personal Info (IDs | Pay Cards | Others | Autocomplete) and
 * Configs (Android: Cloud Apps / Phone Apps / Runbook | Desktop | C3: Infra / Containers). The pages are this lib's
 * (packages com.diegonmarcos.cloudaccount.personal / autofill / configs / hub, moved here from Cloud Account unchanged);
 * what only a host app can do (the GitHub token, the privileged shell, the Runbook setup steps, the channel bar, the
 * Me page) comes through [AccountUiHost].
 */
interface AccountUiHost {
    /** The GitHub token for a private source repo, or null (public repos still read). */
    fun ghToken(ctx: Context): String? = null
    /** Runs one adb-shell line through the host's privileged channel; null = no channel answered. */
    fun shell(ctx: Context, cmd: String): String? = null
    /** Configs > Android > Runbook > Setup steps. */
    @Composable fun RunbookSetup(go: (String, String) -> Unit) { HostNote("The setup steps open in Cloud Account.") }
    /** The privileged channel selector above the Runbook switches. */
    @Composable fun ChannelBar(onChange: () -> Unit) {}
    /** Personal Info > Others > Me. */
    @Composable fun MePage(go: (String, String) -> Unit) { HostNote("Your account card opens in Cloud Account.") }

    companion object {
        /** Set by the host app at start-up (Application or Activity onCreate); the default does only what needs no host. */
        @Volatile var current: AccountUiHost = object : AccountUiHost {}
    }
}

@Composable
private fun HostNote(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp).testTag("accountui:host-note"))
}

/** One node of the declared structure. */
data class AccountUiNode(val id: String, val label: String, val icon: String, val pages: List<AccountUiNode>, val render: String = "") {
    val leaves: List<String> get() = if (pages.isEmpty()) listOf(id) else pages.flatMap { it.leaves }
    fun nav(): NavPage = NavPage(id, label, icon, pages = pages.map { it.nav() })
}

object AccountUi {
    const val PERSONAL: String = "personal"
    const val CONFIGS: String = "configs"

    /** The two sections, in order, from account-ui.json::sections. */
    val sections: List<AccountUiNode> by lazy {
        parse(runCatching { String(Base64.decode(BuildConfig.ACCOUNT_UI_B64, Base64.DEFAULT)) }.getOrDefault("{}"))
    }

    /** #1445 the renderer a leaf [page] of [section] declares (account-ui.json `render`); "" when the declaration has none. */
    fun renderOf(section: String, page: String): String {
        fun find(n: AccountUiNode): AccountUiNode? = if (n.id == page && n.pages.isEmpty()) n else n.pages.firstNotNullOfOrNull(::find)
        return sections.firstOrNull { it.id == section }?.let(::find)?.render.orEmpty()
    }

    /** #1445 the field schema of a Personal Info kind (account-ui.json `kinds`, labels and masks only), or null when the build carries none. */
    fun kind(key: String): JSONObject? = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.ACCOUNT_UI_B64, Base64.DEFAULT))).optJSONObject("kinds")?.optJSONObject(key)
    }.getOrNull()

    fun parse(json: String): List<AccountUiNode> {
        fun node(o: JSONObject): AccountUiNode = AccountUiNode(
            o.optString("id"), o.optString("label"), o.optString("icon"),
            (o.optJSONArray("pages") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::node) } },
            o.optString("render"),
        )
        val a = runCatching { JSONObject(json).optJSONArray("sections") }.getOrNull() ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::node) }
    }
}

/** (section, leaf page) of the shared structure → its page. Cloud Account's MainActivity and [AccountProfileScreen] both mount it. */
@Composable
fun AccountUiPage(section: String, page: String, go: (String, String) -> Unit) {
    // #1445 data driven: the renderer is what account-ui.json declares for the leaf, not a hard-coded page id.
    val render = AccountUi.renderOf(section, page)
    when (section) {
        AccountUi.PERSONAL -> when (render) {
            // #1438 IDs, Pay Cards, Others > Entries: EncryptedSharedPreferences only, masked until device auth.
            "entries:ids" -> PersonalEntriesPage(PersonalKind.IDS)
            "entries:cards" -> PersonalEntriesPage(PersonalKind.CARDS)
            "entries:others" -> PersonalEntriesPage(PersonalKind.OTHERS)
            // Autocomplete: the non-secret autofill Source of Truth (a0_docs/eng-specs/autofill-3-tier.md).
            "autofill:profiles" -> AutofillProfilesPage()
            "autofill:rules" -> AutofillRulesPage()
            "autofill:snippets" -> AutofillSnippetsPage()
            "host:me" -> AccountUiHost.current.MePage(go)
            else -> PersonalEntriesPage(PersonalKind.IDS)
        }
        // PUBLIC: no page here may show a secret value (test-account-configs-public.sh lists the only composables allowed).
        AccountUi.CONFIGS -> when (render) {
            // #1438 Android (Cloud Apps, Phone Apps, Runbook), Desktop, C3 (Infra, Containers): read from the source repos at run time.
            "configs:phone-apps" -> PhoneAppsPage()
            "configs:runbook" -> RunbookTogglesPage(go)
            "configs:desktop" -> DesktopTreePage()
            "configs:infra" -> InfraPage()
            "configs:containers" -> ContainersPage()
            // #1445 the owner's private repo, read and written with the owner's token (RepoConnector.kt).
            "configs:repos" -> RepoConnectorPage()
            else -> CloudAppsPage()
        }
    }
}

object AccountUiTags {
    const val SCREEN: String = "accountui:screen"
    fun strip(level: Int): String = "accountui:strip:$level"
}

/**
 * #1439 the whole profile page for an app that has no section of its own for it (Cloud Me's top-right profile
 * button): the sections, then one strip per level of the declared structure, then [AccountUiPage]. [section] and
 * [path] open a given page (ids of account-ui.json).
 */
@Composable
fun AccountProfileScreen(modifier: Modifier = Modifier, section: String = AccountUi.PERSONAL, dark: Boolean = true) {
    val roots = AccountUi.sections
    if (roots.isEmpty()) { HostNote("The profile structure is missing from this build."); return }
    var chain by rememberSaveable { mutableStateOf(listOf(roots.firstOrNull { it.id == section }?.id ?: roots.first().id)) }
    val go: (String, String) -> Unit = { s, p ->
        val root = roots.firstOrNull { it.id == s }
        if (root != null) chain = listOf(root.id) + path(root, p).orEmpty()
    }
    Column(modifier.fillMaxSize().testTag(AccountUiTags.SCREEN)) {
        // The chain as drawn: a level whose saved id is gone (or was never chosen) shows its first page.
        val eff = mutableListOf<String>()
        var siblings: List<AccountUiNode> = roots
        var level = 0
        while (siblings.isNotEmpty()) {
            val selected = siblings.firstOrNull { it.id == chain.getOrNull(level) } ?: siblings.first()
            val lv = level
            eff += selected.id
            NavBarTopSecondRow(
                pages = siblings.map { it.nav() }, selectedId = selected.id,
                onSelect = { p -> chain = eff.take(lv) + p.id },
                modifier = Modifier.testTag(AccountUiTags.strip(lv)), dark = dark, underTopChrome = false,
            )
            siblings = selected.pages
            level++
        }
        Column(Modifier.weight(1f).fillMaxWidth()) { AccountUiPage(eff.first(), eff.last(), go) }
    }
}

private fun path(n: AccountUiNode, leaf: String): List<String>? {
    for (c in n.pages) {
        if (c.id == leaf) return listOf(c.id)
        path(c, leaf)?.let { return listOf(c.id) + it }
    }
    return null
}
