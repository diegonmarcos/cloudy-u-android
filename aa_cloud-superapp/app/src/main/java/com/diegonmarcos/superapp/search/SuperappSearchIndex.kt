package com.diegonmarcos.superapp.search

import android.content.Context
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.cloud.CloudData
import com.diegonmarcos.superapp.launcher.Sections

/**
 * The superapp half of the search: everything libs:search's engine cannot know.
 *
 * The matching, the sections, the chip rule and the `:`-command line are
 * libs:search ([SearchEngine]); the panel that draws them is [SearchPanel]; what
 * is here is the data — the four index builders made of THIS app's data
 * (sections, tiles, pages, the consolidated cloud config) plus the
 * launcher-profile-filtered app list, and Cloud Browser's three scopes, asked
 * per query through its lookup provider ([BrowserLookupClient]).
 */
object SuperappSearchIndex {

    /** Hits for one declared scope. Unknown kinds yield nothing rather than
     *  throwing, so adding a scope to build.json before its builder exists
     *  degrades to an empty chip instead of a crash. */
    fun hitsFor(ctx: Context, scope: SearchScope): List<SearchHit> = com.diegonmarcos.superapp.xray.EngineProfiler.time("search", "index") { when (scope.kind) {
        "cloud_apps"    -> cloudApps(scope.id)
        "phone_apps"    -> phoneApps(scope.id, ctx)
        "cloud_configs" -> cloudConfigs(scope.id, ctx)
        "phone_configs" -> PhoneConfigs.hits(ctx, scope.id)
        SearchKinds.CONFIGS -> configsOnly(scope.id)
        SearchKinds.APP_ALL -> cloudApps(scope.id) + cloudConfigs(scope.id, ctx)
        // browser_fav / browser_history / browser_web are per query: [Source.live].
        else -> emptyList()
    } }

    /** The app's [SearchSource]: the index above, Cloud Browser's scopes per query, the commands. */
    class Source(ctx: Context) : SearchSource {
        private val app = ctx.applicationContext
        override fun hitsFor(scope: SearchScope) = SuperappSearchIndex.hitsFor(app, scope)
        override fun live(scopes: List<SearchScope>, query: String) = BrowserLookupClient.live(app, scopes, query)
        override fun commands() = SuperappSearchIndex.commands()
    }

    /**
     * The `:`-command table, derived from the home actions the app already
     * declares — no second list to keep in step. `update_all` becomes
     * `:update-all`, because underscores are two taps on a phone keyboard and
     * kebab-case is what a command line looks like everywhere else.
     */
    fun commands(): List<SearchCommand> =
        Sections.homeActions().map { act ->
            SearchCommand(
                alias = act.actionType.replace('_', '-'),
                label = act.label,
                target = "action:${act.actionType}",
                crumb = act.label,
            )
        }.distinctBy { it.alias }.sortedBy { it.alias }

    /** Cloud-Apps: navigable cloud destinations — sections, their tiles, and
     *  home actions. (Sub-pages live in Cloud-Configs.) */
    private fun cloudApps(scopeId: String): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            out += SearchHit(sec.label, "Section", scopeId, target = "section:${sec.id}", iconName = sec.iconName)
            // EVERY `tiles_<x>` list, not a hardcoded shared/apps/admin three:
            // tilesByPage keys on the suffix, so a section that renames a page
            // (Cloud: tiles_admin -> tiles_c3) keeps its tiles searchable
            // instead of quietly dropping them out of the index.
            for (tile in sec.tilesByPage.values.flatten()) {
                out += SearchHit(tile.label, "${sec.label} · Tile", scopeId, target = tile.target, iconName = tile.iconName)
            }
        }
        // The grouped rows a section page draws (Cloud ▸ Apps' Quickmarks: a folder's entries
        // included, separators not), so everything on that page can be found from its search.
        val seen = out.mapNotNull { it.target }.toMutableSet()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            for (group in sec.tileGroups) for (tile in group.destinations) {
                if (tile.target.isBlank() || !seen.add(tile.target)) continue
                out += SearchHit(tile.label, "${sec.label} · ${group.title}", scopeId, target = tile.target, iconName = tile.iconName)
            }
        }
        for (act in Sections.homeActions()) {
            out += SearchHit(act.label, "Action", scopeId, target = "action:${act.actionType}")
        }
        return out
    }

    /** Phone-Apps: installed launchable apps. Goes through
     *  [PhoneAppsFragment.snapshot] so the launcher-profile filter (Guest
     *  whitelist) applies here exactly as it does on the Phone tab — the
     *  reason this scope is NOT in the library. */
    private fun phoneApps(scopeId: String, ctx: Context): List<SearchHit> =
        PhoneAppsFragment.snapshot(ctx).mapNotNull { app ->
            // snapshot() is the launchable set, so the component is always
            // present; mapNotNull (not let) so a non-launchable tile that
            // ever enters the snapshot cannot hand the search index a null
            // component to launch.
            app.activityComponent?.let { comp ->
                SearchHit(app.label, "Phone app · ${app.packageName}", scopeId,
                    phoneApp = PhoneAppRef(comp, app.user))
            }
        }

    /** Configs' own scope (#1076): the pages of the `config` section, read from the same declaration the Configs grid
     *  draws (name, heading crumb, and the settings a page declares), nothing from any other section. */
    internal fun configsOnly(scopeId: String): List<SearchHit> = Sections.byId("config")?.let { sec ->
        sec.pages.filter { !it.separator }.flatMap { page ->
            val target = if (page.action.isNotBlank()) page.action else "page:${sec.id}/${page.id}"
            val crumb = listOf(sec.label, page.group, page.subgroup).filter { it.isNotBlank() }.joinToString(" · ")
            listOf(SearchHit(page.label, crumb, scopeId, target = target)) +
                page.settings.map { SearchHit(it, "$crumb · ${page.label}", scopeId, target = target) }
        }
    }.orEmpty()

    /** Cloud-Configs: every section sub-page PLUS cached consolidated.json
     *  service entries (tap copies the value). Consolidated entries appear
     *  only once some screen has fetched the config this install — this stays
     *  network-free. */
    private fun cloudConfigs(scopeId: String, ctx: Context): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        for (sec in Sections.all().filter { !it.isMasterIndex }) {
            for (page in sec.pages) {
                out += SearchHit(page.label, "${sec.label} · Page", scopeId,
                    target = "page:${sec.id}/${page.id}")
            }
        }
        val root = CloudData.cachedOrNull(ctx) ?: return out
        val services = CloudData.services(root)
        val names = services.keys()
        while (names.hasNext()) {
            val name = names.next()
            val svc = services.optJSONObject(name) ?: continue
            val domain = svc.optString("domain", svc.optString("private_dns", ""))
            val vm = svc.optString("vm", "")
            val crumb = listOf("Cloud config", vm, domain).filter { it.isNotBlank() }.joinToString(" · ")
            out += SearchHit(name, crumb, scopeId, copyValue = domain.ifBlank { name })
        }
        return out
    }
}
