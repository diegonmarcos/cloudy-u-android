package com.diegonmarcos.superapp.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.apps.CloudStoreHandoff
import com.diegonmarcos.superapp.appstore.RetiredApps
import com.diegonmarcos.superapp.updater.Fleet

/**
 * #1736 Cloud ▸ Apps: holding (long-press, #1739) a fleet app's tile opens this dropdown; a tap launches the app.
 *
 *  - Actions: "Open" first (what the tap did before, rule 16: the tile's own target), then the app's own actions,
 *    all DECLARED: `ui.external_apps[].actions` plus the app's deep-link tiles on the Cloud page
 *    (`extapp:<id>#page:...`), reused as they are, never copied.
 *  - Configs: Uninstall, Update (Cloud Store's install/update hand-off), App Info (Android's app details for the
 *    package), About (Cloud Store's About sheet for the app, [com.diegonmarcos.superapp.appstore.StoreAbout]).
 *  - Urls: Repo URL and Release APK URL from constellation-fleet.json (`repo_url`, `release_url`), each opens in
 *    Cloud Browser or is copied.
 *
 * The model ([menu], [resolve], [actionsOf], [urlsOf], [dropRetired]) is pure so it is unit-tested on the JVM;
 * [Menu] only draws it and [run] only performs a tap. A missing piece of data is a disabled row with a reason, never
 * a crash and never a missing row.
 */
object CloudAppMenu {

    enum class Group(val title: String) { ACTIONS("Actions"), CONFIGS("Configs"), URLS("Urls") }
    enum class Verb { OPEN, ACTION, UNINSTALL, UPDATE, APP_INFO, ABOUT, OPEN_URL, COPY_URL }

    /** One row. [arg] = the tile target (OPEN/ACTION), the package (UNINSTALL/UPDATE/APP_INFO/ABOUT) or the URL. */
    class Item(val group: Group, val verb: Verb, val label: String, val arg: String = "",
               val enabled: Boolean = true, val note: String = "") {
        val tag: String get() = "cloudmenu:${group.name.lowercase()}:$label"
    }

    /** The fleet app a tile opens: its `ui.external_apps` id (null for a bare `app://` target), package, tile target. */
    class Ref(val extId: String?, val pkg: String, val openTarget: String)

    /** #1739: the dropdown opens on a long-press of a fleet app's tile only; a tap (longPress=false) launches the app. */
    fun opensMenu(longPress: Boolean, hasMenu: Boolean): Boolean = longPress && hasMenu

    /** The app behind [target] (`extapp:<id>[/fork][#deep]` or `app://<package>`), null for a page, an intent, a URL. */
    fun resolve(target: String, ext: List<Sections.ExternalApp>): Ref? {
        if (target.startsWith("extapp:")) {
            val id = target.removePrefix("extapp:").substringBefore('#').substringBefore('/')
            val app = ext.firstOrNull { it.id == id } ?: return null
            val pkg = app.installPackage.ifBlank { app.hubPackage }
            return if (pkg.isBlank()) null else Ref(id, pkg, target)
        }
        if (target.startsWith("app://")) {
            val pkg = target.removePrefix("app://").substringBefore('#').trim()
            if (pkg.isEmpty()) return null
            return Ref(ext.firstOrNull { it.installPackage == pkg || it.hubPackage == pkg }?.id, pkg, target)
        }
        return null
    }

    /** The app's own actions as (label, target): declared ones first, then its deep-link tiles; never the Open target. */
    fun actionsOf(ref: Ref, ext: List<Sections.ExternalApp>, tiles: List<Sections.AggTile>): List<Sections.AppAction> {
        val id = ref.extId ?: return emptyList()
        val declared = ext.firstOrNull { it.id == id }?.actions.orEmpty()
        fun flat(l: List<Sections.AggTile>): List<Sections.AggTile> = l.flatMap { listOf(it) + flat(it.children) }
        val links = flat(tiles).filter { !it.separator && it.target.startsWith("extapp:$id#") }
            .map { Sections.AppAction(it.label, it.target) }
        return (declared + links).filter { it.target != ref.openTarget }.distinctBy { it.target }
    }

    /** Only a real web URL is ever offered; anything else is "not declared". */
    fun webUrl(u: String?): String? = u?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** (repo URL, release APK URL) of the fleet row for [pkg] (its package or stock alt id); both null when none. */
    fun urlsOf(fleet: List<Fleet.App>, pkg: String): Pair<String?, String?> {
        val app = fleet.firstOrNull { it.pkg == pkg || it.altId == pkg } ?: return null to null
        val release = runCatching { app.abiReleaseUrl }.getOrDefault(app.releaseUrl)
        return webUrl(app.repoUrl) to webUrl(release)
    }

    /** The menu of one app, in order: Actions, Configs, Urls. Total: every row is always present. */
    fun menu(ref: Ref, actions: List<Sections.AppAction>, installed: Boolean, repoUrl: String?, releaseUrl: String?): List<Item> {
        val gone = if (installed) "" else "not installed"
        val out = mutableListOf(Item(Group.ACTIONS, Verb.OPEN, "Open", ref.openTarget))
        for (a in actions) out += Item(Group.ACTIONS, Verb.ACTION, a.label, a.target)
        out += Item(Group.CONFIGS, Verb.UNINSTALL, "Uninstall", ref.pkg, installed, gone)
        out += Item(Group.CONFIGS, Verb.UPDATE, "Update", ref.pkg)
        out += Item(Group.CONFIGS, Verb.APP_INFO, "App Info", ref.pkg, installed, gone)
        out += Item(Group.CONFIGS, Verb.ABOUT, "About", ref.pkg)
        for ((name, url) in listOf("Repo URL" to repoUrl, "Release APK URL" to releaseUrl)) {
            val why = if (url == null) "not declared in the fleet data" else ""
            out += Item(Group.URLS, Verb.OPEN_URL, name, url.orEmpty(), url != null, why)
            out += Item(Group.URLS, Verb.COPY_URL, "Copy $name", url.orEmpty(), url != null, why)
        }
        return out
    }

    // ── Retired apps never appear in the Cloud list ──────────────────────────────────────────

    /** The packages a tile target stands for (the app it opens), empty for a page/intent tile. */
    fun packagesOf(target: String, ext: List<Sections.ExternalApp>): Set<String> {
        val ref = resolve(target, ext) ?: return emptySet()
        val app = ext.firstOrNull { it.id == ref.extId }
        return setOfNotNull(ref.pkg, app?.hubPackage?.takeIf { it.isNotBlank() }, app?.altPackage?.takeIf { it.isNotBlank() })
    }

    /** [tiles] without the ones that open a RETIRED app (appstore-retired-apps.json); a folder keeps its other children. */
    fun dropRetired(tiles: List<Sections.AggTile>, ext: List<Sections.ExternalApp>, retired: Collection<String>): List<Sections.AggTile> {
        if (retired.isEmpty()) return tiles
        val bad = retired.toSet()
        fun keep(t: Sections.AggTile): Boolean = t.separator || packagesOf(t.target, ext).none { it in bad }
        return tiles.filter(::keep).map { t -> if (t.children.isEmpty()) t else t.copy(children = dropRetired(t.children, ext, bad)) }
    }

    fun retiredPackages(ctx: Context): Set<String> = RetiredApps.load(ctx).map { it.pkg }.toSet()

    // ── Doing a tap ──────────────────────────────────────────────────────────────────────────

    private fun fleet(): List<Fleet.App> = runCatching { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }.getOrDefault(emptyList())

    fun installed(ctx: Context, pkg: String): Boolean = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    /** The menu for a tile target, or null when the target is not a fleet app (a page, an intent): that tile taps as before. */
    fun forTarget(ctx: Context, target: String): List<Item>? {
        val ext = runCatching { Sections.externalApps() }.getOrDefault(emptyList())
        val ref = resolve(target, ext) ?: return null
        val tiles = runCatching { Sections.all().flatMap { it.tileGroups }.flatMap { it.tiles } }.getOrDefault(emptyList())
        val (repo, release) = urlsOf(fleet(), ref.pkg)
        return menu(ref, actionsOf(ref, ext, tiles), installed(ctx, ref.pkg), repo, release)
    }

    /** Perform one row. [open] dispatches a tile target exactly as a tap did before. */
    fun run(ctx: Context, item: Item, open: (String) -> Unit) {
        if (!item.enabled) { Toast.makeText(ctx, "${item.label}: ${item.note}", Toast.LENGTH_SHORT).show(); return }
        runCatching {
            when (item.verb) {
                Verb.OPEN, Verb.ACTION -> open(item.arg)
                Verb.UNINSTALL -> AppLongPressMenu.requestUninstall(ctx, item.arg)
                Verb.UPDATE -> update(ctx, item.arg)
                Verb.APP_INFO -> com.diegonmarcos.superapp.perms.PermsPages.openAppInfo(ctx, item.arg)
                Verb.ABOUT -> about(ctx, item.arg)
                Verb.OPEN_URL -> openInBrowser(ctx, item.arg)
                Verb.COPY_URL -> {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText(item.label, item.arg))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure { Toast.makeText(ctx, "${item.label}: ${it.message ?: "failed"}", Toast.LENGTH_LONG).show() }
    }

    /** Update/install through Cloud Store, the installer of record; without it, the SuperApp's own fleet install. */
    private fun update(ctx: Context, pkg: String) {
        if (CloudStoreHandoff.installed(ctx)) {
            ctx.startActivity(com.diegonmarcos.superapp.updater.install.StoreHandoff.intent(ctx.packageName, pkg))
        } else {
            AppLongPressMenu.requestUpdateOf(ctx, pkg)
        }
    }

    /** Cloud Store's About sheet for [pkg]; no Store = say so (the Store tile installs it). */
    private fun about(ctx: Context, pkg: String) {
        if (!CloudStoreHandoff.installed(ctx)) {
            Toast.makeText(ctx, "About opens in Cloud Store, which is not installed", Toast.LENGTH_LONG).show(); return
        }
        ctx.startActivity(Intent("com.diegonmarcos.cloudstore.OPEN").setPackage(CloudStoreHandoff.PKG)
            .putExtra("tab", "cloud").putExtra(com.diegonmarcos.superapp.appstore.StoreAbout.EXTRA, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Cloud Browser when it is installed, else the phone's default handler. */
    private fun openInBrowser(ctx: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val browser = runCatching { Sections.externalApp("cloud-browser")?.installPackage }.getOrNull().orEmpty()
        val viaBrowser = browser.isNotBlank() && installed(ctx, browser) &&
            runCatching { ctx.startActivity(Intent(view).setPackage(browser)); true }.getOrDefault(false)
        if (!viaBrowser) ctx.startActivity(view)
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────────────────

    const val TAG_MENU = "cloudmenu:menu"

    @Composable
    fun Menu(items: List<Item>, onDismiss: () -> Unit, onPick: (Item) -> Unit) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss, modifier = Modifier.testTag(TAG_MENU)) {
            var last: Group? = null
            for (it in items) {
                if (it.group != last) {
                    last = it.group
                    Text(it.group.title.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp).testTag("cloudmenu:group:${it.group.name.lowercase()}"))
                }
                DropdownMenuItem(
                    text = {
                        Text(if (it.enabled || it.note.isEmpty()) it.label else "${it.label} (${it.note})", fontSize = 14.sp,
                            modifier = Modifier.alpha(if (it.enabled) 1f else 0.45f))
                    },
                    onClick = { onPick(it) },
                    modifier = Modifier.fillMaxWidth().testTag(it.tag),
                )
            }
        }
    }
}
