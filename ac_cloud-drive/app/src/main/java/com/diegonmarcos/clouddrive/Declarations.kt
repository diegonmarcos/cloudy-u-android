package com.diegonmarcos.clouddrive

import com.diegonmarcos.cloudlib.gitrepos.*

import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.clouddrive.sync.GitHostsDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * #579 THE ONE reader of the build-time declarations. app/build.gradle bakes every
 * declarative list this app renders — build.json::ui (bottom_nav + sections + default_section,
 * files sections/places/filters), data/drive-*.json — into BuildConfig as base64 JSON; this file
 * decodes each ONCE into typed models and nothing else touches a BuildConfig blob. #868 the
 * navigation (the island's tabs and the Configs / Sync / Volumes strips) is libs:bottomnav's
 * [NavDecl]: a section's `pages` are the strip PageTabs draws.
 *
 * The parse functions take the JSON text, not BuildConfig, so the JVM suite
 * (DeclarationsTest) exercises the exact parser the phone runs against the
 * repository's own build.json — a declared icon name IconCatalog does not know,
 * or a filter with no rule, fails there before it fails on a device.
 */
object Declarations {

    data class TabDecl(val id: String, val label: String, val icon: String)
    data class PageDecl(val id: String, val label: String, val icon: String)
    /** Configs ▸ Webserver (ui.webserver): cloud-c3-webserver's package (resolved from the fleet manifest), its launcher activity and the URL its server answers on. */
    data class WebserverDecl(val packageName: String, val activity: String, val fallbackUrl: String)
    /** #609 the Configs strip: three sub-pages (Git/Rclone/Mounts moved out to ui.sync.pages). */
    data class ConfigsDecl(val pages: List<PageDecl>)
    /** #609 the Sync strip: Git/Rclone/Mounts, moved back out of Configs, plus the per-repository period choices the Git page offers, plus #608's Git page itself. */
    /** #1216 one line of ui.sync.mounts.providers; [others] is the free line (no remote of its own). */
    data class MountProviderDecl(val id: String, val label: String, val icon: String, val remote: String, val rcloneType: String, val rcloneProvider: String, val endpoint: String, val path: String, val readOnly: Boolean, val storageClass: String, val others: Boolean)
    data class SyncDecl(val pages: List<PageDecl>, val gitPeriodsMinutes: List<Int>, val git: GitPageDecl)

    // ── #608 THE SYNC ▸ GIT PAGE, declared (build.json::ui.sync.git) ────────

    /** One of the page's two sections. [loginRequired] = nothing to show before a sign-in. */
    data class GitSectionDecl(val id: String, val label: String, val icon: String, val loginRequired: Boolean)

    /** How the authenticated listing is split — Public, then Private. */
    data class GitGroupDecl(val id: String, val label: String, val icon: String)

    /**
     * A way into the personal section. [kind] is the ONLY thing the code dispatches on:
     * [KIND_SSH_KEY] reuses the key libs:git-sync already holds and cannot list, so [lists]
     * is false and [provider]/[scope] go unread. #641 there is no browser way left: the
     * `webauth` kind drove a device grant against a GitHub App, whose Device Flow the
     * provider ships OFF, and the HTTPS credential comes from the vault import instead.
     */
    data class GitLoginWayDecl(val id: String, val label: String, val icon: String, val kind: String, val provider: String, val scope: String, val lists: Boolean)

    /**
     * #684 ONE OF THE TWO PARALLEL WAYS into the personal section — GitHub and Cloud git — each an
     * always-visible card with its own sign-in, its own listing and its own clone leg. [rung] names
     * the declared git_chain rung whose KIND decides the card's mechanism (fleet_proxy ⇒ the
     * Authelia session, gh_on_device ⇒ a GitHub credential); the page dispatches on that kind and
     * on nothing typed here. Neither way gates, hides or replaces the other.
     */
    data class GitWayDecl(val id: String, val rung: String, val label: String, val icon: String)

    /** One per-repository operation. [destructive] ⇒ the row asks before running it. */
    data class GitOpDecl(val id: String, val label: String, val icon: String, val destructive: Boolean)
    /**
     * #1254 one DECLARED depth command of the Git page (ui.sync.git.depth_actions): [kind] clone_missing (shallow clone of
     * every registry repo with no clone anywhere on the device) or keep_depth (shrink a clean, fully pushed clone back to
     * [depth] in place); [scope] all = the bulk bar, repo = the row's detail; [workers] bounds the concurrency.
     */
    data class GitDepthActionDecl(val id: String, val label: String, val icon: String, val kind: String, val scope: String, val depth: Int, val workers: Int, val confirm: Boolean, /** #1491 kind clone_mode: the id of the ui.sync.git.clone_modes entry it runs. */ val mode: String = "")
    /** #1288 one DECLARED per-folder command of the Parentless / Unlisted tables (ui.sync.git.folder_actions): [kind] rename_to_repo, archive or delete_local. */
    data class GitFolderActionDecl(val id: String, val label: String, val icon: String, val kind: String, val workers: Int, val confirm: Boolean) {
        /** The same runner as the depth commands: a folder command is a depth command of scope "folder". */
        fun asRun(): GitDepthActionDecl = GitDepthActionDecl(id, label, icon, kind, Declarations.DEPTH_SCOPE_FOLDER, 0, workers, confirm)
    }
    /** #1313 abandoned Git temp packs (incoming_*.pack from JGit, tmp_pack_* from C git) older than [minAgeHours] are deleted from objects/pack; never pack-*. */
    data class GitStalePackDecl(
        val enabled: Boolean = true,
        val minAgeHours: Int = 6,
        val patterns: List<String> = com.diegonmarcos.cloudlib.gitrepos.StalePackCleaner.DEFAULT_PATTERNS,
    )
    /** #1255 the Unlisted git folders section (ui.sync.git.unlisted): root templates, walk bounds. */
    data class GitUnlistedDecl(val label: String = "", val roots: List<String> = emptyList(), val maxDepth: Int = 2, val maxDirs: Int = 2000, val timeoutSeconds: Int = 30)
    /** #1315 the one inventory pass (ui.sync.git.inventory): the folder-name globs that are leftovers, never repositories; whether a clone the pass finds under a registry name is registered for the scheduler. */
    /** #1315 the one executor's declared bounds (ui.sync.git.jobs): how many clones a bulk verb (sync all, fetch all, stash & push all) works on at once. */
    data class GitJobsDecl(val workers: Int = 2)
    data class GitInventoryDecl(val debris: List<String> = listOf("*.incoming"), val autoRegister: Boolean = true, /** #1315 leftovers older than this are deleted by the cleanup job (auto after a pass when cleanupAuto). */ val cleanupMinAgeHours: Int = 6, val cleanupAuto: Boolean = true, /** the section titles and lines of the inventory's own sections (duplicates, bare, debris, ...), by key. */ val labels: Map<String, String> = emptyMap()) {
        /** The declared label for [key], else the key itself spelled out (a missing label is visible, never a crash). */
        fun label(key: String): String = labels[key] ?: key.replace('_', ' ')
    }
    /**
     * #1256/#1315 one declared filter of the Git page and its options. An option's MEANING is data: [kinds] (RepoStatus.Kind names,
     * lower-case), [cloned], [lfs], [private], [hub], [archived] (null = not looked at), [level] ("full" = only a worktree-scanned row);
     * every predicate given must hold. [quick] = drawn on the quick-filter rail. The filter's [default] option is the one an empty
     * selection means ("all" unless declared, e.g. archived = hidden); [layout] is a hint for the sheet.
     */
    data class GitFilterOptionDecl(
        val id: String, val label: String, val quick: Boolean = false, val kinds: List<String> = emptyList(),
        val cloned: Boolean? = null, val lfs: Boolean? = null, val private: Boolean? = null, val hub: Boolean? = null, val archived: Boolean? = null, val level: String = "",
    )
    data class GitFilterDecl(val id: String, val label: String, val options: List<GitFilterOptionDecl>, val layout: String = "chips", val default: String = "all") {
        fun option(id: String): GitFilterOptionDecl? = options.firstOrNull { it.id == id }
    }
    /**
     * #1315 THE TOP BAR as data (ui.sync.git.top_bar): groups in order, each one split-menu button; an item is dispatched on
     * [kind] alone (GitReposScreen's `when (item.kind)`): check_light | check_full | check_remote | check_sizes | scan_inventory |
     * cancel_checks | op | compound | pull | depth | open_logs | open_report | toggle_filter | legend. [scope] filtered = the
     * rows the filters and the search leave, all = every registered clone; [confirm] asks first naming the target count; [danger]
     * is drawn red and always confirms; [exclusive] = refused while another writing job runs.
     */
    data class GitTopItemDecl(
        val id: String, val kind: String, val label: String, val hint: String = "", val icon: String = "", val op: String = "", val action: String = "",
        val force: Boolean = false, val scope: String = "filtered", val confirm: Boolean = false, val danger: Boolean = false, val exclusive: Boolean = true,
        val filter: String = "", val option: String = "",
        /** #1356 the dropdown section the item is listed under ("Sync All", "Manual Sync"); blank = none. */
        val section: String = "",
    )
    data class GitTopGroupDecl(val id: String, val label: String, val icon: String, val items: List<GitTopItemDecl>)
    /** #1356 the Logs page (ui.sync.git.logs): its title, its tabs in order (repos | app) and its labels. */
    data class GitLogsDecl(val title: String = "Logs", val tabs: List<Pair<String, String>> = listOf("repos" to "Repos", "app" to "App"), val labels: Map<String, String> = emptyMap()) {
        fun label(key: String): String = labels[key] ?: key.replace('_', ' ')
    }
    data class GitTopBarDecl(val groups: List<GitTopGroupDecl> = emptyList(), val search: Boolean = true, val searchPlaceholder: String = "", val counters: List<String> = emptyList(), val labels: Map<String, String> = emptyMap(), val logs: GitLogsDecl = GitLogsDecl()) {
        fun label(key: String): String = labels[key] ?: key.replace('_', ' ')
        fun item(id: String): GitTopItemDecl? = groups.flatMap { it.items }.firstOrNull { it.id == id }
    }

    /** What `origin` can be switched to. [readOnly] ⇒ the page refuses push / force push. */
    data class GitRemoteModeDecl(val id: String, val label: String, val icon: String, val url: String, val readOnly: Boolean) {
        /** The declared URL shape with the owner and the repository substituted, once. */
        fun urlFor(owner: String, name: String): String = url.replace("{owner}", owner).replace("{name}", name)
    }

    /** Where the authenticated listing comes from, and the row's web link. */
    data class GitApiDecl(val baseUrl: String, val reposPath: String, val maxPages: Int, val webUrl: String) {
        /** Page [page] (1-based) of the declared list route; the separator follows the declared query. */
        fun reposUrl(page: Int): String =
            baseUrl + reposPath + (if (reposPath.contains('?')) "&" else "?") + "page=" + page.coerceAtLeast(1)
        fun webUrlFor(owner: String, name: String): String = webUrl.replace("{owner}", owner).replace("{name}", name)
    }

    /** #850 the status reader's bounds: per-repository timeout, how many are read at once, cache age. */
    data class GitStatusDecl(val timeoutSeconds: Int = 20, val concurrency: Int = 1, val ttlSeconds: Int = 60, val maxAutoFiles: Int = 3000, val maxScanMs: Long = 1500, val logWindow: Int = 100, /** The three-icon mapping (local / server / sync -> kind -> on|off|unknown|unverifiable) of build.json git.status.icons. */ val icons: Map<String, Map<String, String>> = emptyMap())

    /**
     * #850 "Auto pull on open": [defaultOn] is the setting before the owner touches it, the
     * toggle's value is then persisted per device; [requireUnmetered] keeps it off mobile data
     * (Wi-Fi only), the same constraint git_sync and the seed declare.
     */
    /** #1289 one item of the per-repository menu (ui.sync.git.repo_menu): the id the page dispatches on, its label and translations. */
    data class GitMenuItemDecl(val id: String, val label: String, val i18n: Map<String, String> = emptyMap(), val icon: String = "") {
        fun labelFor(lang: String): String = i18n[lang]?.takeIf { it.isNotBlank() } ?: label
    }
    /** #1289 one group (or subgroup) of the per-repository menu; [lines] = one item per line, [danger] = drawn red and every item asks first. */
    data class GitMenuGroupDecl(
        val id: String, val label: String, val i18n: Map<String, String> = emptyMap(), val lines: Boolean = false,
        val danger: Boolean = false, val items: List<GitMenuItemDecl> = emptyList(), val subgroups: List<GitMenuGroupDecl> = emptyList(),
        /** #1356 layout "row": the items are icon actions on ONE horizontal row. */
        val row: Boolean = false,
    ) {
        fun labelFor(lang: String): String = i18n[lang]?.takeIf { it.isNotBlank() } ?: label
        /** Every item id of this group and its subgroups, in render order. */
        val allIds: List<String> get() = items.map { it.id } + subgroups.flatMap { it.allIds }
    }
    /** #1289 the per-repository Configs-AutoSync defaults; [defaultMinutes] and every option are never under [minMinutes] (WorkManager's floor). */
    data class GitAutoSyncDecl(val defaultOn: Boolean = false, val defaultMinutes: Long = 15, val minMinutes: Long = 15, val optionsMinutes: List<Long> = listOf(15, 30, 60, 180, 360))
    data class GitRepoMenuDecl(val groups: List<GitMenuGroupDecl> = emptyList(), val autoSync: GitAutoSyncDecl = GitAutoSyncDecl()) {
        fun group(id: String): GitMenuGroupDecl? = groups.firstOrNull { it.id == id }
        fun item(id: String): GitMenuItemDecl? = groups.asSequence().flatMap { g -> (g.items + g.subgroups.flatMap { it.items }).asSequence() }.firstOrNull { it.id == id }
        val allIds: List<String> get() = groups.flatMap { it.allIds }
    }
    data class GitAutoPullDecl(val defaultOn: Boolean = false, val requireUnmetered: Boolean = true)

    /** One repository of the declared public set. */
    data class GitPublicRepoDecl(val name: String, val label: String)

    /**
     * #642 HOW A GIT OPERATION LEAVES THIS APP. cloud-drive runs no git: it hands the argv to
     * cloud-terminal-nix's RunCommandService, which has a real git CLI in a real proot rootfs
     * and the credential to use it.
     *
     * Every field is declared (build.json::ui.sync.git.terminal) and [pkg] is substituted into
     * the rest at BUILD time from the constellation fleet manifest, so no package id, action or
     * extra name is a literal in Kotlin. [command] is the terminal's bin/login, whose "called
     * with arguments" branch execs the caller's argv inside the proot (#641) -- a /nix/store
     * binary exec'd straight from Android would die for want of its glibc interpreter.
     */
    data class GitTerminalDecl(
        val pkg: String,
        val action: String,
        val permission: String,
        val command: String,
        val extraPath: String,
        val extraArguments: String,
        val extraWorkdir: String,
        val extraBackground: String,
        val background: Boolean,
        val ops: Map<String, List<String>>,
    ) {
        val declared: Boolean get() =
            pkg.isNotBlank() && action.isNotBlank() && command.isNotBlank() && ops.isNotEmpty()

        /** The argv for [op] with the declared placeholders filled, or null if [op] is not declared. */
        fun argv(op: String, url: String, dest: String, depth: Int = 0): List<String>? =
            ops[op]?.map { it.replace("{url}", url).replace("{dest}", dest) }?.let { argv ->
                // #1138 clone settings: depth N goes right after the declared `clone` word, as two argv items.
                val at = argv.indexOf("clone")
                if (op == Declarations.GIT_OP_CLONE && depth > 0 && at >= 0) argv.take(at + 1) + listOf("--depth", depth.toString()) + argv.drop(at + 1) else argv
            }
    }

    data class GitPageDecl(
        val dense: Boolean,
        val owner: String,
        val sections: List<GitSectionDecl>,
        val personalGroups: List<GitGroupDecl>,
        val loginWays: List<GitLoginWayDecl>,
        val ways: List<GitWayDecl>,
        val api: GitApiDecl,
        val remoteModes: List<GitRemoteModeDecl>,
        val ops: List<GitOpDecl>,
        val historyMax: Int,
        val nameFamilies: List<String>,
        val publicRepos: List<GitPublicRepoDecl>,
        val terminal: GitTerminalDecl?,
        /** #850 how the ONE status reader is bounded (ui.sync.git.status). */
        val status: GitStatusDecl = GitStatusDecl(),
        /** #850 the page's "Auto pull on open" setting (ui.sync.git.auto_pull). */
        val autoPull: GitAutoPullDecl = GitAutoPullDecl(),
        /** #1254 the declared depth commands (bulk and per repo). */
        val depthActions: List<GitDepthActionDecl> = emptyList(),
        /** #1491 the declared clone modes (ui.sync.git.clone_modes), in render order: the per-repo Clone and Clone All offer exactly these. */
        val cloneModes: List<com.diegonmarcos.cloudlib.gitcore.CloneMode> = emptyList(),
        /** #1255 the Unlisted git folders scan. */
        val unlisted: GitUnlistedDecl = GitUnlistedDecl(),
        /** #1256 the filter row. */
        val filters: List<GitFilterDecl> = emptyList(),
        /** #1315 the top bar (ui.sync.git.top_bar). */
        val topBar: GitTopBarDecl = GitTopBarDecl(),
        /** #1258 Activity Logs (was History): how many entries its view lists. */
        val activityMax: Int = 50,
        /** #1282 where Clone bare puts the mirrors, relative to the store's git folder: <bare_root>/<owner>/<repo>.git. */
        val bareRoot: String = "_bare",
        /** #1288 the per-folder commands (Rename, Archive, Delete) of the Parentless and Unlisted tables. */
        val folderActions: List<GitFolderActionDecl> = emptyList(),
        /** #1288 where Archive moves a folder, relative to the store's git folder. */
        val archiveRoot: String = "z-archived",
        /** #1317 ui.sync.git.stale_origin.auto_fix: a clone whose origin names a retired repository name is re-pointed at the current one on scan. */
        val staleOriginAutoFix: Boolean = true,
        /** #1315 the one inventory pass (ui.sync.git.inventory). */
        val inventory: GitInventoryDecl = GitInventoryDecl(),
        /** #1315 the one executor's bounds (ui.sync.git.jobs). */
        val jobs: GitJobsDecl = GitJobsDecl(),
        /** #1315 how long one store copy (a replace copy through cld.termux, ExecCopier) may take (ui.sync.git.copy_timeout_seconds, bounded by CLOUD_EXEC's own cap). */
        val copyTimeoutSeconds: Int = 110,
        /** #1289 the per-repository menu: groups, items, order, labels (ui.sync.git.repo_menu). */
        val repoMenu: GitRepoMenuDecl = GitRepoMenuDecl(),
        /** #1313 the stale temp pack cleanup (ui.sync.git.stale_pack_cleanup). */
        val stalePackCleanup: GitStalePackDecl = GitStalePackDecl(),
    ) {
        fun folderAction(kind: String): GitFolderActionDecl? = folderActions.firstOrNull { it.kind == kind }
        fun depthActions(scope: String): List<GitDepthActionDecl> = depthActions.filter { it.scope == scope }
        fun depthAction(id: String): GitDepthActionDecl? = depthActions.firstOrNull { it.id == id }
        fun cloneMode(id: String): com.diegonmarcos.cloudlib.gitcore.CloneMode? = cloneModes.firstOrNull { it.id == id }
        fun op(id: String): GitOpDecl? = ops.firstOrNull { it.id == id }
        fun remoteMode(id: String): GitRemoteModeDecl? = remoteModes.firstOrNull { it.id == id }
        /** The declared HTTPS clone URL of [name] under the declared owner — the default for every clone. */
        fun cloneUrl(name: String, mode: String = REMOTE_HTTPS): String =
            remoteMode(mode)?.urlFor(owner, name) ?: ""
        fun webUrl(name: String): String = api.webUrlFor(owner, name)
        /** Whether [name] belongs to one of the declared name families — the rule the public set was derived from. */
        fun inNameFamilies(name: String): Boolean = nameFamilies.any { it.isNotBlank() && name.startsWith(it) }
        val sshWay: GitLoginWayDecl? get() = loginWays.firstOrNull { it.kind == KIND_SSH_KEY }
    }

    /** #604 one of the four classes of volume; VolumesScreen dispatches on [id]. */
    data class VolumeClassDecl(val id: String, val label: String, val icon: String)

    /**
     * #613 one of the two sections the classes regroup into. [classIds] are the
     * ui.volumes.classes ids this section owns; [auth] gives it the Personal-Mounts
     * sign-in affordance (a deep-link to cloud-sa's Profile). A pure reparent — the
     * class rendering and the VolumesScreen dispatch are unchanged.
     */
    data class VolumeSectionDecl(val id: String, val label: String, val auth: Boolean, val classIds: List<String>)

    /**
     * #613 the Personal Mounts sign-in affordance — a LINK to the fleet's auth Profile,
     * never a second auth surface. [pkg] is resolved from the fleet manifest at build time;
     * the launch intent carries [target] as the string extra [extra] (cloud-sa's
     * launcher-shortcut deep-link contract), so the tap lands on Profile ▸ Connect.
     */
    data class PersonalAuthDecl(val pkg: String, val target: String, val extra: String, val icon: String)

    /**
     * #630 THE PLATFORM WALL, declared (build.json::ui.volumes.constellation_owner_only). A fleet
     * app's `/Android/data/<pkg>/` tree is readable ONLY by that app since API [blockedSinceSdk]:
     * MANAGE_EXTERNAL_STORAGE carves it out and Android 13+ closes the SAF route too. [openable]
     * is therefore false and the row must render NO Open affordance onto [path]; [handoff] says it
     * offers a launch of the owning app instead, which is the one process that can read it.
     */
    data class OwnerOnlyDecl(val path: String, val openable: Boolean, val blockedSinceSdk: Int, val handoff: Boolean)

    /**
     * #604/#613/#630 the Volumes tab: the four classes, the two sections that regroup them
     * (#613), the host kinds Cloud-Machines groups by, the data/drive-remotes.json types
     * the S3 class owns, where one fleet app's folder lives inside the shared store
     * ([constellationPath], a RELATIVE leg carrying a `<package>` placeholder — #630), the
     * owner-only tree that can never be opened, and Personal Volumes' auth deep-link.
     */
    data class VolumesDecl(
        val classes: List<VolumeClassDecl>,
        val sections: List<VolumeSectionDecl>,
        val machineKinds: List<String>,
        val s3RemoteTypes: Set<String>,
        val constellationPath: String,
        val personalAuth: PersonalAuthDecl?,
        val ownerOnly: OwnerOnlyDecl? = null,
    ) {
        /** [packageName]'s folder inside the shared store — a relative leg SharedStore resolves. */
        fun constellationPathOf(packageName: String): String =
            if (constellationPath.isBlank() || packageName.isBlank()) "" else constellationPath.replace("<package>", packageName)

        /** The declared owner-only tree of [packageName] — stated, never offered as openable. */
        fun ownerOnlyPathOf(packageName: String): String =
            ownerOnly?.path?.takeIf { it.isNotBlank() && packageName.isNotBlank() }?.replace("<package>", packageName).orEmpty()
        /** The class ids no section claims — VolumesScreen renders them under 'Others' (#292: never lose a row). */
        fun unsectioned(): List<VolumeClassDecl> {
            val claimed = sections.flatMap { it.classIds }.toSet()
            return classes.filter { it.id !in claimed }
        }
    }

    /** #1537 one volume source of Manage (ui.manage); [kind] is what manage/VolumeSources dispatches on. */
    data class ManageSource(val id: String, val label: String, val icon: String, val kind: String, val remote: String = "", val fleet: String = "", val relative: String = "")
    data class ManageDecl(val local: List<ManageSource>, val cloud: List<ManageSource>, val switcher: List<String>, val defaultVolume: String)
    /** #1537 an old route and where it lands now. An empty [page] matches the whole old tab. */
    data class LegacyRoute(val tab: String, val page: String, val toTab: String, val toPage: String)

    /** #604 a fleet app Volumes ▸ Cloud-Constellation lists; the package is resolved at build time. */
    data class ConstellationAppDecl(val label: String, val icon: String, val packageName: String, val fleet: String = "")

    /**
     * #604 a folder↔folder sync rule. [direction] is upload | download | bidirectional;
     * [scheduleMinutes] is declarative only (build.json::_doc_sync_rules says why).
     */
    data class SyncRuleDecl(
        val id: String,
        val localPath: String,
        val remoteName: String,
        val remotePath: String,
        val direction: String,
        val scheduleMinutes: Int?,
        val enabled: Boolean,
        val notes: String,
    ) {
        /** `remote:path` — the rclone side of the rule, composed once. */
        val remoteLeg: String get() = "$remoteName:$remotePath"
    }

    /** #603 a section header of the Files navigator; [PlaceDecl.section] names one of these. */
    data class SectionDecl(val id: String, val label: String, val icon: String)

    /** kind: shared_root | external_root | public_dir (dir = Environment.DIRECTORY_<dir>) | external_path (path relative to shared storage) | terminal_home (the terminal's $HOME) | store_path (path relative to the shared store). */
    data class PlaceDecl(val id: String, val label: String, val icon: String, val kind: String, val section: String, val dir: String, val path: String, val hero: Boolean)

    data class FilterDecl(val id: String, val label: String, val icon: String, val mime: String?, val mimePrefix: String?, val extensions: Set<String>) {
        /** Folders always pass so the tree stays walkable; `all` (no rule) passes everything. */
        fun matches(isDirectory: Boolean, mimeType: String, extension: String): Boolean {
            if (isDirectory) return mime == null || mime == "inode/directory" || (mimePrefix == null && extensions.isEmpty())
            if (mime == null && mimePrefix == null && extensions.isEmpty()) return true
            if (mime != null && mimeType == mime) return true
            if (mimePrefix != null && mimeType.startsWith(mimePrefix)) return true
            return extension.lowercase() in extensions
        }
    }

    data class FilesDecl(
        val sections: List<SectionDecl>,
        val places: List<PlaceDecl>,
        val sortKeys: List<String>,
        val defaultSort: String,
        val filters: List<FilterDecl>,
        val archiveExtensions: Set<String>,
        val textExtensions: Set<String>,
        val defaultDualPane: Boolean,
        val tabsPerPaneMax: Int,
        /** #875 the row/selection actions the screen offers, declared (label, icon, snack) instead of typed into Kotlin. */
        val rowActions: List<RowActionDecl> = emptyList(),
        /** The explorer's inline search: scopes, default chip, bounds (ui.files.search). */
        val search: FilesSearchDecl = FilesSearchDecl(),
        /** #1680 the grouped Explorer menus (ui.files.menus): "item" and "selection", drawn by ONE component. */
        val menus: Map<String, List<com.diegonmarcos.clouddrive.files.FileMenu.MenuGroupDecl>> = emptyMap(),
    ) {
        fun rowAction(id: String): RowActionDecl? = rowActions.firstOrNull { it.id == id }
        fun menu(id: String): List<com.diegonmarcos.clouddrive.files.FileMenu.MenuGroupDecl> = menus[id].orEmpty()
    }

    /** One scope chip. [fromRoot] = walk from the storage root, else from the current folder; [recursive] false = filter the listing in place. */
    data class SearchScopeDecl(val id: String, val label: String, val recursive: Boolean, val fromRoot: Boolean, val foldersOnly: Boolean)

    data class FilesSearchDecl(
        val scopes: List<SearchScopeDecl> = emptyList(),
        val defaultScope: String = "",
        val heightDp: Int = 40,
        val debounceMs: Long = 250,
        val resultCeiling: Int = 300,
        val scanCeiling: Int = 50_000,
        val depthCeiling: Int = 24,
        val placeholder: String = "Search",
    ) {
        fun scope(id: String): SearchScopeDecl? = scopes.firstOrNull { it.id == id }
        /** The declared default when it names a scope, else the first scope. */
        val initialScope: SearchScopeDecl? get() = scope(defaultScope) ?: scopes.firstOrNull()
    }

    /** #875 [snack] is the confirmation shown after the action; it never carries the value acted on. */
    data class RowActionDecl(val id: String, val label: String, val icon: String, val snack: String)

    /** #603 [routeTab]/[routePage] non-blank ⇒ the tile stays in the app and selects that declared tab and sub-page. */
    data class AppTileDecl(val label: String, val icon: String, val packageName: String, val fallbackUrl: String, val routeTab: String, val routePage: String)
    /** [machine] is #604's host class — vm | pc | phone, or `container` (the default) for the container mesh. */
    data class ConnectionDecl(val name: String, val kind: String, val endpoint: String, val auth: String, val vm: String, val status: String, val scope: String, val notes: String, val reason: String, val uri: String, val machine: String)
    data class GitInstanceDecl(val name: String, val kind: String, val org: String, val host: String, val port: Int?, val reachable: Boolean, val reach: String)
    /** [renamedFrom] = the names this repository was cloned under before an upstream rename; the store moves those clones to [name]. */
    data class GitRepoDecl(val name: String, val label: String, val githubOwner: String, val giteaOwner: String, val private: Boolean, val seed: Boolean, val notes: String, val renamedFrom: List<String> = emptyList())
    data class GitFamilyDecl(val instances: List<GitInstanceDecl>, val repos: List<GitRepoDecl>) {
        val upstream: GitInstanceDecl? get() = instances.firstOrNull { it.kind == "upstream" && it.host.isNotBlank() }
        /** `https://<upstream host>/<github_owner>/<name>.git` — the #575 composition, once. */
        fun cloneUrl(repo: GitRepoDecl): String? = upstream?.let { "https://${it.host}/${repo.githubOwner}/${repo.name}.git" }
        /** old clone name -> current name, from every repository's declared `renamed_from`. */
        val renames: Map<String, String> get() = repos.flatMap { r -> r.renamedFrom.map { it to r.name } }.toMap()
    }
    /** #1073 one entry of the repo registry (_git-repos-master/repos.json) as baked in REPOS_SOT_B64. [github] is the GitHub repository name; [pending] = not created yet, so nothing may clone or list it. */
    data class RepoSotEntry(val name: String, val github: String, val private: Boolean, val pending: Boolean, val renamedFrom: List<String> = emptyList(), val lfs: Boolean = false, /** #1256 a hub repository (the registry entry carries `parent_hub`). */ val hub: Boolean = false) {
        /** The registry's naming convention for a retired repository (z-archived_*): hidden from the table unless the owner opens the Archived group. */
        val archived: Boolean get() = github.startsWith("z-archived") || name.startsWith("z-archived")
    }
    /** The registry, the ONE list of repositories; the Sync > Git family is held to it. */
    data class RepoSotDecl(val repos: List<RepoSotEntry>, /** the registry's own rename records, old -> new (one hop each) */ val renameRecords: Map<String, String> = emptyMap(), /** #1317 the index repo itself (registry `self`): listed for readers, never a repos[] row. */ val self: RepoSotEntry? = null) {
        /** The registry's repositories plus the index repo itself: what "listed" means for the Unlisted and Parentless folders. */
        val listed: List<RepoSotEntry> get() = repos + listOfNotNull(self)
        /** Every historical name -> the current github name (entries' renamed_from, then the records, chains followed). */
        val renameMap: Map<String, String> by lazy {
            val direct = LinkedHashMap<String, String>()
            renameRecords.forEach { (o, n) -> direct[o] = n }
            listed.forEach { r -> r.renamedFrom.forEach { o -> if (o != r.github) direct[o] = r.github } }
            direct.keys.associateWith { k -> var cur = direct[k]!!; var hops = 0; while (hops++ < 10) cur = direct[cur] ?: break; cur }
        }
        /** The current name of a clone folder or repository name: itself when never renamed. */
        fun canonical(name: String): String = renameMap[name] ?: name
        fun isArchived(name: String): Boolean = byGithub(canonical(name))?.archived ?: name.startsWith("z-archived")
        fun byGithub(github: String): RepoSotEntry? = repos.firstOrNull { it.github == github }
        /** #1238 true = the registry declares `lfs: true` for it, false = listed and normal, null = the registry does not list it (unknown, never guessed). */
        fun lfsOf(github: String): Boolean? = byGithub(github)?.lfs
        /** #1256 true = a hub, false = a member, null = not in the registry. */
        fun hubOf(github: String): Boolean? = byGithub(canonical(github))?.hub
        /** true only for a repository the registry lists AND has created. */
        fun cloneable(github: String): Boolean = byGithub(github)?.let { !it.pending } ?: false
    }
    data class MirrorJobDecl(val name: String, val source: String, val destination: String, val delete: Boolean, val notes: String)
    data class RemoteDecl(val name: String, val type: String, val purpose: String, val endpoint: String, val host: String, val auth: String, val status: String, val reason: String, val notes: String, val declaredToPhone: Boolean)
    data class RcloneJobDecl(val name: String, val kind: String, val source: String, val destination: String, val schedule: String, val notes: String)

    // ── the baked declarations, decoded once ───────────────────────────────

    /** THE navigation declaration (#868): ui.bottom_nav + ui.sections + ui.default_section. */
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }
    val tabs: List<TabDecl> get() = tabsOf(nav)
    val defaultTab: String get() = nav.default()?.id.orEmpty()
    /** The first row (ui.top_bar): the round Settings button and its Profile / Configs dropdown; null = no bar. */
    val topBar: TopBarDecl? by lazy { TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64) }
    val webserver: WebserverDecl? by lazy { parseWebserver(decode(BuildConfig.UI_WEBSERVER_B64)) }
    val configs: ConfigsDecl by lazy { ConfigsDecl(pagesOf(nav, "configs")) }
    /** #1333 Configs > General: the anchor row and the Perms list (ui.general), parsed by configs/GeneralDecl. */
    val general: com.diegonmarcos.clouddrive.configs.GeneralDecl.General by lazy { com.diegonmarcos.clouddrive.configs.GeneralDecl.parse(decode(BuildConfig.UI_GENERAL_B64)) }
    /** #1149 the former Sync pages are bottom-nav sections now: [SYNC_SECTIONS], in nav order. */
    val sync: SyncDecl by lazy { parseSync(decode(BuildConfig.UI_SYNC_B64), nav.sections.filter { it.id in SYNC_SECTIONS }.map { PageDecl(it.id, it.label, it.icon) }) }
    /** #1149 Git > Cloud's public list (ui.sync.cloud_list) and Git > Me's extra hosts (ui.sync.git_hosts). */
    val cloudList: GitHostsDecl.CloudListDecl by lazy { GitHostsDecl.parseCloud(decode(BuildConfig.UI_SYNC_B64)) }
    val gitHosts: List<GitHostsDecl.GitHostDecl> by lazy { GitHostsDecl.parseHosts(decode(BuildConfig.UI_SYNC_B64)) }
    /** #1679 ui.sync.git.auto_cloned_cleanup: the folders the retired seed cloned by itself (More ▸ Remove auto-cloned copies). */
    val autoClonedCleanup: com.diegonmarcos.clouddrive.sync.AutoClonedCleanup.Decl by lazy { runCatching { com.diegonmarcos.clouddrive.sync.AutoClonedCleanup.parse(decode(BuildConfig.UI_SYNC_B64)) }.getOrDefault(com.diegonmarcos.clouddrive.sync.AutoClonedCleanup.Decl()) }
    /** #1216 the provider lines of Mounts > FUSE and Configs > Mounts (ui.sync.mounts.providers), in declared order. */
    val mountProviders: List<MountProviderDecl> by lazy { parseMountProviders(decode(BuildConfig.UI_SYNC_B64)) }
    /** #1216 the connection kinds Configs > Fsync lists (ui.sync.config_fsync.connection_kinds). */
    val fsyncConnectionKinds: List<String> by lazy { parseFsyncKinds(decode(BuildConfig.UI_SYNC_B64)) }
    /** The Provider column's declared host -> provider map (ui.sync.providers). */
    val providers: GitHostsDecl.ProvidersDecl by lazy { GitHostsDecl.parseProviders(decode(BuildConfig.UI_SYNC_B64)) }
    /** #1149 every section the shell can show: the bottom-nav ones plus the ones the top bar opens (AI, Configs). */
    fun routable(id: String): Boolean = nav.section(id) != null
    val files: FilesDecl by lazy { parseFiles(decode(BuildConfig.UI_FILES_B64)) }
    /** #1537 Manage's volume sources (ui.manage) and the places of the deleted Home's routes (ui.legacy_routes). */
    val manage: ManageDecl by lazy { parseManage(decode(BuildConfig.UI_MANAGE_B64)) }
    val legacyRoutes: List<LegacyRoute> by lazy { parseLegacyRoutes(decode(BuildConfig.UI_LEGACY_B64)) }
    /** Where a (tab, page) route lands now: an old Home id goes to its new place (ui.legacy_routes), anything else is unchanged. A route naming a page matches that page first, then the whole old tab. */
    fun resolveRoute(tab: String, page: String, routes: List<LegacyRoute> = legacyRoutes): Pair<String, String> {
        val hit = routes.firstOrNull { it.tab == tab && it.page.isNotBlank() && it.page == page } ?: routes.firstOrNull { it.tab == tab && it.page.isBlank() }
        return if (hit == null) tab to page else hit.toTab to hit.toPage
    }
    val volumes: VolumesDecl by lazy { parseVolumes(decode(BuildConfig.UI_VOLUMES_B64), classesOf(nav)) }
    val constellation: List<ConstellationAppDecl> by lazy { parseConstellation(decode(BuildConfig.UI_CONSTELLATION_B64)) }
    val syncRules: List<SyncRuleDecl> by lazy { parseSyncRules(decode(BuildConfig.SYNC_RULES_B64)) }
    /** #1084 the Unison folder pairs (data/drive-sync-rules.json `unison`), parsed and validated by libs:rclone; a pair that breaks a rule is a sentence in `problems`, never a half-valid run. */
    /** #1173 Unison's shipped POLICY (defaults, shared ignore rules; no pair) as JSON text: the user's pairs are laid over it by storage/UnisonPairs. */
    val unisonPolicy: String by lazy { decode(BuildConfig.UNISON_B64) }
    val unison: com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig.Declared by lazy {
        runCatching { com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig.parse(decode(BuildConfig.UNISON_B64), runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrNull() ?: com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig.DEFAULT_STORAGE_ROOT) }
            .getOrElse { com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig.Declared(emptyList(), listOf("the unison declaration is not valid JSON: ${it.message}")) }
    }
    val iconDefault: String get() = BuildConfig.UI_ICON_DEFAULT
    val apps: List<AppTileDecl> by lazy { parseApps(decode(BuildConfig.UI_APPS_B64)) }
    val connections: List<ConnectionDecl> by lazy { parseConnections(decode(BuildConfig.CONNECTIONS_B64)) }
    /** #1073 the repo registry, baked from build.json::storage.repos_source. */
    private val bakedSot: RepoSotDecl by lazy { parseRepoSot(decode(BuildConfig.REPOS_SOT_B64)) }
    /** A newer registry fetched at runtime (RepoSotLive), replacing the baked snapshot for everything that is read live. */
    @Volatile var liveSot: RepoSotDecl? = null
    val repoSot: RepoSotDecl get() = liveSot ?: bakedSot
    /** The Sync > Git family, minus any repository the registry does not list or has not created (pending_creation). */
    val gitFamily: GitFamilyDecl by lazy { restrictToRegistry(parseGitFamily(decode(BuildConfig.GIT_REPOS_B64)), repoSot) }
    val mirrorJobs: List<MirrorJobDecl> by lazy { parseMirrorJobs(decode(BuildConfig.MIRROR_JOBS_B64)) }
    val remotes: List<RemoteDecl> by lazy { parseRemotes(decode(BuildConfig.RCLONE_REMOTES_B64)) }
    val rcloneJobs: List<RcloneJobDecl> by lazy { parseRcloneJobs(decode(BuildConfig.RCLONE_JOBS_B64)) }
    /** #587 build.json::auth.applies → artifact section id → the key inside it that a drive sign-in applies. */
    val authApplies: Map<String, String> by lazy { parseAuthApplies(decode(BuildConfig.AUTH_APPLIES_B64)) }
    /** #629 the same block's `credential_id`: the id the VAULT-delivered credential is held under. */
    val authCredentialIds: Map<String, String> by lazy { parseAuthCredentialIds(decode(BuildConfig.AUTH_APPLIES_B64)) }

    /** build.json::mount_keeper (the mount lifetime policy), decoded once; parsed by mounts/MountPolicy. */
    val mountKeeperJson: String by lazy { decode(BuildConfig.MOUNT_KEEPER_B64) }

    fun decode(b64: String): String = if (b64.isBlank()) "" else runCatching { String(Base64.getDecoder().decode(b64), Charsets.UTF_8) }.getOrDefault("")

    // ── parsers (pure; JVM-tested) ─────────────────────────────────────────

    fun parseWebserver(text: String): WebserverDecl? {
        val o = element(text) as? JsonObject ?: return null
        val pkg = o.str("package")
        return if (pkg.isBlank()) null else WebserverDecl(pkg, o.str("activity"), o.str("fallback_url"))
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun element(text: String): JsonElement? = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
    private fun JsonObject.str(key: String, default: String = ""): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: default
    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
    private fun JsonObject.boolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    private fun objects(e: JsonElement?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** The island's tabs, in `ui.bottom_nav` order — the order is load-bearing, it IS the nav order. */
    fun tabsOf(nav: NavDecl): List<TabDecl> = nav.bottomSections().map { TabDecl(it.id, it.label, it.icon) }

    /** A section's declared pages — the strip PageTabs draws over it. */
    fun pagesOf(nav: NavDecl, sectionId: String): List<PageDecl> =
        nav.section(sectionId)?.pages.orEmpty().map { p: NavPage -> PageDecl(p.id, p.label, p.icon) }

    /** A section's declared pages as the lib's [NavPage]s — what PageTabs draws. */
    fun navPages(sectionId: String): List<NavPage> = nav.section(sectionId)?.pages.orEmpty()

    /** The four volume classes: Explorer ▸ Cloud's sub-pages (ui.sections[explorer].pages[cloud].pages; Cloud is the former Volumes tab). */
    fun classesOf(nav: NavDecl): List<VolumeClassDecl> =
        nav.section(SECTION_EXPLORER)?.pages.orEmpty().firstOrNull { it.id == PAGE_CLOUD }?.pages.orEmpty().map { VolumeClassDecl(it.id, it.label, it.icon) }

    const val SECTION_EXPLORER = "explorer"
    const val PAGE_LOCAL = "local"
    const val PAGE_CLOUD = "cloud"
    /** #1537 Manage: a bottom-nav page after Projects (Home is deleted); its pages are Overview | Local-Apps | Local-Data. */
    const val SECTION_MANAGE = "manage"
    const val PAGE_OVERVIEW = "overview"
    const val PAGE_LOCAL_APPS = "local_apps"
    const val PAGE_LOCAL_DATA = "local_data"
    const val SECTION_EDITOR = "editor"
    /** #1491 Projects: a bottom-nav page right after Explorer (no sub-pages). */
    const val SECTION_PROJECTS = "projects"
    fun managePages(): List<NavPage> = navPages(SECTION_MANAGE)
    const val SECTION_AI = "ai"
    const val SECTION_CONFIGS = "configs"
    /** #1149 the former Sync pages, now bottom-nav sections of their own. */
    const val SECTION_GIT = "git"
    const val SECTION_FSYNC = "fsync"
    const val SECTION_MOUNTS = "mounts"
    val SYNC_SECTIONS = listOf(SECTION_GIT, SECTION_FSYNC, SECTION_MOUNTS)
    const val PAGE_ME = "me"

    /** #609 the Sync destinations: Git/Rsync/Mounts ([pages]; #1149 their own bottom-nav sections). #608 carries the Git page too. */
    fun parseSync(text: String, pages: List<PageDecl> = emptyList()): SyncDecl {
        val o = element(text) as? JsonObject ?: return SyncDecl(pages, emptyList(), EMPTY_GIT_PAGE)
        val periods = (o["git_periods_minutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it >= 15 } ?: emptyList()
        return SyncDecl(pages, periods, parseGitPage(o["git"]))
    }

    fun parseMountProviders(text: String): List<MountProviderDecl> {
        val o = element(text) as? JsonObject ?: return emptyList()
        val m = o["mounts"] as? JsonObject ?: return emptyList()
        return objects(m["providers"]).mapNotNull { p ->
            val id = p.str("id"); if (id.isBlank()) return@mapNotNull null
            val others = p.bool("others")
            if (!others && p.str("remote").isBlank()) return@mapNotNull null
            MountProviderDecl(id, p.str("label", id), p.str("icon"), p.str("remote"), p.str("rclone_type"), p.str("rclone_provider"), p.str("endpoint"), p.str("path"), p.bool("read_only"), p.str("storage_class"), others)
        }
    }

    fun parseFsyncKinds(text: String): List<String> {
        val o = element(text) as? JsonObject ?: return emptyList()
        return ((o["config_fsync"] as? JsonObject)?.strings("connection_kinds")) ?: emptyList()
    }

    /** #608 the Git page's own declaration. A missing block yields the EMPTY page — the
     *  screen then renders its honest "nothing declared" state instead of inventing a verb. */
    fun parseGitPage(e: JsonElement?): GitPageDecl {
        val o = e as? JsonObject ?: return EMPTY_GIT_PAGE
        val api = (o["api"] as? JsonObject) ?: JsonObject(emptyMap())
        val term = o["terminal"] as? JsonObject
        val st = o["status"] as? JsonObject ?: JsonObject(emptyMap())
        val ap = o["auto_pull"] as? JsonObject ?: JsonObject(emptyMap())
        return GitPageDecl(
            status = GitStatusDecl(
                timeoutSeconds = (st.int("timeout_seconds") ?: 20).coerceIn(1, 600),
                concurrency = (((st["modes"] as? JsonObject)?.get("light") as? JsonObject)?.int("workers") ?: st.int("concurrency") ?: 1).coerceIn(1, 2),
                logWindow = (((st["modes"] as? JsonObject)?.get("light") as? JsonObject)?.int("log_window_commits") ?: 100).coerceIn(10, 1000),
                ttlSeconds = (st.int("ttl_seconds") ?: 60).coerceAtLeast(0),
                maxAutoFiles = (st.int("max_auto_files") ?: 3000).coerceAtLeast(1),
                maxScanMs = (st.int("max_scan_ms") ?: 1500).toLong().coerceAtLeast(1),
                icons = (st["icons"] as? JsonObject)?.mapNotNull { (icon, v) ->
                    (v as? JsonObject)?.let { m -> icon to m.mapNotNull { (k, l) -> (l as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it } }.toMap() }
                }?.toMap() ?: emptyMap(),
            ),
            autoPull = GitAutoPullDecl(ap.bool("default_on", false), ap.bool("require_unmetered_network", true)),
            dense = o.bool("dense", true),
            owner = o.str("owner"),
            sections = objects(o["sections"]).mapNotNull { s ->
                val id = s.str("id"); if (id.isBlank()) null else GitSectionDecl(id, s.str("label", id), s.str("icon"), s.bool("login_required"))
            },
            personalGroups = objects(o["personal_groups"]).mapNotNull { g ->
                val id = g.str("id"); if (id.isBlank()) null else GitGroupDecl(id, g.str("label", id), g.str("icon"))
            },
            loginWays = objects(o["login_ways"]).mapNotNull { w ->
                val id = w.str("id"); if (id.isBlank()) null
                else GitLoginWayDecl(id, w.str("label", id), w.str("icon"), w.str("kind"), w.str("provider"), w.str("scope"), w.bool("lists"))
            },
            // #684 a way with no rung has no mechanism and is dropped rather than drawn as a
            // placeholder card (#648: a surface exists when its mechanism exists).
            ways = objects(o["ways"]).mapNotNull { w ->
                val id = w.str("id"); val rung = w.str("rung")
                if (id.isBlank() || rung.isBlank()) null else GitWayDecl(id, rung, w.str("label", id), w.str("icon"))
            },
            api = GitApiDecl(api.str("base_url"), api.str("repos_path"), (api.int("max_pages") ?: 1).coerceAtLeast(1), api.str("web_url")),
            remoteModes = objects(o["remote_modes"]).mapNotNull { m ->
                val id = m.str("id"); if (id.isBlank()) null else GitRemoteModeDecl(id, m.str("label", id), m.str("icon"), m.str("url"), m.bool("read_only"))
            },
            ops = objects(o["ops"]).mapNotNull { p ->
                val id = p.str("id"); if (id.isBlank()) null else GitOpDecl(id, p.str("label", id), p.str("icon"), p.bool("destructive"))
            },
            historyMax = (o.int("history_max") ?: 30).coerceAtLeast(1),
            cloneModes = objects(o["clone_modes"]).mapNotNull { m ->
                val id = m.str("id"); if (id.isBlank()) null
                else com.diegonmarcos.cloudlib.gitcore.CloneMode(id, m.str("label", id), (m.int("depth") ?: 0).coerceIn(0, 1000), m.bool("bare"), m.bool("single_branch"), m.str("filter"))
            },
            // #1491 a clone_mode action names one of clone_modes; one naming no declared mode is dropped.
            depthActions = objects(o["depth_actions"]).mapNotNull { a ->
                val id = a.str("id"); val kind = a.str("kind"); val scope = a.str("scope")
                if (id.isBlank() || kind !in DEPTH_KINDS || scope !in DEPTH_SCOPES) null
                else GitDepthActionDecl(id, a.str("label", id), a.str("icon"), kind, scope, (a.int("depth") ?: 1).coerceIn(1, 1000), (a.int("workers") ?: 1).coerceIn(1, 4), a.bool("confirm"), a.str("mode"))
            }.filter { it.kind != DEPTH_CLONE_MODE || objects(o["clone_modes"]).any { m -> m.str("id") == it.mode } },
            unlisted = (o["unlisted"] as? JsonObject)?.let { u ->
                GitUnlistedDecl(u.str("label"), u.strings("roots"), (u.int("max_depth") ?: 2).coerceIn(0, 6), (u.int("max_dirs") ?: 2000).coerceIn(1, 100_000), (u.int("timeout_seconds") ?: 30).coerceIn(1, 600))
            } ?: GitUnlistedDecl(),
            activityMax = ((o["activity_logs"] as? JsonObject)?.int("max_entries") ?: 50).coerceIn(1, 1000),
            bareRoot = o.str("bare_root", "_bare").takeIf { com.diegonmarcos.clouddrive.sync.BareClones.validRoot(it) } ?: "_bare",
            folderActions = objects(o["folder_actions"]).mapNotNull { a ->
                val id = a.str("id"); val kind = a.str("kind")
                if (id.isBlank() || kind !in FOLDER_KINDS) null else GitFolderActionDecl(id, a.str("label", id), a.str("icon"), kind, (a.int("workers") ?: 1).coerceIn(1, 4), a.bool("confirm", true))
            },
            archiveRoot = o.str("archive_root", "z-archived").takeIf { com.diegonmarcos.clouddrive.sync.BareClones.validRoot(it) } ?: "z-archived",
            staleOriginAutoFix = (o["stale_origin"] as? JsonObject)?.bool("auto_fix", true) ?: true,
            inventory = (o["inventory"] as? JsonObject)?.let { i ->
                GitInventoryDecl(
                    i.strings("debris").filter { com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.safeGlob(it) }.ifEmpty { GitInventoryDecl().debris },
                    i.bool("auto_register", true),
                    ((i["cleanup"] as? JsonObject)?.int("min_age_hours") ?: 6).coerceIn(1, 24 * 90),
                    (i["cleanup"] as? JsonObject)?.bool("auto", true) ?: true,
                    (i["labels"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
                )
            } ?: GitInventoryDecl(),
            jobs = GitJobsDecl(((o["jobs"] as? JsonObject)?.int("workers") ?: 2).coerceIn(1, 4)),
            copyTimeoutSeconds = (o.int("copy_timeout_seconds") ?: 110).coerceIn(10, 120),
            repoMenu = parseRepoMenu(o["repo_menu"]),
            stalePackCleanup = (o["stale_pack_cleanup"] as? JsonObject)?.let { c ->
                GitStalePackDecl(
                    enabled = c.bool("enabled", true),
                    minAgeHours = (c.int("min_age_hours") ?: 6).coerceIn(1, 24 * 90),
                    patterns = c.strings("patterns").filter { com.diegonmarcos.cloudlib.gitrepos.StalePackCleaner.safePattern(it) }.ifEmpty { com.diegonmarcos.cloudlib.gitrepos.StalePackCleaner.DEFAULT_PATTERNS },
                )
            } ?: GitStalePackDecl(),
            filters = objects(o["filters"]).mapNotNull { f ->
                val id = f.str("id")
                val opts = objects(f["options"]).mapNotNull { x ->
                    x.str("id").takeIf { it.isNotBlank() }?.let {
                        GitFilterOptionDecl(it, x.str("label", it), x.bool("quick"), x.strings("kinds").map { k -> k.lowercase() },
                            x.boolOrNull("cloned"), x.boolOrNull("lfs"), x.boolOrNull("private"), x.boolOrNull("hub"), x.boolOrNull("archived"), x.str("level"))
                    }
                }
                if (id.isBlank() || opts.isEmpty()) null else GitFilterDecl(id, f.str("label", id), opts, f.str("layout", "chips"), f.str("default", "all").takeIf { d -> opts.any { it.id == d } } ?: opts.first().id)
            },
            topBar = (o["top_bar"] as? JsonObject)?.let { t ->
                GitTopBarDecl(
                    objects(t["groups"]).mapNotNull { g ->
                        val gid = g.str("id"); if (gid.isBlank()) return@mapNotNull null
                        GitTopGroupDecl(gid, g.str("label"), g.str("icon"), objects(g["items"]).mapNotNull { i ->
                            val iid = i.str("id"); val kind = i.str("kind")
                            if (iid.isBlank() || kind !in TOP_KINDS) null else GitTopItemDecl(iid, kind, i.str("label", iid), i.str("hint"), i.str("icon"), i.str("op"), i.str("action"),
                                i.bool("force"), i.str("scope", "filtered"), i.bool("confirm"), i.bool("danger"), i.bool("exclusive", true), i.str("filter"), i.str("option"), i.str("section"))
                        })
                    },
                    t.bool("search", true), t.str("search_placeholder"), t.strings("counters"),
                    (t["labels"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
                    (o["logs"] as? JsonObject)?.let { l ->
                        GitLogsDecl(l.str("title", "Logs"), objects(l["tabs"]).mapNotNull { x -> x.str("id").takeIf { it.isNotBlank() }?.let { it to x.str("label", it) } },
                            (l["labels"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty())
                    } ?: GitLogsDecl(),
                )
            } ?: GitTopBarDecl(),
            nameFamilies = o.strings("name_families"),
            // #642 absent block -> null -> the page says git handoff is not configured rather
            // than firing an intent with an empty action, which resolves to nothing in silence.
            terminal = term?.let { t ->
                GitTerminalDecl(
                    pkg = t.str("package"),
                    action = t.str("action"),
                    permission = t.str("permission"),
                    command = t.str("command"),
                    extraPath = t.str("extra_path"),
                    extraArguments = t.str("extra_arguments"),
                    extraWorkdir = t.str("extra_workdir"),
                    extraBackground = t.str("extra_background"),
                    background = t.bool("background"),
                    ops = ((t["ops"] as? JsonObject)?.mapValues { (_, v) ->
                        (v as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    })?.filterValues { it.isNotEmpty() }.orEmpty(),
                )
            },
            // `repo`/`caption`, not `name`/`label`: build.json::ui.sync.git._doc_public_repos
            // says why (the fleet's app-names guard sweeps name/label/title, and a repository
            // name is not an application name).
            publicRepos = objects(o["public_repos"]).mapNotNull { r ->
                val name = r.str("repo"); if (name.isBlank()) null else GitPublicRepoDecl(name, r.str("caption", name))
            },
        )
    }

    /** #1289 ui.sync.git.repo_menu: a group or item without an id is dropped; minutes are floored at min_minutes (15 = WorkManager's floor). */
    fun parseRepoMenu(e: JsonElement?): GitRepoMenuDecl {
        val o = e as? JsonObject ?: return GitRepoMenuDecl()
        fun i18n(x: JsonObject): Map<String, String> = (x["i18n"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        fun group(g: JsonObject): GitMenuGroupDecl? {
            val id = g.str("id"); if (id.isBlank()) return null
            return GitMenuGroupDecl(
                id, g.str("label", id), i18n(g), g.str("layout") == "lines", g.bool("danger"),
                objects(g["items"]).mapNotNull { x -> x.str("id").takeIf { it.isNotBlank() }?.let { GitMenuItemDecl(it, x.str("label", it), i18n(x), x.str("icon")) } },
                objects(g["subgroups"]).mapNotNull { group(it) },
                g.str("layout") == "row",
            )
        }
        val a = o["autosync"] as? JsonObject ?: JsonObject(emptyMap())
        val floor = (a.int("min_minutes") ?: 15).toLong().coerceAtLeast(15)
        val options = ((a["options_minutes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.toLong() } ?: emptyList()).filter { it >= floor }.distinct()
        val def = (a.int("default_minutes") ?: 15).toLong().coerceAtLeast(floor)
        return GitRepoMenuDecl(
            groups = objects(o["groups"]).mapNotNull { group(it) },
            autoSync = GitAutoSyncDecl(a.bool("default_on", false), def, floor, options.ifEmpty { listOf(def) }),
        )
    }

    fun parseFiles(text: String): FilesDecl {
        val o = element(text) as? JsonObject ?: return FilesDecl(emptyList(), emptyList(), listOf("name"), "name", emptyList(), emptySet(), emptySet(), true, 6)
        val sections = objects(o["sections"]).mapNotNull { s ->
            val id = s.str("id"); if (id.isBlank()) null else SectionDecl(id, s.str("label", id), s.str("icon"))
        }
        val places = objects(o["places"]).mapNotNull { p ->
            val id = p.str("id"); if (id.isBlank()) return@mapNotNull null
            PlaceDecl(id, p.str("label", id), p.str("icon"), p.str("kind"), p.str("section"), p.str("dir"), p.str("path"), p.bool("hero"))
        }
        val filters = objects(o["filters"]).mapNotNull { f ->
            val id = f.str("id"); if (id.isBlank()) return@mapNotNull null
            FilterDecl(id, f.str("label", id), f.str("icon"), f.str("mime").ifBlank { null }, f.str("mime_prefix").ifBlank { null }, f.strings("extensions").map { it.lowercase() }.toSet())
        }
        val sortKeys = o.strings("sort_keys").ifEmpty { listOf("name") }
        return FilesDecl(
            sections = sections,
            places = places,
            sortKeys = sortKeys,
            defaultSort = o.str("default_sort", sortKeys.first()),
            filters = filters,
            archiveExtensions = o.strings("archive_extensions").map { it.lowercase() }.toSet(),
            textExtensions = o.strings("text_extensions").map { it.lowercase() }.toSet(),
            defaultDualPane = o.bool("default_dual_pane", true),
            tabsPerPaneMax = (o.int("tabs_per_pane_max") ?: 6).coerceAtLeast(1),
            rowActions = objects(o["row_actions"]).mapNotNull { a ->
                val id = a.str("id"); if (id.isBlank()) null else RowActionDecl(id, a.str("label", id), a.str("icon"), a.str("snack"))
            },
            search = parseFilesSearch(o["search"] as? JsonObject),
            menus = parseFileMenus(o["menus"] as? JsonObject),
        )
    }

    /** #1680 ui.files.menus → {menu id: groups}. Blank ids are skipped; a malformed block yields no menu, never a crash. */
    fun parseFileMenus(o: JsonObject?): Map<String, List<com.diegonmarcos.clouddrive.files.FileMenu.MenuGroupDecl>> {
        if (o == null) return emptyMap()
        return o.keys.associateWith { key ->
            val arr = o[key] as? kotlinx.serialization.json.JsonArray ?: return@associateWith emptyList()
            objects(arr).mapNotNull { g ->
                val gid = g.str("id"); if (gid.isBlank()) return@mapNotNull null
                com.diegonmarcos.clouddrive.files.FileMenu.MenuGroupDecl(
                    gid, g.str("label", gid), g.bool("git_only", false),
                    objects(g["items"]).mapNotNull { i -> val iid = i.str("id"); if (iid.isBlank()) null else com.diegonmarcos.clouddrive.files.FileMenu.MenuItemDecl(iid, i.str("label", iid)) },
                )
            }
        }
    }

    fun parseFilesSearch(o: JsonObject?): FilesSearchDecl {
        if (o == null) return FilesSearchDecl()
        val scopes = objects(o["scopes"]).mapNotNull { sc ->
            val id = sc.str("id"); if (id.isBlank()) null
            else SearchScopeDecl(id, sc.str("label", id), sc.bool("recursive"), sc.str("from") == "root", sc.bool("folders_only"))
        }
        return FilesSearchDecl(
            scopes = scopes,
            defaultScope = o.str("default_scope"),
            heightDp = (o.int("search_height_dp") ?: 40).coerceAtLeast(24),
            debounceMs = (o.int("debounce_ms") ?: 250).coerceAtLeast(0).toLong(),
            resultCeiling = (o.int("result_ceiling") ?: 300).coerceAtLeast(1),
            scanCeiling = (o.int("scan_ceiling") ?: 50_000).coerceAtLeast(1),
            depthCeiling = (o.int("depth_ceiling") ?: 24).coerceAtLeast(1),
            placeholder = o.str("placeholder", "Search"),
        )
    }

    /** [classes] are ui.sections[volumes].pages (#868). */
    fun parseVolumes(text: String, classes: List<VolumeClassDecl> = emptyList()): VolumesDecl {
        val o = element(text) as? JsonObject ?: return VolumesDecl(classes, emptyList(), emptyList(), emptySet(), "", null, null)
        val sections = objects(o["sections"]).mapNotNull { s ->
            val id = s.str("id"); if (id.isBlank()) null else VolumeSectionDecl(id, s.str("label", id), s.bool("auth"), s.strings("classes"))
        }
        val personalAuth = (o["personal_auth"] as? JsonObject)?.let {
            val pkg = it.str("package"); if (pkg.isBlank()) null else PersonalAuthDecl(pkg, it.str("target"), it.str("extra"), it.str("icon"))
        }
        val ownerOnly = (o["constellation_owner_only"] as? JsonObject)?.let {
            val path = it.str("path"); if (path.isBlank()) null else OwnerOnlyDecl(path, it.bool("openable"), it.int("blocked_since_sdk") ?: 30, it.bool("handoff", true))
        }
        return VolumesDecl(classes, sections, o.strings("machine_kinds"), o.strings("s3_remote_types").toSet(), o.str("constellation_path"), personalAuth, ownerOnly)
    }

    fun parseManage(text: String): ManageDecl {
        val o = element(text) as? JsonObject ?: return ManageDecl(emptyList(), emptyList(), emptyList(), "")
        fun sources(e: JsonElement?) = objects(e).mapNotNull { s ->
            val id = s.str("id"); if (id.isBlank()) null else ManageSource(id, s.str("label", id), s.str("icon"), s.str("kind"), s.str("remote"), s.str("fleet"), s.str("relative"))
        }
        val ov = o["overview"] as? JsonObject
        val ld = o["local_data"] as? JsonObject
        return ManageDecl(sources(ov?.get("local")), sources(ov?.get("cloud")), ld?.strings("switcher").orEmpty(), ld?.str("default_volume").orEmpty())
    }

    fun parseLegacyRoutes(text: String): List<LegacyRoute> = objects(element(text)).mapNotNull { r ->
        val to = r["to"] as? JsonObject ?: return@mapNotNull null
        val tab = r.str("tab"); val toTab = to.str("tab")
        if (tab.isBlank() || toTab.isBlank()) null else LegacyRoute(tab, r.str("page"), toTab, to.str("page"))
    }

    fun parseConstellation(text: String): List<ConstellationAppDecl> = objects(element(text)).mapNotNull { a ->
        val label = a.str("label"); if (label.isBlank()) return@mapNotNull null
        ConstellationAppDecl(label, a.str("icon"), a.str("package"), a.str("fleet"))
    }

    fun parseSyncRules(text: String): List<SyncRuleDecl> = objects(element(text)).mapNotNull { r ->
        val id = r.str("id"); if (id.isBlank()) return@mapNotNull null
        SyncRuleDecl(id, r.str("local_path"), r.str("remote_name"), r.str("remote_path"), r.str("direction", DIRECTION_BIDIRECTIONAL), r.int("schedule_minutes"), r.bool("enabled", true), r.str("notes"))
    }

    fun parseApps(text: String): List<AppTileDecl> = objects(element(text)).mapNotNull { a ->
        val label = a.str("label"); if (label.isBlank()) return@mapNotNull null
        val route = a["route"] as? JsonObject
        AppTileDecl(label, a.str("icon"), a.str("package"), a.str("fallback_url"), route?.str("tab") ?: "", route?.str("page") ?: "")
    }

    fun parseConnections(text: String): List<ConnectionDecl> = objects(element(text)).mapNotNull { c ->
        val name = c.str("name"); if (name.isBlank()) return@mapNotNull null
        ConnectionDecl(name, c.str("kind"), c.str("endpoint"), c.str("auth"), c.str("vm"), c.str("status"), c.str("scope"), c.str("notes"), c.str("reason"), c.str("uri"), c.str("machine", MACHINE_CONTAINER))
    }

    /** The baked slim array, or the full registry object (`repos` + `renames`) a live fetch returns. */
    fun parseRepoSot(text: String): RepoSotDecl {
        val e = element(text)
        val o = e as? JsonObject
        val list = if (o != null) objects(o["repos"]) else objects(e)
        val repos = list.mapNotNull { r ->
            val name = r.str("name"); if (name.isBlank()) return@mapNotNull null
            val github = r.str("github").ifBlank { r.str("url").substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifBlank { name } }
            RepoSotEntry(name, github, r.bool("private"), r.bool("pending") || r.str("status") == "pending_creation", r.strings("renamed_from"), r.bool("lfs"), r.bool("is_hub") || r.str("parent_hub").isNotBlank())
        }
        val records = o?.let { objects(it["renames"]) }.orEmpty().mapNotNull { x -> x.str("from").takeIf { it.isNotBlank() }?.let { f -> x.str("to").takeIf { it.isNotBlank() }?.let { t -> f to t } } }.toMap()
        val self = (o?.get("self") as? JsonObject)?.let { x ->
            val name = x.str("name"); if (name.isBlank()) null
            else RepoSotEntry(name, x.str("github").ifBlank { x.str("url").substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifBlank { name } }, x.bool("private"), false, x.strings("renamed_from"))
        }
        return RepoSotDecl(repos, records, self)
    }

    /** #1073 a family repository survives only when the registry lists it and it exists: a pending_creation repo is never offered for clone or seed. */
    fun restrictToRegistry(family: GitFamilyDecl, sot: RepoSotDecl): GitFamilyDecl =
        family.copy(repos = family.repos.filter { sot.cloneable(it.name) })

    fun parseGitFamily(text: String): GitFamilyDecl {
        val o = element(text) as? JsonObject ?: return GitFamilyDecl(emptyList(), emptyList())
        val instances = objects(o["instances"]).mapNotNull { i ->
            val name = i.str("name"); if (name.isBlank()) return@mapNotNull null
            GitInstanceDecl(name, i.str("kind"), i.str("org"), i.str("host"), i.int("port"), i.bool("reachable"), i.str("reach"))
        }
        val repos = objects(o["repos"]).mapNotNull { r ->
            val name = r.str("name"); if (name.isBlank()) return@mapNotNull null
            GitRepoDecl(name, r.str("label", name), r.str("github_owner"), r.str("gitea_owner"), r.bool("private"), r.bool("seed"), r.str("notes"), r.strings("renamed_from"))
        }
        return GitFamilyDecl(instances, repos)
    }

    fun parseMirrorJobs(text: String): List<MirrorJobDecl> = objects(element(text)).mapNotNull { j ->
        val name = j.str("name"); if (name.isBlank()) return@mapNotNull null
        MirrorJobDecl(name, j.str("source"), j.str("destination"), j.bool("delete"), j.str("notes"))
    }

    fun parseRemotes(text: String): List<RemoteDecl> = objects(element(text)).mapNotNull { r ->
        val name = r.str("name"); if (name.isBlank()) return@mapNotNull null
        RemoteDecl(name, r.str("type"), r.str("purpose"), r.str("endpoint"), r.str("host"), r.str("auth"), r.str("status"), r.str("reason"), r.str("notes"), r["rclone"] is JsonObject)
    }

    fun parseRcloneJobs(text: String): List<RcloneJobDecl> = objects(element(text)).mapNotNull { j ->
        val name = j.str("name"); if (name.isBlank()) return@mapNotNull null
        RcloneJobDecl(name, j.str("kind"), j.str("source"), j.str("destination"), j.str("schedule"), j.str("notes"))
    }

    /**
     * #629 section id -> `credential_id`, declared beside `key` in the SAME block so the id the
     * vault-delivered credential lives under is never typed into Kotlin. A section with no
     * credential_id simply has none, and the caller then writes only the per-repository secrets.
     */
    fun parseAuthCredentialIds(text: String): Map<String, String> {
        val o = element(text) as? JsonObject ?: return emptyMap()
        return o.entries.asSequence().filterNot { it.key.startsWith("_") }
            .mapNotNull { (id, v) -> (v as? JsonObject)?.str("credential_id")?.takeIf { it.isNotBlank() }?.let { id to it } }
            .toMap()
    }

    fun parseAuthApplies(text: String): Map<String, String> {
        val o = element(text) as? JsonObject ?: return emptyMap()
        return o.entries.asSequence().filterNot { it.key.startsWith("_") }
            .mapNotNull { (id, v) -> (v as? JsonObject)?.str("key")?.takeIf { it.isNotBlank() }?.let { id to it } }
            .toMap()
    }

    /** Every icon name the declarations use — what test-drive-shell.sh and DeclarationsTest hold IconCatalog to. */
    fun iconNames(tabs: List<TabDecl>, configs: ConfigsDecl, files: FilesDecl, volumes: VolumesDecl? = null, sync: SyncDecl? = null): Set<String> =
        (tabs.map { it.icon } + configs.pages.map { it.icon } + files.sections.map { it.icon } + files.places.map { it.icon } + files.filters.map { it.icon } + files.rowActions.map { it.icon } +
            (volumes?.classes?.map { it.icon } ?: emptyList()) + (sync?.pages?.map { it.icon } ?: emptyList()) +
            (sync?.git?.let { g -> g.sections.map { it.icon } + g.personalGroups.map { it.icon } + g.loginWays.map { it.icon } + g.ways.map { it.icon } + g.remoteModes.map { it.icon } + g.ops.map { it.icon } } ?: emptyList())
            ).filter { it.isNotBlank() }.toSet()

    /** #608 the page with nothing declared: every list empty, so the screen says so. */
    // #642 the trailing null is `terminal`: the empty page hands nothing to cloud-terminal.
    // Stated rather than defaulted — a default would let a real page lose its git handoff
    // silently, and this is the one place where having none is the correct answer.
    val EMPTY_GIT_PAGE = GitPageDecl(true, "", emptyList(), emptyList(), emptyList(), emptyList(), GitApiDecl("", "", 1, ""), emptyList(), emptyList(), 30, emptyList(), emptyList(), null)

    /** #608 the login kinds the Git page dispatches on — the ONLY provider vocabulary in Kotlin.
     *  #641 `webauth` is gone with the GitHub device grant it drove. */
    const val KIND_SSH_KEY = "ssh_key"

    /** #642 the terminal-handoff operation ids (build.json::ui.sync.git.terminal.ops keys).
     *  Clone is not one of ui.sync.git.ops: those are the verbs of a repository ALREADY on
     *  the device, and this is the one that puts it there. */
    const val GIT_OP_CLONE = "clone"

    /** #608 the declared remote modes, by id. */
    const val REMOTE_HTTPS = "https"
    const val REMOTE_SSH = "ssh"
    /** #1254 the depth command kinds and scopes GitReposScreen dispatches on (test-drive-git-depth.sh diffs both ways). */
    const val DEPTH_KEEP = "keep_depth"
    const val DEPTH_SCOPE_ALL = "all"
    const val DEPTH_SCOPE_REPO = "repo"
    /** #1491 one declared clone mode (ui.sync.git.clone_modes) on one scope: Full, Full Depth 3, Bare Main Only, Bare Blobless, Bare Treeless. */
    const val DEPTH_CLONE_MODE = "clone_mode"

    val DEPTH_KINDS = setOf(DEPTH_CLONE_MODE, DEPTH_KEEP)
    val DEPTH_SCOPES = setOf(DEPTH_SCOPE_ALL, DEPTH_SCOPE_REPO)
    /** #1288 the per-folder command kinds the Parentless / Unlisted tables dispatch on (test-drive-git-folder-actions.sh diffs both ways). */
    const val FOLDER_RENAME = "rename_to_repo"
    const val FOLDER_ARCHIVE = "archive"
    const val FOLDER_DELETE = "delete_local"
    /** #1317 Fix origin: `remote set-url origin` swaps a retired repository name (a renamed_from) for the current one. */
    const val FOLDER_REPOINT = "repoint_origin"
    val FOLDER_KINDS = setOf(FOLDER_RENAME, FOLDER_ARCHIVE, FOLDER_DELETE, FOLDER_REPOINT)
    /** #1288 the runner's scope of a folder command (never declared in depth_actions). */
    const val DEPTH_SCOPE_FOLDER = "folder"
    /** #1315 the top bar's item kinds (ui.sync.git.top_bar.groups[].items[].kind), dispatched by GitReposScreen; test-drive-git-top-bar.sh diffs both ways. */
    /** #1679 refresh_list / fetch_status = Refresh ▸ Refresh repo list / Fetch status; remove_auto_cloned = More ▸ Remove auto-cloned copies. */
    val TOP_KINDS = setOf("check_light", "check_full", "check_remote", "check_sizes", "scan_inventory", "cancel_checks", "op", "compound", "pull", "depth", "open_logs", "open_report", "toggle_filter", "legend", "open_advanced", "open_conflicts", "refresh_list", "fetch_status", "remove_auto_cloned")
    const val REMOTE_READONLY = "readonly"

    /** #608 the Git page's two sections and the personal section's two groups, by id. */
    const val GIT_SECTION_PUBLIC = "public"
    const val GIT_SECTION_PERSONAL = "personal"
    const val GIT_GROUP_PUBLIC = "public"
    const val GIT_GROUP_PRIVATE = "private"

    /** #604 the machine class a connection without a declared `machine` belongs to: the container mesh. */
    const val MACHINE_CONTAINER = "container"
    const val DIRECTION_UPLOAD = "upload"
    const val DIRECTION_DOWNLOAD = "download"
    const val DIRECTION_BIDIRECTIONAL = "bidirectional"

    /** #603 the seed set of the shared store: the PUBLIC repositories marked `seed` in the manifest. */
    val seedRepos: List<GitRepoDecl> get() = gitFamily.repos.filter { it.seed && !it.private }
}
