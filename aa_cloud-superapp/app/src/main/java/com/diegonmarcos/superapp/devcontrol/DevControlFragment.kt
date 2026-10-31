package com.diegonmarcos.superapp.devcontrol

import com.diegonmarcos.superapp.agents.AgentStatusReader
import com.diegonmarcos.superapp.agents.FleetAgents
import com.diegonmarcos.superapp.configs.Diagnostics
import com.diegonmarcos.superapp.devtools.DevControlPrefs
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.system.AppProcessUptime
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.StackAnchors
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.battery.SysfsProc
import com.diegonmarcos.superapp.network.WgState
import com.diegonmarcos.superapp.network.WireGuardPrefs
import com.diegonmarcos.superapp.profile.ProfilePrefs
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.updater.BuildConfig as UpdBuildConfig
import com.diegonmarcos.superapp.updater.BuildAge
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.webserver.CloudWebServer
import com.diegonmarcos.superapp.webserver.WebServerPrefs
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.TimeZone

/**
 * About page — comprehensive runtime + build metadata for the user to inspect. Long-press any monospace row to copy to
 * clipboard.
 *
 * Compose since #773 (was a ScrollView of hand-built Views). The page is declared once in [declare] — macro groups and
 * service blocks, each block's body a function of an [AboutItems] — and drawn from a LazyColumn. A block's body does not run
 * until it is reached (#286): the viewport triggers it (a finger scroll or an Index card — both reach a block by scrolling
 * to it), and an idle loop warms the rest one block per frame (#333) so scrolling rarely meets a placeholder.
 */
class DevControlFragment : KitComposeFragment() {

    /** What the page head wrote to the copy buffer (the title); every block owns its own buffer, assembled in DECLARED order
     *  by [copyAll] because bodies finish in whatever order the user reaches them. */
    /** The page title (build.json label) and the macro groups it draws: all of them (X-Ray) or [ABOUT_MACROS] (About). */
    private val pageTitle: String get() = arguments?.getString(ARG_TITLE) ?: "X-Ray"
    private val onlyMacros: Set<String>? get() = arguments?.getStringArrayList(ARG_MACROS)?.toSet()

    private val infoBuf by lazy { StringBuilder("# $pageTitle · Cloud SuperApp\n") }

    private fun ctxAny(): Context = requireContext()

    override fun palette() = LauncherPalette.kit(requireContext())

    /** The text prompt on screen (web server port / document root), or null. */
    private var prompt by mutableStateOf<AboutPrompt?>(null)

    @Composable
    override fun Content() {
        val ctx = requireContext()
        val scope = rememberCoroutineScope()
        val page = remember { AboutPage(attached = { isAdded && view != null }, only = onlyMacros).also { declare(it) } }
        val blocks = remember(page) { page.withBackLinks() }
        val list = rememberLazyListState()
        val actions = remember {
            AboutActions(
                copy = { toast, text -> if (text.isNotEmpty()) copy(ctx, text); Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show() },
                toast = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() },
            )
        }

        // Fill a section when it is reached. Detached hosts render nothing: every helper reaches for the context, which
        // is the #194 throw.
        fun loadSection(s: AboutSection) { if (isAdded && view != null) s.load(scope) }

        // Render before the jump, not because of it: a section that only starts rendering once the scroll settles would
        // show a placeholder at the exact moment it was asked for.
        fun goMacro(label: String) {
            for (s in page.sections) if (s.macro == label) loadSection(s)
            val at = blocks.indexOfFirst { it is AboutBlock.Macro && it.label == label }
            if (at >= 0) scope.launch { list.animateScrollToItem(HEAD_ITEMS + at) }
        }

        // Fill the rest of the page ONE SECTION PER FRAME once the opening viewport is painted. #286 made the page open
        // instantly by deferring every body; the cost was that scrolling then met a placeholder. This keeps the fast open
        // and spends the idle main loop afterwards. One section per frame keeps each unit of work short enough that the
        // scroll never janks.
        LaunchedEffect(page) {
            withFrameNanos { }
            for (s in page.sections) { loadSection(s); withFrameNanos { } }
        }

        CompositionLocalProvider(LocalAboutActions provides actions) {
            LazyColumn(Modifier.fillMaxSize().testTag(AboutTags.LIST), state = list, contentPadding = PaddingValues(16.dp)) {
                item(key = "title") { AboutTitle("$pageTitle · Cloud SuperApp") }
                item(key = "index") { AboutIndex(page.macros, ::goMacro) }
                itemsIndexed(blocks, key = { i, b -> blockKey(i, b) }) { _, block ->
                    when (block) {
                        is AboutBlock.Macro -> AboutMacroHeader(block)
                        is AboutBlock.Section -> {
                            // The viewport is the trigger: a block composed is a block reached.
                            LaunchedEffect(block.section) { loadSection(block.section) }
                            AboutSectionView(block.section)
                        }
                        AboutBlock.BackToIndex -> AboutBackToIndex { scope.launch { list.animateScrollToItem(1) } }
                        AboutBlock.CopyAll -> AboutButton(AboutItem.Button("Copy All Infos", false, onClick = { copyAll(ctx, page) }))
                    }
                }
            }
            prompt?.let { AboutPromptDialog(it) { prompt = null } }
        }
    }

    private fun blockKey(i: Int, b: AboutBlock): String = when (b) {
        is AboutBlock.Macro -> "macro:${b.label}"
        is AboutBlock.Section -> "section:${b.section.head}"
        AboutBlock.BackToIndex -> "back:$i"
        AboutBlock.CopyAll -> "copy-all"
    }

    // Tail-anchor: snapshot the entire About page to the clipboard. Each section owns its own buffer and fills it only when it
    // loads, so the dump is assembled in DECLARED order here. A section that has not loaded says so: silently omitting it
    // would make a partial dump look like a complete one, which is the same lie as an empty section.
    private fun copyAll(ctx: Context, page: AboutPage) {
        val sb = StringBuilder(infoBuf)
        for (s in page.sections) {
            sb.append(s.buf)
            if (!s.started) sb.append("  (not loaded — scroll to this section to read it)\n")
        }
        val snapshot = sb.toString()
        copy(ctx, snapshot)
        Toast.makeText(ctx,
            "Copied ${snapshot.length} chars (${snapshot.count { it == '\n' }} lines)",
            Toast.LENGTH_SHORT).show()
    }

    /** Read per-app foreground + background time from UsageStatsManager. Returns (foregroundMs, backgroundMs); both -1 if the
     *  user hasn't granted PACKAGE_USAGE_STATS. Window = last 7 days. */
    private fun readUsageStats(ctx: Context): Pair<Long, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return -1L to -1L
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val stats = runCatching {
            usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_WEEKLY,
                weekAgo, now,
            )
        }.getOrNull() ?: return -1L to -1L
        if (stats.isEmpty()) return -1L to -1L
        val me = stats.firstOrNull { it.packageName == ctx.packageName }
            ?: return 0L to 0L
        val fg = me.totalTimeInForeground
        // backgroundTime() is only on API 29+; older versions report 0.
        val bg = if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val m = me.javaClass.getMethod("getTotalTimeForegroundServiceUsed")
                (m.invoke(me) as? Long) ?: 0L
            }.getOrDefault(0L)
        } else 0L
        return fg to bg
    }

    private fun fmtDuration(ms: Long): String = AboutFormat.fmtDuration(ms)
    private fun fmtSecs(s: Long): String = AboutFormat.fmtSecs(s)
    private fun fmtMillis(ms: Long): String = AboutFormat.fmtMillis(ms)
    private fun sizeStr(bytes: Long): String = AboutFormat.sizeStr(bytes)

    /** The page, declared. Nothing in here runs a section body: [AboutPage.section] only registers it. */
    private fun declare(page: AboutPage) = with(page) {
        val ctx = requireContext()
        // The context a background probe is allowed to hold. requireContext() off the main thread is the #194 throw (a
        // fragment reaching for a host it has already been detached from); the application context cannot be detached and
        // answers packageManager/packageName the same.
        val appCtx = ctx.applicationContext

        // ══ APP & BUILD macro section — this APK's identity: package/version,
        //    release channel, signing, provenance, declared sections, and the full stack scan.
        macro("📱  APP & BUILD")

        infoSection("App") {
            row("Name",         BuildConfig.APPLICATION_ID)
            row("Version",      BuildConfig.VERSION_NAME)
            row("Version code", BuildConfig.VERSION_CODE.toString())
            // The versionCode is wall-clock encoded (3,000,000 + minutes since
            // 2026-01-01 UTC), so "3356276" is a DATE nobody can read. Decoding
            // it is the difference between a number and the answer to the only
            // question anyone asks here: how old is what I am running.
            row("Built from code", BuildAge.describe(BuildConfig.VERSION_CODE.toLong()))
            row("Git sha",      BuildConfig.GIT_SHORT_SHA)
            row("Built (UTC)",  BuildConfig.BUILD_TIMESTAMP)
            row("Build type",   BuildConfig.BUILD_TYPE)
            row("Debuggable",   BuildConfig.DEBUG.toString())
        }

        infoSection("Sections (from build.json)") {
            val all = Sections.all()
            row("Total",       all.size.toString())
            row("Bottom-nav",  all.count { s -> s.bottomNav }.toString())
            row("Aggregators", all.count { s -> s.isAggregator }.toString())
            row("Default mode", Sections.defaultMode())
            row("Default section", Sections.defaultSectionId())
        }

        infoSection("Stack") {
            // Languages — decode the base64 JSON blob the build emitted.
            // Format: {"Kotlin":{"files":N,"loc":M}, …}
            small("What's actually inside this APK — scanned by the build, never a hardcoded list.")
            val langB64 = BuildConfig.UI_STACK_LANGUAGES_JSON_B64
            val langJson = runCatching { String(android.util.Base64.decode(langB64, android.util.Base64.DEFAULT)) }.getOrDefault("{}")
            val langs = runCatching {
                val o = org.json.JSONObject(langJson)
                val list = mutableListOf<Triple<String, Int, Int>>()
                val it2 = o.keys()
                while (it2.hasNext()) {
                    val k = it2.next()
                    val v = o.optJSONObject(k) ?: continue
                    list += Triple(k, v.optInt("files", 0), v.optInt("loc", 0))
                }
                list.sortedByDescending { tr -> tr.third }
            }.getOrDefault(emptyList())
            val totalLoc = langs.sumOf { tr -> tr.third }
            val totalFiles = langs.sumOf { tr -> tr.second }
            row("Total LOC",   "%,d lines".format(totalLoc))
            row("Total files", totalFiles.toString())
            for ((lang, files, loc) in langs) {
                val pct = if (totalLoc > 0) " (%d%%)".format(loc * 100 / totalLoc) else ""
                row(lang, "%,d lines · %d file(s)%s".format(loc, files, pct))
            }

            // Frameworks — sorted unique Maven coordinates that the
            // gradle scripts depend on. Internal :libs:* are skipped.
            small("Frameworks (Maven coordinates from every *.gradle in the repo):")
            val fwB64 = BuildConfig.UI_STACK_FRAMEWORKS_JSON_B64
            val fwJson = runCatching { String(android.util.Base64.decode(fwB64, android.util.Base64.DEFAULT)) }.getOrDefault("[]")
            val frameworks = runCatching {
                val arr = org.json.JSONArray(fwJson)
                (0 until arr.length()).map { i -> arr.getString(i) }
            }.getOrDefault(emptyList())
            row("Dep count", frameworks.size.toString())
            for (dep in frameworks) {
                // Strip the version off the front for terseness; show
                // "group:artifact" with version on a second row.
                val parts = dep.split(":")
                val head = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else dep
                val ver  = if (parts.size >= 3) parts[2] else "?"
                row(head, ver)
            }

            // Local internal modules — derived from build.json::modules.
            small("Internal modules (build.json::modules, project(':libs:*')):")
            // Modules are part of buildJson::modules but we don't have it
            // here — list nativeLibraryDir contents (per-ABI native libs)
            // instead, which is its own useful "what's in here" line.
            val pm2 = ctxAny().packageManager
            @Suppress("DEPRECATION")
            // PackageInfo.applicationInfo is @Nullable as of API 35.
            val ai = pm2.getPackageInfo(ctxAny().packageName, 0).applicationInfo
            val splits = ai?.splitSourceDirs
            val baseApk = ai?.sourceDir?.let { File(it) }
            row("Base APK",
                if (baseApk != null) "${baseApk.name} · ${sizeStr(baseApk.length())}" else "—")
            splits?.forEach { s ->
                row("Split", File(s).name + " · " + sizeStr(File(s).length()))
            }

            // Build-time metrics. Avg/last numbers come from `gh run
            // list` at gradle config time — full wall-clock from queue
            // to "completed" of the GHA `ship-cloud-superapp.yml`
            // workflow, sampling the most-recent N successful runs.
            small("Build metrics (from GitHub Actions history):")
            row("Build avg time", fmtSecs(BuildConfig.STACK_BUILD_AVG_SECS) +
                if (BuildConfig.STACK_BUILD_SAMPLE > 0) " (n=${BuildConfig.STACK_BUILD_SAMPLE})" else "")
            row("Build last",     fmtSecs(BuildConfig.STACK_BUILD_LAST_SECS))
            row("Gradle config phase", "%d ms".format(BuildConfig.STACK_GRADLE_CONFIG_MS))
            row("Build SHA",      BuildConfig.GIT_SHORT_SHA)

            // Three trees SIDE-BY-SIDE — Sitemap | Folders | AST. Accordion: tapping one button OPENS its box and
            // CLOSES the other two (only one box visible at a time). LONG-PRESS — on the button OR anywhere in the open
            // box — copies that tree's full text to the clipboard. (Folders also feeds the page-copy buffer.)
            fun dec(b64: String) = runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }.getOrDefault("—")
            // Each tree is a list of (column-header, text) columns. Sitemap and AST are single-column; Folders is three
            // columns — L3 / L4 / L5 — each a depth-bounded view of the same ea_* scan.
            val trees = listOf(
                AboutTree("Sitemap", listOf("" to dec(BuildConfig.UI_ASM_TREE_B64))),
                AboutTree("Folders", listOf(
                    "L3" to dec(BuildConfig.UI_STACK_FOLDER_TREE_L3_B64),
                    "L4" to dec(BuildConfig.UI_STACK_FOLDER_TREE_L4_B64),
                    "L5" to dec(BuildConfig.UI_STACK_FOLDER_TREE_B64),
                )),
                AboutTree("AST", listOf("" to dec(BuildConfig.UI_AST_TREE_B64))),
            )
            buf.append("\n```\n").append(trees[1].columns.last().second).append("\n```\n")
            custom { AboutTreeAccordion(trees) }
        }

        // ══ REPO & RELEASES macro section — where this build CAME FROM: the
        //    owner profile and its repos, the published release, the artifact
        //    itself, the key that signed it and the provenance that ties the
        //    three together. Renamed from "Cloud Identity", and the release,
        //    APK, signing and provenance sections moved in from App & Build so
        //    the heading is not a lie: identity alone never explained a build.
        macro("☁  REPO & RELEASES")

        infoSection("Profile") {
            val prof = ProfilePrefs(ctxAny())
            row("Name",     prof.name.ifBlank { com.diegonmarcos.superapp.account.BuildConfig.UI_PROFILE_NAME })
            row("Email",    prof.email.ifBlank { com.diegonmarcos.superapp.account.BuildConfig.UI_PROFILE_EMAIL })
            row("Company",  prof.company.ifBlank { com.diegonmarcos.superapp.account.BuildConfig.UI_PROFILE_COMPANY })
            row("Location", prof.location.ifBlank { com.diegonmarcos.superapp.account.BuildConfig.UI_PROFILE_LOCATION })
            val site = prof.website.ifBlank { com.diegonmarcos.superapp.account.BuildConfig.UI_PROFILE_WEBSITE }
            if (site.isNotBlank()) row("Website", site)
            // Repos — data-driven from build.json::ui.profile_default.repos.
            small("Repos:")
            for ((label, url) in parseProfileRepos()) row(label, url)
            // Install — the repo links above tell you where the SOURCE lives,
            // which is no help to someone holding a broken build: reading the
            // code is not installing it. The artifact those repos produce gets
            // its own address here, next to them, plus the button that uses it.
            small(getString(R.string.about_install_header))
            renderDirectInstall()
            // Wireless Debugging, next to the install button rather than only in
            // "Dev Tools" twenty sections below. Same audience, same
            // moment: the channel that makes an install silent is the switch
            // someone reaching for the recovery install most often needs, and a
            // control that far down the page is one the user reports as absent.
            // Status is read here (unprivileged); every control (pair, connect, the server, Shizuku)
            // is on the ADB Shell page, which the button opens.
            row("Wireless debugging",
                if (WirelessDebugging.isOn(ctxAny())) "ON" else "OFF")
            button("ADB Shell ›", gray = true) { com.diegonmarcos.superapp.adbdebug.AdbShellLink.open(ctxAny()) }
            // The consolidated cloud-data config that feeds the mesh.
            small("Config source:")
            row("Consolidated", BuildConfig.UI_CONSOLIDATED_CONFIG.ifBlank { "—" })
        }

        infoSection("Release / GHCR") {
            row("Registry",  UpdBuildConfig.GHCR_REGISTRY)
            row("Namespace", UpdBuildConfig.GHCR_NAMESPACE)
            row("Image",     UpdBuildConfig.GHCR_IMAGE)
            row("Tag",       UpdBuildConfig.AUTO_UPDATE_TAG)
            row("Full URL",
                "${UpdBuildConfig.GHCR_REGISTRY}/${UpdBuildConfig.GHCR_NAMESPACE}/${UpdBuildConfig.GHCR_IMAGE}:${UpdBuildConfig.AUTO_UPDATE_TAG}")
            row("Check interval", "${UpdBuildConfig.AUTO_UPDATE_INTERVAL_HOURS}h")
        }

        infoSection("APK") {
            val pm = requireContext().packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(requireContext().packageName, 0)
            // PackageInfo.applicationInfo is @Nullable as of API 35.
            // Fall back to "—" so the dev-control row still renders.
            val path = info.applicationInfo?.sourceDir ?: "—"
            val size = runCatching { File(path).length() }.getOrDefault(0L)
            row("Path",       path)
            row("Size",       sizeStr(size))
            row("Installed",  fmtMillis(info.firstInstallTime))
            row("Updated",    fmtMillis(info.lastUpdateTime))
        }

        infoSection("Signing") {
            val pm = requireContext().packageManager
            val sigFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            val sigPkg = runCatching { pm.getPackageInfo(requireContext().packageName, sigFlags) }.getOrNull()
            val sigBytes = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    sigPkg?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                else @Suppress("DEPRECATION") sigPkg?.signatures?.firstOrNull()?.toByteArray()
            }.getOrNull()
            if (sigBytes != null) {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(sigBytes.inputStream()) as X509Certificate
                val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    .joinToString(":") { "%02X".format(it) }
                row("Subject",     cert.subjectDN.name)
                row("SHA-256",     sha256)
                row("Valid until", fmtMillis(cert.notAfter.time))
            } else {
                row("Cert", "—")
            }
        }

        infoSection("APK provenance") {
            val pm = ctxAny().packageManager
            val pkg = ctxAny().packageName
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val src = runCatching { pm.getInstallSourceInfo(pkg) }.getOrNull()
                row("Installed by", src?.installingPackageName ?: "—")
                row("Initiated by", src?.initiatingPackageName ?: "—")
                if (android.os.Build.VERSION.SDK_INT >= 34)
                    row("Update owner", src?.updateOwnerPackageName ?: "—")
            } else {
                @Suppress("DEPRECATION")
                val installer = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
                row("Installed by", installer ?: "—")
            }
            // Signing cert SHA-256 — proves "this APK was signed by my keystore"
            val sigInfo = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                } else null
            }.getOrNull()
            val sigs = sigInfo?.signingCertificateHistory ?: sigInfo?.apkContentsSigners
            if (sigs.isNullOrEmpty()) {
                row("Cert SHA-256", "—")
            } else {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val hex = md.digest(sigs[0].toByteArray()).joinToString(":") { "%02X".format(it) }
                row("Cert SHA-256", hex)
            }
            // Split APKs (base + per-density + per-ABI).
            // PackageInfo.applicationInfo is @Nullable as of API 35;
            // safe-call every access and degrade to "—" / 0 if the
            // platform decides not to hand us an ApplicationInfo.
            @Suppress("DEPRECATION")
            val appInfo = pm.getPackageInfo(pkg, 0).applicationInfo
            val splits = appInfo?.splitSourceDirs?.size ?: 0
            row("Splits",      "$splits split APK(s)")
            row("Native lib dir", appInfo?.nativeLibraryDir ?: "—")
            val nativeLibs = runCatching { File(appInfo?.nativeLibraryDir ?: "").listFiles()?.map { it.name } }.getOrNull()
            row("Native libs", nativeLibs?.joinToString(", ") ?: "—")
        }

        // ══ DEVICE macro section — the physical/OS device this build is running on.
        macro("🖥️  DEVICE")

        infoSection("Device / stack") {
            row("Manufacturer", android.os.Build.MANUFACTURER)
            row("Model",        android.os.Build.MODEL)
            row("Brand",        android.os.Build.BRAND)
            row("Device",       android.os.Build.DEVICE)
            row("Hardware",     android.os.Build.HARDWARE)
            row("Android",      "${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            row("ABIs",         android.os.Build.SUPPORTED_ABIS.joinToString(", "))
            row("Locale",       java.util.Locale.getDefault().toLanguageTag())
        }

        infoSection("Kernel / OS") {
            row("Kernel",   System.getProperty("os.version") ?: "—")
            row("Security patch", android.os.Build.VERSION.SECURITY_PATCH)
            row("Codename", android.os.Build.VERSION.CODENAME)
            row("Incremental", android.os.Build.VERSION.INCREMENTAL)
            row("/proc/version", runCatching { File("/proc/version").readText().trim() }.getOrDefault("—"))
            row("VM",       "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
            row("VM heap",  "${sizeStr(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())} / ${sizeStr(Runtime.getRuntime().maxMemory())}")
            val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize()
            row("Native heap", sizeStr(nativeHeap))
        }

        infoSection("SoC / CPU") {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                row("SoC mfr",   android.os.Build.SOC_MANUFACTURER)
                row("SoC model", android.os.Build.SOC_MODEL)
            }
            row("Bootloader", android.os.Build.BOOTLOADER)
            @Suppress("DEPRECATION")
            row("Radio",      android.os.Build.getRadioVersion() ?: "—")
            row("Board",      android.os.Build.BOARD)
            row("Fingerprint", android.os.Build.FINGERPRINT)
            row("CPU cores",  Runtime.getRuntime().availableProcessors().toString())
            // /proc/cpuinfo is scanned line by line and the two cpufreq nodes
            // are sysfs reads — cheap individually, but this is a diagnostics
            // page and none of it needs to happen on the way to a frame.
            launch {
                val cpuModel = row("CPU model", "reading…")
                val curFreqRow = row("Cur freq", "reading…")
                val govRow = row("Governor", "reading…")
                val facts = withContext(Dispatchers.IO) {
                    val m = runCatching {
                        File("/proc/cpuinfo").useLines { lines ->
                            lines.firstOrNull { l -> l.startsWith("Hardware") || l.contains("model name") }
                                ?.substringAfter(':')?.trim()
                        }
                    }.getOrNull()
                    val f = runCatching {
                        File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toLong()
                    }.getOrNull()
                    val g = runCatching {
                        File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readText().trim()
                    }.getOrNull()
                    Triple(m, f, g)
                }
                if (!isAdded) return@launch
                cpuModel.text = facts.first ?: "—"
                curFreqRow.text = facts.second?.let { v -> "%d MHz".format(v / 1000) } ?: "—"
                govRow.text = facts.third ?: "—"
            }
        }

        // Two sysfs reads per zone, and a phone can expose dozens. Off-thread.
        asyncSection("Thermal zones",
            probe = {
                runCatching {
                    File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                        ?.sortedBy { it.name }
                        ?.map { z ->
                            val type = runCatching { File(z, "type").readText().trim() }.getOrDefault(z.name)
                            val tempMilliC = runCatching { File(z, "temp").readText().trim().toLong() }.getOrDefault(0L)
                            z.name to "$type — %.1f °C".format(tempMilliC / 1000.0)
                        }
                }.getOrNull().orEmpty()
            },
            render = { zones ->
                if (zones.isEmpty()) {
                    small("No thermal zones readable (kernel restricts /sys/class/thermal on most modern devices).")
                } else {
                    for ((k, v) in zones) row(k, v)
                }
            })

        // SYSFS-PROC — the no-perm kernel-telemetry dump. Every field
        // here comes from a world-readable /sys or /proc file (the
        // same path AccuBattery, Termux's top, and friends use). No
        // DUMP, no QUERY_ALL_PACKAGES, no signature perm required.
        // The reader (SysfsProc.kt) returns (key, formatted-value)
        // pairs per subsystem; we paint sub-group headers via small()
        // so the section is one big scrollable table the user can
        // long-press individual rows to copy.

        infoSection("Display") {
            val wm = ctxAny().getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            val dm = resources.displayMetrics
            row("Resolution", "${dm.widthPixels} × ${dm.heightPixels}")
            row("Density",    "${dm.density}x · ${dm.densityDpi} dpi")
            @Suppress("DEPRECATION")
            val display = wm?.defaultDisplay
            row("Refresh",    display?.refreshRate?.let { "%.1f Hz".format(it) } ?: "—")
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                val modes = display?.supportedModes?.map { "%.0f Hz @ %dx%d".format(it.refreshRate, it.physicalWidth, it.physicalHeight) }
                row("Modes", modes?.joinToString(", ") ?: "—")
            }
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                row("HDR",       display?.hdrCapabilities?.supportedHdrTypes?.joinToString(",") ?: "—")
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                row("Wide gamut", display?.isWideColorGamut?.toString() ?: "—")
            }
            val cfg = resources.configuration
            val dark = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            row("Dark mode", dark.toString())
            row("Font scale", "${cfg.fontScale}x")
        }

        infoSection("Sensors") {
            val sm = ctxAny().getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            val all = sm?.getSensorList(android.hardware.Sensor.TYPE_ALL) ?: emptyList()
            row("Count", all.size.toString())
            for ((idx, s) in all.withIndex()) {
                row("Sensor ${idx + 1}", "${s.name} — ${s.vendor}")
            }
        }

        infoSection("Locale & time") {
            val locales = if (android.os.Build.VERSION.SDK_INT >= 24)
                (0 until resources.configuration.locales.size()).joinToString(", ") {
                    resources.configuration.locales[it].toLanguageTag()
                }
            else
                @Suppress("DEPRECATION") resources.configuration.locale.toLanguageTag()
            row("Locales",  locales)
            val tz = TimeZone.getDefault()
            row("Timezone", "${tz.id} (${tz.displayName})")
            val offMin = tz.rawOffset / 60000
            row("UTC offset", "%+03d:%02d".format(offMin / 60, kotlin.math.abs(offMin) % 60))
            row("In DST",   tz.inDaylightTime(Date()).toString())
            val autoTime = runCatching {
                android.provider.Settings.Global.getInt(ctxAny().contentResolver,
                    android.provider.Settings.Global.AUTO_TIME) == 1
            }.getOrDefault(false)
            row("Auto time", autoTime.toString())
            // System uptime gap = boot wall-clock.
            val bootWall = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            row("Booted",   fmtMillis(bootWall))
            row("System uptime", fmtDuration(android.os.SystemClock.elapsedRealtime()))
        }

        // ══ SECURITY macro section — device lock state, ADB/dev-options exposure, biometrics.
        macro("🔐  SECURITY")

        infoSection("Security posture") {
            val cr = ctxAny().contentResolver
            val devMode = runCatching {
                android.provider.Settings.Global.getInt(cr,
                    android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
            }.getOrDefault(false)
            val adbEnabled = runCatching {
                android.provider.Settings.Global.getInt(cr,
                    android.provider.Settings.Global.ADB_ENABLED) == 1
            }.getOrDefault(false)
            row("Dev options", devMode.toString())
            row("USB ADB",     adbEnabled.toString())
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val adbWifi = runCatching {
                    android.provider.Settings.Global.getInt(cr, "adb_wifi_enabled") == 1
                }.getOrDefault(false)
                row("Wireless ADB", adbWifi.toString())
                for ((k, v) in com.diegonmarcos.superapp.system.WirelessDebugKeeper.Status.rows(ctxAny()))
                    row("Keep-alive $k", v)
            }
            val km = ctxAny().getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            row("Device secure",  (km?.isDeviceSecure ?: false).toString())
            row("Keyguard locked", (km?.isKeyguardLocked ?: false).toString())
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val bm = ctxAny().getSystemService(Context.BIOMETRIC_SERVICE)
                    as? android.hardware.biometrics.BiometricManager
                val biometric = runCatching {
                    @Suppress("DEPRECATION")
                    bm?.canAuthenticate() ?: -1
                }.getOrDefault(-1)
                row("Biometric ready", when (biometric) {
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS              -> "Yes"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED  -> "No (not enrolled)"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE    -> "No (no hardware)"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Hardware unavailable"
                    else -> "Unknown"
                })
            }
        }

        // ══ RESOURCES macro section — storage, battery, memory and raw kernel telemetry.
        macro("🔋  RESOURCES")

        // THE worst offender on this page: dirSize() is a recursive walk of the
        // whole private data tree and it ran TWICE, on the main thread, before
        // the page could draw. On an install with a large cache that is seconds,
        // not milliseconds — an ANR, not a jank.
        asyncSection("Storage", timeoutMs = 15_000,
            probe = {
            val ctxAny = appCtx
            val pm = ctxAny.packageManager
            @Suppress("DEPRECATION")
            val pkg = pm.getPackageInfo(ctxAny.packageName, 0)
            // PackageInfo.applicationInfo is @Nullable as of API 35;
            // null-guard once, then degrade gracefully per field —
            // APK size to 0 (visible "—" in the row), dataDir to
            // Context.getDataDir() which is always non-null.
            val appInfo   = pkg.applicationInfo
            val apkBytes  = runCatching { File(appInfo?.sourceDir ?: "").length() }.getOrDefault(0L)
            // "Datos" = the app's private data root — includes filesDir,
            // databases, shared_prefs, and everything else the app
            // persists outside of cacheDir.
            val dataDir   = appInfo?.dataDir?.let { File(it) } ?: ctxAny.dataDir
            val cacheDir  = ctxAny.cacheDir
            val cacheBytes = dirSize(cacheDir)
            // dataDir contains cacheDir; subtract to get pure "data".
            val dataBytes  = (dirSize(dataDir) - cacheBytes).coerceAtLeast(0L)
            val totalBytes = apkBytes + dataBytes + cacheBytes

            StorageFacts(
                filesDir = ctxAny.filesDir.absolutePath,
                cacheDir = cacheDir.absolutePath,
                dataRoot = dataDir.absolutePath,
                external = ctxAny.getExternalFilesDir(null)?.absolutePath ?: "—",
                traceLog = sizeStr(File(ctxAny.getExternalFilesDir(null), "trace/trace.log").length()),
                apk      = sizeStr(apkBytes),
                data     = sizeStr(dataBytes),
                cache    = sizeStr(cacheBytes),
                total    = sizeStr(totalBytes),
            )
            },
            render = { f ->
                // Paths first.
                row("Files dir",   f.filesDir)
                row("Cache dir",   f.cacheDir)
                row("Data root",   f.dataRoot)
                row("External",    f.external)
                row("Trace log",   f.traceLog)
                small("Breakdown — same buckets Android system settings shows:")
                row("Aplicación",  f.apk)
                row("Datos",       f.data)
                row("Caché",       f.cache)
                row("Total",       f.total)
            })

        // Permissions section moved to PermissionsFragment (config/perms tab).

        infoSection("Battery & Usage") {
            // Everything in this block is what the app CAN read about
            // itself without privileged permissions. Per-app battery mAh
            // + screen-on / background time split / wakelocks count etc.
            // live in BatteryStatsManager which is system-only. The
            // "Open battery usage details" button below jumps to the
            // OS screen the user pasted from for the full picture.
            val ctxAny = requireContext()
            val pkg = ctxAny.packageManager.getPackageInfo(ctxAny.packageName, 0)
            val myUid = ctxAny.applicationInfo.uid

            // App-side crash count — files in CrashLogger's private dir
            // (getExternalFilesDir/crashes/crash-<ts>.txt).
            val crashDir = File(ctxAny.getExternalFilesDir(null), "crashes")
            val crashes  = crashDir.listFiles { f -> f.name.startsWith("crash-") }?.size ?: 0
            val mostRecent = crashDir.listFiles { f -> f.name.startsWith("crash-") }
                ?.maxByOrNull { it.lastModified() }
            row("Crash count",  crashes.toString())
            row("Last crash",   mostRecent?.let { fmtMillis(it.lastModified()) } ?: "—")
            row("Crashes dir",  crashDir.absolutePath)

            // Process uptime since this Application's onCreate.
            val uptimeMs = android.os.SystemClock.elapsedRealtime() -
                AppProcessUptime.startedAtElapsed
            row("Process uptime", fmtDuration(uptimeMs))
            row("First install",  fmtMillis(pkg.firstInstallTime))
            row("Last update",    fmtMillis(pkg.lastUpdateTime))

            // Network bytes RX/TX since boot (TrafficStats is per-UID;
            // resets on reboot). Captures all sockets this UID has used.
            val rx = android.net.TrafficStats.getUidRxBytes(myUid)
            val tx = android.net.TrafficStats.getUidTxBytes(myUid)
            val rxMobile = android.net.TrafficStats.getMobileRxBytes()
            val txMobile = android.net.TrafficStats.getMobileTxBytes()
            row("Net RX (all)",    sizeStr(if (rx < 0) 0L else rx))
            row("Net TX (all)",    sizeStr(if (tx < 0) 0L else tx))
            row("Mobile RX (UID)", if (rxMobile < 0) "—" else sizeStr(rxMobile))
            row("Mobile TX (UID)", if (txMobile < 0) "—" else sizeStr(txMobile))

            // Per-app foreground / background screen time — needs
            // PACKAGE_USAGE_STATS (Settings.ACTION_USAGE_ACCESS_SETTINGS).
            // queryUsageStats over a 7-day window is a binder call that walks
            // the whole usage database — the single slowest read left in this
            // section, so it fills its three rows asynchronously instead of
            // holding the block (and, before #286, the entire page) behind it.
            val fgRow = row("Screen-on",  "reading…")
            val bgRow = row("Background", "reading…")
            val totRow = row("Total used", "reading…")
            launch {
                val stats = runCatching {
                    withContext(Dispatchers.IO) { readUsageStats(appCtx) }
                }
                if (!isAdded) return@launch
                val (fgMs, bgMs) = stats.getOrDefault(-1L to -1L)
                // "Needs Usage Access" is a REAL answer (the permission is not
                // granted); a thrown read is not, and must not be dressed up
                // as one — this page is a diagnostics surface.
                if (stats.isFailure) {
                    val why = stats.exceptionOrNull()?.javaClass?.simpleName ?: "error"
                    fgRow.text = "unavailable ($why)"
                    bgRow.text = "unavailable ($why)"
                    totRow.text = "—"
                } else {
                    fgRow.text  = if (fgMs < 0) "Needs Usage Access" else fmtDuration(fgMs)
                    bgRow.text  = if (bgMs < 0) "Needs Usage Access" else fmtDuration(bgMs)
                    totRow.text = if (fgMs < 0 || bgMs < 0) "—" else fmtDuration(fgMs + bgMs)
                }
            }

            // ── Battery: the SoT's summary, the full page one tap away ─────
            // The rows are the battery Source of Truth's (libs:battery
            // BatteryRepository.report → BatteryRows), the same values the
            // home-screen popup and the Battery badge show; nothing is
            // computed here. The full stats (graph, cycles, charge sessions,
            // capacity vs design, privileged extras) are Configs › About ›
            // Battery, page:config/battery.
            val rep = runCatching { com.diegonmarcos.superapp.battery.BatteryRepository.report(ctxAny) }.getOrNull()
            if (rep != null) {
                for (sec in com.diegonmarcos.superapp.battery.BatteryRows.popup(rep)) {
                    small("— ${sec.title} —")
                    for (r in sec.rows) row(r.label, r.value)
                }
                if (rep.reading.onPower)
                    row("Charger input", com.diegonmarcos.superapp.battery.BatteryRows.charger(rep))
            } else row("Battery", "unavailable")
            button("Battery stats ›", gray = true) {
                runCatching { com.diegonmarcos.superapp.batterystats.BatteryStatsPage.open(requireActivity()) }
            }
            small("Battery-stats internals (mAh per-component, wakelocks, wakeups) are system-only. Grant Usage Access + Set Battery No Optimization shortcuts live in the Permissions section above.")
            // Deep BatteryManager / sticky-intent dump — merged in from
            // the former separate "Battery (deep)" section (one Battery
            // section, not two).
            small("— Deep (raw BatteryManager + sticky intent, as reported) —")
            renderBatteryDeep()
            // AccuBattery-style energy page — device state→draw
            // attribution, per-foreground-app draw, and our own subsystem
            // ledger (what INSIDE the app costs). Backed by the
            // EnergyWatchdog Tier-1 sampler + EnergyLedger.
            button("Battery Usage Details", gray = true) {
                runCatching {
                    com.diegonmarcos.superapp.battery.EnergyUsageDialog()
                        .show(parentFragmentManager, com.diegonmarcos.superapp.battery.EnergyUsageDialog.TAG)
                }
            }
        }

        infoSection("Memory") {
            val am = ctxAny().getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            row("Total RAM",  sizeStr(mi.totalMem))
            row("Available",  sizeStr(mi.availMem))
            row("Threshold",  sizeStr(mi.threshold))
            row("Low memory", mi.lowMemory.toString())
            val pids = intArrayOf(android.os.Process.myPid())
            val procInfo = runCatching { am?.getProcessMemoryInfo(pids) }.getOrNull()?.firstOrNull()
            if (procInfo != null) {
                row("App PSS",  sizeStr(procInfo.totalPss * 1024L))
                row("Java heap", sizeStr(procInfo.getMemoryStat("summary.java-heap")?.toLongOrNull()?.times(1024L) ?: 0L))
                row("Native heap (proc)", sizeStr(procInfo.getMemoryStat("summary.native-heap")?.toLongOrNull()?.times(1024L) ?: 0L))
            }
            val fdCount = runCatching {
                File("/proc/self/fd").listFiles()?.size ?: 0
            }.getOrDefault(0)
            row("Open FDs", fdCount.toString())
            row("Threads",  Thread.activeCount().toString())
        }

        infoSection("Memory & CPU Usage") {
            // All metrics are process-attributable + readable WITHOUT any
            // privileged permission. JVM heap from Runtime; PSS from
            // Debug.MemoryInfo (Android's accounting for shared-page
            // proportional set size); RSS from /proc/self/statm (raw
            // kernel view). System totals come from ActivityManager.
            // CPU comes from /proc/self/stat (utime + stime in jiffies)
            // normalised against process wall-time for an avg %.
            val ctxAny = requireContext()
            val rt = Runtime.getRuntime()

            val heapMax   = rt.maxMemory()
            val heapTotal = rt.totalMemory()
            val heapFree  = rt.freeMemory()
            val heapUsed  = heapTotal - heapFree
            val heapPct   = if (heapMax > 0) (heapUsed * 100 / heapMax).toInt() else -1
            row("JVM heap used",
                "${sizeStr(heapUsed)} / ${sizeStr(heapMax)}" +
                    (if (heapPct >= 0) " ($heapPct%)" else ""))
            row("JVM heap total", sizeStr(heapTotal))
            row("JVM heap free",  sizeStr(heapFree))

            val mi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(mi)
            row("PSS total",     sizeStr(mi.totalPss.toLong()        * 1024L))
            row("PSS dalvik",    sizeStr(mi.dalvikPss.toLong()       * 1024L))
            row("PSS native",    sizeStr(mi.nativePss.toLong()       * 1024L))
            row("PSS other",     sizeStr(mi.otherPss.toLong()        * 1024L))
            row("Private dirty", sizeStr(mi.totalPrivateDirty.toLong() * 1024L))

            // RSS via /proc/self/statm field 2 (resident pages).
            val pageSize = runCatching {
                android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            }.getOrDefault(4096L)
            val rssBytes = runCatching {
                val parts = File("/proc/self/statm").readText().trim().split(' ')
                parts.getOrNull(1)?.toLongOrNull()?.let { it * pageSize } ?: -1L
            }.getOrDefault(-1L)
            row("RSS", if (rssBytes < 0) "—" else sizeStr(rssBytes))

            val am = ctxAny.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            val sysMi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(sysMi)
            row("System avail",      sizeStr(sysMi.availMem))
            row("System total",      sizeStr(sysMi.totalMem))
            row("System low-memory", sysMi.lowMemory.toString())
            row("Heap class limit",  am?.memoryClass?.let { "$it MB" } ?: "—")
            row("Heap class (large)",
                am?.largeMemoryClass?.let { "$it MB" } ?: "—")

            // CPU: utime (field 14) + stime (field 15) in jiffies. The
            // comm field is parenthesised and may contain spaces — strip
            // it before tokenising so field indices stay aligned.
            val statRaw = runCatching { File("/proc/self/stat").readText() }.getOrNull()
            val fields = statRaw?.let { raw ->
                val open  = raw.indexOf('(')
                val close = raw.lastIndexOf(')')
                if (open < 0 || close < 0 || close <= open) null
                else (raw.substring(0, open) + raw.substring(close + 1))
                    .trim().split(Regex("\\s+"))
            }
            val tck   = runCatching {
                android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
            }.getOrDefault(100L)
            val utime = fields?.getOrNull(13)?.toLongOrNull() ?: -1L
            val stime = fields?.getOrNull(14)?.toLongOrNull() ?: -1L
            val cpuJif = if (utime < 0 || stime < 0) -1L else utime + stime

            val uptimeMs = android.os.SystemClock.elapsedRealtime() -
                AppProcessUptime.startedAtElapsed
            val uptimeSec = uptimeMs / 1000.0

            row("CPU user-time",
                if (utime < 0 || tck <= 0) "—"
                else "%.2fs".format(utime.toDouble() / tck))
            row("CPU sys-time",
                if (stime < 0 || tck <= 0) "—"
                else "%.2fs".format(stime.toDouble() / tck))
            row("CPU total",
                if (cpuJif < 0 || tck <= 0) "—"
                else "%.2fs".format(cpuJif.toDouble() / tck))

            val cores = rt.availableProcessors()
            val avgPctSingle = if (cpuJif < 0 || uptimeSec <= 0 || tck <= 0) -1.0
                else (cpuJif.toDouble() / tck / uptimeSec) * 100.0
            val avgPctPerCore = if (avgPctSingle < 0 || cores <= 0) -1.0
                else avgPctSingle / cores
            row("CPU avg % (1-thread)",
                if (avgPctSingle < 0) "—" else "%.2f%%".format(avgPctSingle))
            row("CPU avg % (per-core)",
                if (avgPctPerCore < 0) "—" else "%.2f%%".format(avgPctPerCore))
            row("Cores online", cores.toString())

            // ── Phone storage ─────────────────────────────────────
            // StatFs against the data partition is the right anchor —
            // that's where the app, its caches, and DB live; "phone
            // storage" colloquially means user-data partition free space.
            // External / SD is separate but reported next to it so the
            // user has both numbers in one place.
            val dataDir = android.os.Environment.getDataDirectory().absolutePath
            val (dataFree, dataTotal) = statFsBytes(dataDir)
            val dataPct = if (dataTotal > 0) ((dataTotal - dataFree) * 100 / dataTotal).toInt() else -1
            row("Data free",
                "${sizeStr(dataFree)} / ${sizeStr(dataTotal)}" +
                    (if (dataPct >= 0) " (${100 - dataPct}% free)" else ""))

            val extDir = ctxAny.getExternalFilesDir(null)?.absolutePath
            if (extDir != null) {
                val (extFree, extTotal) = statFsBytes(extDir)
                row("External free",
                    "${sizeStr(extFree)} / ${sizeStr(extTotal)}")
            }

            // ── Phone swap (zRAM / disk) ──────────────────────────
            // /proc/meminfo SwapTotal + SwapFree are kB, space-padded.
            // Some kernels disable swap entirely → SwapTotal:0; report
            // "disabled" so the row isn't misread as "everything's used".
            val (swapTotal, swapFree) = readSwapBytes()
            val swapUsed = if (swapTotal < 0 || swapFree < 0) -1L else swapTotal - swapFree
            row("Swap total",
                if (swapTotal < 0) "—"
                else if (swapTotal == 0L) "disabled"
                else sizeStr(swapTotal))
            row("Swap free",
                if (swapFree < 0 || swapTotal == 0L) "—" else sizeStr(swapFree))
            row("Swap used",
                if (swapUsed < 0 || swapTotal == 0L) "—" else sizeStr(swapUsed))

            small("PSS = process-attributable RSS after sharing. JVM heap is the growable section of the Dalvik PSS bucket. CPU avg % is utime+stime / wall-time × 100 — 1-thread (100% = one core saturated) or per-core (100% = ALL cores saturated). Sustained > 50% with the screen off may indicate a background work leak. Storage = data partition (where the app + caches live). Swap is typically zRAM on Android — counts AGAINST physical RAM but appears as virtual.")
        }

        // (VPN / WireGuard / Mesh moved up under the "Cloud" macro header,
        //  rendered via renderVpnMesh — see the reorg right after the title.)

        // IPC Contract — the cross-app intent/IPC surface Cloud SuperApp
        // exposes/consumes (the constellation hub ↔ ea_cloud-comms /
        // ac_cloud-myterminal contract). Always rendered, even when nothing is
        // declared yet, so the surface is discoverable.

        infoSection("SYSFS-PROC") {
            small("Raw kernel-side telemetry — no runtime permission needed. World-readable /sys/class/* and /proc/* paths. Use this as the audit surface; promote interesting rows to dedicated UI elsewhere.")

            small("── /sys/class/power_supply/battery/")
            for ((k, v) in SysfsProc.battery()) row(k, v)

            small("── /sys/class/power_supply/{usb,ac,wireless,main}/")
            val chargerRows = SysfsProc.chargers()
            if (chargerRows.isEmpty()) small("(no charger nodes readable)")
            for ((k, v) in chargerRows) row(k, v)

            small("── /proc/loadavg + /proc/uptime  (CPU load in seconds)")
            for ((k, v) in SysfsProc.cpuLoadRows()) row(k, v)

            small("── /proc/stat  (cumulative jiffies → seconds, ALL cores)")
            for ((k, v) in SysfsProc.procStatRows()) row(k, v)

            small("── /sys/devices/system/cpu/cpu*/cpufreq/")
            for ((k, v) in SysfsProc.cpuFreqs()) row(k, v)

            small("── /sys/class/thermal/thermal_zone*/")
            val thermalRows = SysfsProc.thermal()
            if (thermalRows.isEmpty()) small("(no zones readable — vendor restriction)")
            for ((k, v) in thermalRows) row(k, v)

            small("── /proc/meminfo  (selected)")
            for ((k, v) in SysfsProc.memInfo()) row(k, v)

            small("── /sys/class/net/*/statistics/")
            for ((k, v) in SysfsProc.network()) row(k, v)

            small("── /proc/diskstats")
            val diskRows = SysfsProc.diskstats()
            if (diskRows.isEmpty()) small("(no diskstats readable)")
            for ((k, v) in diskRows) row(k, v)

            small("── /proc/self/  (this app's own kernel-side stats)")
            for ((k, v) in SysfsProc.selfProc()) row(k, v)
        }

        // ══ NETWORK macro section — connectivity, Wi-Fi, the IPC contract and the
        //    no-root per-app firewall.
        macro("🌐  NETWORK")

        infoSection("Network") {
            val cm = ctxAny().getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val active = cm?.activeNetwork
            val caps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
            val link = active?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

            val transports = mutableListOf<String>()
            caps?.let { c ->
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))      transports.add("Wi-Fi")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))  transports.add("Cellular")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))  transports.add("Ethernet")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN))       transports.add("VPN")
                if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("Bluetooth")
            }
            row("Transport",     if (transports.isEmpty()) "—" else transports.joinToString(", "))
            row("Validated",     caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: "—")
            row("Captive portal", caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)?.toString() ?: "—")
            row("Metered",       cm?.isActiveNetworkMetered?.toString() ?: "—")
            caps?.let { c ->
                row("Link down",     "${c.linkDownstreamBandwidthKbps} kbps")
                row("Link up",       "${c.linkUpstreamBandwidthKbps} kbps")
            }
            link?.let { lp ->
                row("Iface",  lp.interfaceName ?: "—")
                row("MTU",    lp.mtu.toString())
                row("DNS",    lp.dnsServers.joinToString(", ") { it.hostAddress ?: "" }.ifBlank { "—" })
                val v4 = lp.linkAddresses.mapNotNull { la -> la.address.hostAddress }.filter { ":" !in it }
                val v6 = lp.linkAddresses.mapNotNull { la -> la.address.hostAddress }.filter { ":" in it }
                row("IPv4",   v4.joinToString(", ").ifBlank { "—" })
                row("IPv6",   v6.joinToString(", ").ifBlank { "—" })
                val gw = lp.routes.firstOrNull { r -> r.isDefaultRoute }?.gateway?.hostAddress
                row("Default gw", gw ?: "—")
            }
            // Data-usage manager (:libs:datamanager DataUsageProvider over
            // NetworkStatsManager). Same "open the full data screen" button
            // shape the Firewall section uses. Per-app / per-transport /
            // per-period usage; gated on the SAME usage-access grant the
            // battery estimate and Phone smart folders already ask for, so
            // no new permission flow — the dialog deep-links to the same
            // settings screen Configs/About → Permissions opens.
            small("Per-app, per-network, per-period data usage — read live from NetworkStatsManager (needs Usage Access).")
            button("Data Usage", gray = true) {
                runCatching {
                    com.diegonmarcos.superapp.datamanager.DataUsageDialog()
                        .show(parentFragmentManager, com.diegonmarcos.superapp.datamanager.DataUsageDialog.TAG)
                }
            }
        }

        infoSection("Wi-Fi") {
            // SSID/BSSID require ACCESS_FINE_LOCATION on Android 10+
            // AND a connected Wi-Fi network; we already declare both.
            // If location isn't granted yet, SSID comes back as
            // "<unknown ssid>" — surface that as the user-facing hint.
            val wm = ctxAny().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wm == null || !wm.isWifiEnabled) {
                small("Wi-Fi is off.")
            } else {
                @Suppress("DEPRECATION")
                val info = runCatching { wm.connectionInfo }.getOrNull()
                if (info == null) {
                    small("No Wi-Fi connection info.")
                } else {
                    val ssid = info.ssid?.trim('"').orEmpty()
                    row("SSID",       if (ssid.isBlank() || ssid == "<unknown ssid>") "Needs location permission" else ssid)
                    row("BSSID",      info.bssid ?: "—")
                    row("RSSI",       "${info.rssi} dBm")
                    row("Link speed", "${info.linkSpeed} Mbps")
                    row("Tx speed",   "${info.txLinkSpeedMbps} Mbps")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        row("Rx speed", "${info.rxLinkSpeedMbps} Mbps")
                    }
                    row("Frequency",  "${info.frequency} MHz")
                    row("Hidden SSID", info.hiddenSSID.toString())
                }
            }
        }

        // getPackageInfo with GET_ACTIVITIES|GET_SERVICES|GET_PROVIDERS|
        // GET_RECEIVERS is a full manifest unmarshal across a binder call —
        // small on a fast phone, not free, and never worth a frame drop.
        asyncSection("IPC Contract",
            // Two halves, and the order matters. First the MESH — the peers this
            // app talks to, from build.json::ui.app_mesh, each resolved live. Then
            // what this app itself exports. Before the roster existed only the
            // second half was here, which is why the section read "nothing
            // declared" while eight channels were in daily use: a manifest can
            // only ever describe its own side of a binding.
            probe = { collectAppMesh(appCtx) to collectSelfIpcContract(appCtx) },
            render = { (mesh, self) ->
                if (mesh.isEmpty() && self.isEmpty()) {
                    small("No IPC contract declared yet — no exported/consumed cross-app intents, services, or providers beyond the framework defaults.")
                } else {
                    if (mesh.isNotEmpty()) {
                        small("Mesh — declared peers (ui.app_mesh), resolved on this device")
                        for ((k, v) in mesh) row(k, v)
                    }
                    if (self.isNotEmpty()) {
                        small("Exported by this app")
                        for ((k, v) in self) row(k, v)
                    }
                }
            })

        infoSection("Firewall") {
            // No-root per-app firewall (local VpnService, :libs:firewall).
            // Info rows read WITHOUT any privileged permission via
            // ConnectivityManager / Settings. The gray button opens Network ▸ Firewall, where the control
            // screen lives (master toggle + per-app preset picker). The firestack
            // merge (per-app filtering + WireGuard in one tunnel) is staged at
            // libs/firewall/phase3-firestack/ — staged, compiles only once
            // firestack.aar is built from libs/firewall/firestack/.
            val fw = com.diegonmarcos.superapp.firewall.FirewallInfo.read(ctx)
            row("State", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtState(fw))
            row("Apps with rules", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtBlocked(fw))
            row("Active transport", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtTransport(fw))
            row("System VPN", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtVpn(fw))
            row("Private DNS", com.diegonmarcos.superapp.firewall.FirewallInfo.fmtPrivateDns(fw))
            small("Read-only summary. Switch, VPN consent and per-app rules live in Network ▸ Firewall.")
            button("Manage in Network ▸ Firewall", gray = true) {
                (activity as? com.diegonmarcos.superapp.ShellActivity)?.openSectionPage("config", "firewall")
            }
        }

        // ══ API macro section — the on-device HTTP control surface (Dev
        //    Control) and the curl shortcuts that drive it. The SSH servers and
        //    the adb/Shizuku channels that used to share this section live in
        //    DEV TOOLS now: one heading, one question — "is the control plane
        //    up and what is its bearer".
        macro("🔌  API")

        infoSection("Dev Control HTTP API") {
            val prefs = DevControlPrefs(requireContext())
            val running = DevControlServer.isRunning()
            val bound   = DevControlServer.boundHost()
            val loopback = DevControlServer.isLoopbackOnly()
            row("Endpoint", "http://${bound ?: "127.0.0.1"}:${prefs.port}")
            row("Status",   if (running) "Running" else "Stopped")
            row("Bound to", bound ?: "—")
            row("Bind scope", when {
                !running       -> "—"
                loopback       -> "✓ Loopback only (127.0.0.1) — unreachable from LAN"
                else           -> "✗ Reachable from LAN — bind addr ${bound ?: "?"}"
            })
            row("Token",    prefs.token)
            small("Bearer token — long-press to copy. DECLARATIVE: baked from the vault sops secret (BuildConfig.FLEET_TOKEN), the same value cloud-superapp-mcp is configured with, so it is stable across installs and there is nothing to regenerate. Endpoints follow /api/{group}/{op}. Full catalog: GET /api/docs.")

            // Toggle Switch: persists pref + start/stop the server live.
            toggle("API enabled", prefs.enabled) { checked ->
                    prefs.enabled = checked
                    if (checked) {
                        DevControlServer.start(requireContext().applicationContext)
                    } else {
                        DevControlServer.stop()
                    }
                    // Re-render so Status/Bound/scope reflect the new state.
                    reload()
            }

            // Regenerate REMOVED: the token is now declarative (BuildConfig.FLEET_TOKEN
            // from the vault sops secret, seeded in App.onCreate). Rotating it on one
            // device would only split the fleet from the value the MCP and every other
            // member hold; rotation, if ever needed, is a vault secret change + rebuild.
        }

        infoSection("Curl shortcuts") {
            val port = DevControlPrefs(requireContext()).port
            val tok  = DevControlPrefs(requireContext()).token
            // Endpoint catalog moved to /api/{group}/{op} layout in
            // commit fb2bc62; the flat aliases still resolve but the
            // documented form is the grouped one. Source of truth for
            // the full list is GET /api/docs (machine-readable JSON,
            // generated from the same Spec list the routing uses so it
            // can't drift). These shortcuts cover the most-used probes.
            row("Docs",     "curl http://127.0.0.1:$port/api/docs")
            row("Logcat",   "curl http://127.0.0.1:$port/api/diagnostics/logcat?n=500")
            row("Trace",    "curl http://127.0.0.1:$port/api/diagnostics/trace")
            row("Crashes",  "curl http://127.0.0.1:$port/api/diagnostics/crashes")
            row("Bundle",   "curl http://127.0.0.1:$port/api/diagnostics/bundle")
            row("Download logs", "curl http://127.0.0.1:$port/api/diagnostics/download")
            row("Send logs → cloud", "curl http://127.0.0.1:$port/api/diagnostics/push")
            row("Info",     "curl http://127.0.0.1:$port/api/system/info")
            row("State",    "curl -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/api/state")
            row("Haptic",   "curl -XPOST -H 'Authorization: Bearer $tok' 'http://127.0.0.1:$port/api/haptic?preset=gemini_stream'")
            row("Update",   "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/api/system/update")
            row("Restart",  "curl -XPOST -H 'Authorization: Bearer $tok' http://127.0.0.1:$port/api/system/restart")
            row("Tracker",  "curl http://127.0.0.1:$port/api/tracker/counts")
        }

        // ══ MESH macro section — this device's place in the WireGuard mesh:
        //    tunnel state, peers, and whether the fleet is reachable right now.
        //    Split out of the identity block because "who owns this phone" and
        //    "can it reach the fleet" are different questions, and in an outage
        //    only the second one is ever asked.
        macro("🕸️  MESH")

        infoSection("VPN / WireGuard / Mesh") { renderVpnMesh() }

        // ══ DEV TOOLS macro section — the debugging channels that are NOT
        //    the HTTP API: the terminal apps' sshd, Wireless Debugging, the
        //    embedded adb pairing walkthrough and Shizuku. Split out of the old
        //    combined section so API above answers "is the control plane up"
        //    and this one answers "how do I get a shell on this phone".
        macro("🛠️  DEV TOOLS")

        infoSection("SSH servers (Termux & co.)") {
            // ── SSH (Termux & co.) — detect installed terminal emulators and
            //    whether each is running an sshd we can reach on localhost.
            //    Installed-check is synchronous (PackageManager); the port
            //    probe is async (no network on the main thread).
            small("SSH servers on this device — terminal apps + whether their sshd is listening on localhost (probes 127.0.0.1 across common ports).")
            for (term in SSH_TERMINALS) {
                val installed = isPackageInstalled(ctx, term.pkg)
                val portList = term.ports.joinToString(",")
                val valueView = row(term.label,
                    if (!installed) "not installed" else "installed · probing :$portList…")
                if (installed) launch {
                    val open = withContext(Dispatchers.IO) { term.ports.filter { p -> portOpen("127.0.0.1", p) } }
                    if (open.isNotEmpty()) {
                        valueView.text = "installed · sshd ✓ up :${open.joinToString(",")}"
                        valueView.color = Color(0xFF8BE9A0)
                    } else {
                        valueView.text = "installed · sshd ✗ down (tried :$portList)"
                        valueView.color = Color(0xFFFFB199)
                    }
                }
            }
        }

        infoSection("Self-contained ADB, W-less Debuging & Shizuku") {
            // The token is read here too: the pairing walkthrough below prints
            // the exact curl the user has to run, and a walkthrough with the
            // bearer left as a placeholder is a walkthrough nobody can follow.
            val prefs = DevControlPrefs(requireContext())
            small("Self-contained ADB — enable Wireless Debugging, then pair via /api/adb/pair + /api/adb/connect:")
            button("ADB Shell ›", gray = true) { com.diegonmarcos.superapp.adbdebug.AdbShellLink.open(ctxAny()) }
            // ── Self-contained ADB how-to (embedded adb client, no Shizuku
            //    app, no PC; works with WireGuard ON). Long-press any row to
            //    copy. Documented here next to the button that enables it.
            val adbPort = DevControlPrefs(requireContext()).port
            val adbTok  = prefs.token
            small("Self-contained ADB (embedded libadb — no Shizuku app, no PC, works with WireGuard ON). Steps:")
            small("1) Open ADB Shell and press Connect (or turn Wireless Debugging ON by hand). 2) /api/adb/pair posts the pairing notification. 3) 'Pair device with pairing code' → type the 6-digit code into the notification (or pass code=). IP + ports are discovered over mDNS.")
            small("⚠ HOST GOTCHA: use your Wi-Fi LAN IP (e.g. 192.168.x.x), NOT the 10.x the dialog shows — that 10.x is the WireGuard tun0 and gets ECONNREFUSED. Find the real wlan0 IP with /api/adb/netinfo.")
            row("0 NetInfo",  "curl -H 'Authorization: Bearer $adbTok' http://127.0.0.1:$adbPort/api/adb/netinfo   # find wlan0 IPv4")
            row("1 Pair",     "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/pair'   # optional &code=<6digits>; port via mDNS")
            row("2 Connect",  "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/connect?host=<wlan-ip>&port=<connectport>'")
            row("3 Status",   "curl -H 'Authorization: Bearer $adbTok' http://127.0.0.1:$adbPort/api/adb/status   # embedded-adb ready=true")
            row("4 Charger",  "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/diagnostics?bundle=charger'")
            row("Bundles",    "charger | battery | usb | thermal | pd   (build.json::shizuku_diagnostics)")
            row("Exec",       "curl -H 'Authorization: Bearer $adbTok' 'http://127.0.0.1:$adbPort/api/adb/exec?cmd=dumpsys%20battery'")
            small("Pairing/connect bind the socket to the Wi-Fi Network so it bypasses wg0. Pairing is once per boot (Android law; only root removes it). Pair port lives only while the pairing dialog is open — keep it open until step 1 returns ok.")
        }

        // ══ WEBSERVER macro section — a full static HTTP server living INSIDE
        //    this app. The ENGINE is libs:webserver (CloudWebServer / WebServer /
        //    WebServerPrefs); everything below is only the surface that drives
        //    it. That split is what lets a standalone Cloud WebServer APK take
        //    the same socket implementation later without carrying this screen,
        //    and it is why nothing in this file parses HTTP itself.
        macro("🌐  WEBSERVER")

        infoSection("Web server") {
            val webPrefs = WebServerPrefs(appCtx)
            val web = CloudWebServer.stats(appCtx)
            row("Status",        if (web.running) "Running" else "Stopped")
            row("URL",           CloudWebServer.reachableUrl(appCtx))
            row("Bound to",      web.boundHost ?: "—")
            row("Bind scope", when {
                !web.running     -> "—"
                web.loopbackOnly -> "✓ Loopback only (127.0.0.1) — unreachable from LAN"
                else             -> "✗ Reachable from the Wi-Fi LAN and the mesh — bound to every interface"
            })
            row("Port",          webPrefs.port.toString())
            row("Document root", web.docRoot)
            row("Files in root", web.filesInRoot.toString())
            row("Directory listing", if (webPrefs.directoryListing) "on" else "off")
            row("Uptime",        if (web.running) fmtDuration(web.uptimeMillis) else "—")
            row("Requests",      web.requests.toString())
            row("Bytes served",  sizeStr(web.bytesServed))
            // #281's rule applied to this surface: a server that FAILED TO BIND
            // must never read the same as one that is merely switched off. The
            // engine records the bind error rather than throwing, and this row
            // is where that error becomes visible.
            row("Last error",    web.lastError ?: "none")
            small("Self-contained static HTTP server — engine libs:webserver. " +
                "Serves the document root above with MIME typing, index.html, optional directory " +
                "listings and a path-traversal containment check against the canonical root. " +
                "GET /__status returns these same counters as JSON.")

            // Master switch. Default OFF: opening a listening socket is never
            // something the app should decide on the user's behalf.
            toggle("Web server enabled", webPrefs.enabled) { checked ->
                    webPrefs.enabled = checked
                    if (checked) CloudWebServer.start(appCtx) else CloudWebServer.stop(appCtx)
                    reload()
            }
            toggle("Reachable from LAN and mesh (off = loopback only)", !webPrefs.loopbackOnly) { checked ->
                    webPrefs.loopbackOnly = !checked
                    CloudWebServer.refresh(appCtx)
                    reload()
            }
            toggle("Directory listing for folders with no index.html", webPrefs.directoryListing) { checked ->
                    webPrefs.directoryListing = checked
                    CloudWebServer.refresh(appCtx)
            }

            button("Open in browser") {
                openInAppUrl(CloudWebServer.reachableUrl(appCtx))
            }
            button("Open /__status", gray = true) {
                openInAppUrl(CloudWebServer.reachableUrl(appCtx) + "__status")
            }
            button("Copy URL", gray = true) {
                copy(ctx, CloudWebServer.reachableUrl(appCtx))
            }
            button("Change port", gray = true) { editWebServerPort(ctx) { reload() } }
            button("Change document root", gray = true) { editWebServerDocRoot(ctx) { reload() } }
            button("Refresh", gray = true) { reload() }

            // The request log is the difference between "the switch says
            // Running" and "something actually talked to it".
            small("Recent requests (newest first):")
            if (web.recent.isEmpty()) row("—", "no requests served yet")
            else web.recent.forEachIndexed { index, entry -> row((index + 1).toString(), entry) }
        }

        // ══ DIAGNOSTICS macro section — folded in from the removed Configs ▸ Observability ▸ Diagnostics page: the device's
        //    own numbers (gesture-nav mode and insets, edge service + strip rects + last touch events, measured Configs
        //    tiles). The same Diagnostics.collect/text the debug API's diagnostics/get serves.
        macro("🩺  DIAGNOSTICS")

        infoSection("Gestures, edge strips & tiles") {
            Diagnostics.text(Diagnostics.collect(ctx)).lines().filter { it.isNotBlank() }.forEach { small(it) }
            button("Refresh", gray = true) { reload() }
        }

        // ══ AGENTS macro section — folded in from the removed Configs ▸ Observability ▸ Agents page: every agent the
        //    fleet declares (data/fleet-agents.json, baked) with its live status, read only through local channels.
        macro("🤖  AGENTS")

        for ((head, scope) in listOf("SuperApp agents" to FleetAgents.SCOPE_SUPERAPP, "Fleet agents" to FleetAgents.SCOPE_FLEET)) {
            asyncSection(head, timeoutMs = 10_000,
                probe = { FleetAgents.forScope(FleetAgents.all(), scope).map { it to AgentStatusReader.read(appCtx, it) } },
            ) { agents ->
                if (agents.isEmpty()) row("—", "no agent declared")
                agents.forEach { (a, live) -> row(a.name, "${live.word} · ${a.app} · ${a.kind}") }
            }
        }

        // The Copy All button closes the last group; "Go Back Up to Index" links are derived (AboutPage.withBackLinks).
        copyAllButton()
    }

    // ── helpers ──────────────────────────────────────────────────────

    // Known on-device terminal emulators that can run an sshd. The sshd port is
    // user-configurable, so each probes a list of common candidates (8022 is the
    // Termux/nix default; 8024 and 22 are common manual choices).
    private data class SshTerminal(val label: String, val pkg: String, val ports: List<Int>)
    private val SSH_TERMINALS = listOf(
        SshTerminal("Termux", "com.termux", listOf(8022, 8024, 22)),
        SshTerminal("Nix-on-droid", "com.termux.nix", listOf(8022, 8024, 22)),
        SshTerminal("Termux (F-Droid)", "com.termux.fdroid", listOf(8022, 8024, 22)),
        SshTerminal("UserLAnd", "tech.ula", listOf(2022, 8022, 22)),
    )

    /**
     * Open a URL on the app's OWN browser surface instead of handing it to an
     * external one. #156: an action on an About row must not eject the user
     * out of the app. The launcher Activity implements the tile-click
     * contract and resolves a bare http(s) target to the embedded browser; a
     * host that does not is the only case that falls through to the system.
     */
    private fun openInAppUrl(url: String) {
        val host = activity as? com.diegonmarcos.superapp.launcher.TileGridFragment.TileClickListener
        if (host != null) {
            host.onTileClicked(url)
            return
        }
        runCatching {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                )
            )
        }
    }

    /** Port editor for the WEBSERVER section. Rebinds on save — a port row that changed without the socket moving would be a
     *  lie. [reload] re-reads the block so the Port / Bound-to rows say what is true now. */
    private fun editWebServerPort(ctx: Context, reload: () -> Unit) {
        val prefs = WebServerPrefs(ctx.applicationContext)
        prompt = AboutPrompt(
            title = "Web server port", message = "1024-65535. The server rebinds immediately.",
            initial = prefs.port.toString(), numeric = true,
            onSave = { text ->
                val value = text.trim().toIntOrNull()
                if (value == null || value !in 1024..65535) {
                    Toast.makeText(ctx, "Port must be 1024-65535", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.port = value
                    CloudWebServer.refresh(ctx.applicationContext)
                    reload()
                }
            },
        )
    }

    /** Document-root editor for the WEBSERVER section. */
    private fun editWebServerDocRoot(ctx: Context, reload: () -> Unit) {
        val prefs = WebServerPrefs(ctx.applicationContext)
        prompt = AboutPrompt(
            title = "Document root", message = "Absolute path of the directory served as /. Created if missing.",
            initial = prefs.docRootPath, numeric = false,
            neutralLabel = "Default",
            onNeutral = {
                prefs.docRootPath = prefs.defaultDocRoot().absolutePath
                CloudWebServer.refresh(ctx.applicationContext)
                reload()
            },
            onSave = { text ->
                val value = text.trim()
                if (value.isBlank()) {
                    Toast.makeText(ctx, "Path is empty", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.docRootPath = value
                    CloudWebServer.refresh(ctx.applicationContext)
                    reload()
                }
            },
        )
    }

    private fun isPackageInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    /** Is something listening on host:port? Short-timeout TCP connect.
     *  MUST run off the main thread (Android blocks network on the UI thread). */
    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 350); true }
    }.getOrDefault(false)

    /** Everything the Storage section paints, gathered in one off-thread pass
     *  so the two recursive dirSize() walks never touch the main thread. */
    private data class StorageFacts(
        val filesDir: String,
        val cacheDir: String,
        val dataRoot: String,
        val external: String,
        val traceLog: String,
        val apk: String,
        val data: String,
        val cache: String,
        val total: String,
    )

    private fun copy(ctx: Context, v: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("about", v))
    }

    /** Deep dump: every raw BatteryManager property and sticky-intent extra exactly as the device reports it (no scaling, no
     *  sign, no arithmetic), read by the battery SoT itself (BatteryRepository.rawDump) — the computed values are the SoT
     *  rows above. */
    private fun AboutItems.renderBatteryDeep() {
        val dump = runCatching { com.diegonmarcos.superapp.battery.BatteryRepository.rawDump(ctxAny()) }.getOrDefault(emptyList())
        for ((k, v) in dump) row(k, v ?: "—")
    }

    /**
     * The APK's own address, and the button that installs it.
     *
     * ## Why this belongs next to the repo links
     * The repo rows answer "where does this come from". They do not answer
     * "how do I get a working copy", and the two get confused precisely when
     * it matters — the user whose update chain is broken is the one reading
     * this screen, and a GitHub source link hands them a tree they cannot
     * build on a phone. The artifact URL is the actionable half of the same
     * fact and it was simply missing.
     *
     * ## Why the URL is derived, not written down
     * It comes from the fleet manifest ([Fleet]) that Constellation already
     * installs from, so About cannot drift away from what the updater
     * actually fetches. The ABI suffix is applied on top: this constellation
     * publishes an `-x86_64` variant beside the default, and a hardcoded arm64
     * link is a link that installs the wrong architecture on every emulator
     * and x86 tablet in the fleet — a failure that looks like a corrupt
     * download rather than a wrong URL.
     */
    private fun AboutItems.renderDirectInstall() {
        val me = Fleet.parse(UpdBuildConfig.CONSTELLATION_FLEET_B64)
            .firstOrNull { it.pkg == BuildConfig.APPLICATION_ID }

        // Fleet.App.abiReleaseUrl already answers this, so ask it.
        //
        // This used to DERIVE the filename: take the GHCR tag suffix and splice
        // it into the asset name ("Cloud-SuperApp.apk" + "-x86_64"). That is a
        // guess about how release assets are named, and it was a second ABI
        // mechanism sitting beside the real one — it happened to agree for this
        // app only because the naming convention happened to match. The asset
        // names are DATA (build.json::release.variants[].gh_asset, which is also
        // what CI publishes under), so the resolver reads them instead of
        // reconstructing them, and a variant whose asset does not follow the tag
        // convention can no longer produce a confidently-wrong URL.
        val apkUrl = me?.abiReleaseUrl?.takeIf { it.isNotBlank() }
            ?: "https://github.com/diegonmarcos/cloudy-u-android/releases/download/latest/Cloud-SuperApp.apk"

        row("APK", apkUrl)
        // The sidecar is what makes the URL trustworthy rather than merely
        // convenient, so it is shown rather than silently used.
        row("Checksum", "$apkUrl.sha256")
        me?.repoUrl?.takeIf { it.isNotBlank() }?.let { row("Release repo", it) }

        button(getString(R.string.about_install_action)) {
            // Straight into the same recovery flow the advisory feed opens:
            // download → verify sha256 → system installer. Opening the URL in
            // a browser instead would drop the verification step, which is the
            // only reason to prefer this over telling the user to search GitHub.
            runCatching {
                startActivity(
                    com.diegonmarcos.superapp.recovery.RecoveryActivity.intent(ctxAny(), me?.id)
                )
            }
        }
    }

    /** Repo {label,url} list for the Profile section — data-driven from
     *  build.json::ui.profile_default.repos (UI_PROFILE_REPOS_B64). */
    private fun parseProfileRepos(): List<Pair<String, String>> = runCatching {
        val json = String(android.util.Base64.decode(BuildConfig.UI_PROFILE_REPOS_B64, android.util.Base64.NO_WRAP))
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optString("label").ifBlank { "repo" } to o.optString("url")
        }
    }.getOrDefault(emptyList())

    /** VPN / WireGuard / Mesh body — the WG interface state PLUS a live
     *  reachability ping of EVERY declared mesh member (Sections.mesh()
     *  nodes, baked from the consolidated cloud-data config), not just
     *  the configured WG peers. Live rx/tx + handshake attach to a node
     *  when its wg-IP matches a configured peer's tunnel and the tunnel
     *  is UP. Rendered inside a infoSection() under the Cloud macro header. */
    private fun AboutItems.renderVpnMesh() {
        val ctx = ctxAny()
        val wgPrefs = WgState.prefs(ctx)
        val backend = runCatching { WgState.backend(ctx) }.getOrNull()
        val tunnel  = WgState.tunnel
        val state   = runCatching { backend?.getState(tunnel) }.getOrNull()
        row("Tunnel name", wgPrefs.tunnelName.ifBlank { "—" })
        row("State",       state?.name ?: "—")
        row("Backend",     "libwg-go ${runCatching { backend?.version }.getOrNull() ?: "—"}")
        row("Always-on",   runCatching { backend?.isAlwaysOn?.toString() }.getOrNull() ?: "—")
        row("Lockdown",    runCatching { backend?.isLockdownEnabled?.toString() }.getOrNull() ?: "—")
        row("Configured peers", wgPrefs.peers().size.toString())

        // Live per-peer stats keyed by pubkey (only when tunnel UP), then
        // re-keyed by the peer's mesh IP so we can attach to mesh nodes.
        val statsByKey = HashMap<String, com.wireguard.android.backend.Statistics.PeerStats?>()
        if (state == Tunnel.State.UP) {
            runCatching { backend?.getStatistics(tunnel) }.getOrNull()?.let { stats ->
                for (key in stats.peers()) {
                    val k64 = runCatching { key.toBase64() }.getOrDefault("")
                    if (k64.isNotEmpty()) statsByKey[k64] = stats.peer(key)
                }
            }
        }
        val statsByMeshIp = HashMap<String, com.wireguard.android.backend.Statistics.PeerStats>()
        for (p in wgPrefs.peers()) {
            val ip = AboutFormat.meshIpOf(p.allowedIps, p.endpoint) ?: continue
            statsByKey[p.publicKey.trim()]?.let { statsByMeshIp[ip] = it }
        }

        // FULL mesh — every declared member from the baked consolidated config.
        val nodes = runCatching { com.diegonmarcos.superapp.launcher.Sections.mesh().nodes }.getOrDefault(emptyList())
        small("Full mesh — ${nodes.size} declared members (consolidated config). Live wg0 reachability ping:")
        val pingTargets = ArrayList<Pair<String, AboutItem.Row>>()
        for (n in nodes) {
            val name = "${n.alias.ifBlank { n.name }} · ${n.role}"
            small("— $name —")
            row("$name · wg ip", n.wgIp.ifBlank { "—" })
            if (n.publicIp.isNotBlank()) row("$name · public ip", n.publicIp)
            val provReg = listOf(n.provider, n.region).filter { it.isNotBlank() }.joinToString(" · ")
            if (provReg.isNotBlank()) row("$name · provider", provReg)
            statsByMeshIp[n.wgIp]?.let { ps ->
                val hsAge = if (ps.latestHandshakeEpochMillis > 0)
                    fmtDuration(System.currentTimeMillis() - ps.latestHandshakeEpochMillis) + " ago" else "never"
                row("$name · rx/tx",     "${sizeStr(ps.rxBytes)} / ${sizeStr(ps.txBytes)}")
                row("$name · handshake", hsAge)
            }
            if (n.wgIp.isNotBlank()) {
                val pingRow = row("$name · ping ${n.wgIp}", "pinging…")
                pingTargets.add(n.wgIp to pingRow)
            }
        }
        if (nodes.isEmpty()) small("No mesh members in the baked consolidated config (data/mesh.json).")

        if (pingTargets.isNotEmpty()) {
            launch {
                for ((ip, view) in pingTargets) {
                    view.text = withContext(Dispatchers.IO) { pingHost(ip, 1500) }
                }
            }
        }
        button("Re-ping all mesh members") { reload() }
    }

    /** Reachability probe for [ip] — ICMP echo where allowed, else a TCP
     *  echo handshake (InetAddress.isReachable's fallback). Returns a
     *  ✓ latency / ✗ timeout string. Call OFF the main thread. */
    private fun pingHost(ip: String, timeoutMs: Int): String = try {
        val addr = java.net.InetAddress.getByName(ip)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val ok = addr.isReachable(timeoutMs)
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        AboutFormat.pingVerdict(ok, dt, timeoutMs)
    } catch (e: Exception) {
        "✗ ${e.message ?: "unreachable"}"
    }

    /** The inter-app mesh — build.json::ui.app_mesh resolved against THIS
     *  device, one row per declared channel.
     *
     *  Why this is not derived: [collectSelfIpcContract] below can only read
     *  our own manifest, so it answers "what do I expose" and can never
     *  answer "who are my peers". The peer list lives in the libraries that
     *  bind each channel (TextTools.SERVICE_PACKAGES, FleetToken.AUTHORITY_PKG,
     *  …) as deliberately hard-coded constellation ADDRESSES, and a shared
     *  library cannot read it from build.json because it would read the
     *  CONSUMING app's build.json. So the roster is declared once here, in the
     *  app that owns the About screen.
     *
     *  The verdict per server is the part the declaration cannot hold: absent
     *  (not installed) vs installed-but-not-publishing vs live. That third
     *  state is the whole point — a preferred peer that ships no service looks
     *  exactly like a broken feature otherwise (Cloud Writer today). */
    private fun collectAppMesh(ctx: Context): List<Pair<String, String>> = runCatching {
        val json = String(
            android.util.Base64.decode(BuildConfig.UI_APP_MESH_B64, android.util.Base64.DEFAULT),
            Charsets.UTF_8,
        )
        val arr = org.json.JSONArray(json)
        val pm = ctx.packageManager
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            val isProvider = entry.optString("kind") == "provider"
            // A $pkg placeholder resolves per server: the two terminal apps
            // publish the same channel under their own package names.
            val channel = (if (isProvider) entry.optString("authority") else entry.optString("action"))
            val servers = entry.optJSONArray("serves")
            val verdicts = ArrayList<String>()
            for (j in 0 until (servers?.length() ?: 0)) {
                val pkg = servers!!.getString(j)
                val name = channel.replace("\$pkg", pkg)
                val short = pkg.substringAfterLast('.')
                val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
                val publishes = when {
                    !installed  -> false
                    isProvider  -> pm.resolveContentProvider(name, 0) != null
                    else        -> pm.queryIntentServices(
                        android.content.Intent(name).setPackage(pkg), 0,
                    ).isNotEmpty()
                }
                verdicts.add(when {
                    !installed -> "$short ✗ absent"
                    publishes  -> "$short ✓ live"
                    else       -> "$short — installed, channel not published"
                })
            }
            val head = entry.optString("label")
            val kind = if (isProvider) "provider" else "service"
            out.add(
                "$head ($kind)" to
                    (if (verdicts.isEmpty()) "no server declared" else verdicts.joinToString(" · ")),
            )
        }
        out
    }.getOrDefault(emptyList())

    /** Cross-app IPC surface this app exposes/consumes — the manifest's
     *  exported activities/services/providers/receivers with intent
     *  filters (the contract other apps in the constellation can bind
     *  to). Empty list → nothing declared (the section shows a note). */
    private fun collectSelfIpcContract(ctx: Context): List<Pair<String, String>> = runCatching {
        val pm = ctx.packageManager
        val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
            android.content.pm.PackageManager.GET_SERVICES or
            android.content.pm.PackageManager.GET_PROVIDERS or
            android.content.pm.PackageManager.GET_RECEIVERS
        val pi = pm.getPackageInfo(ctx.packageName, flags)
        val out = ArrayList<Pair<String, String>>()
        fun shortName(n: String) = n.removePrefix(ctx.packageName).removePrefix(".")
        pi.activities?.filter { it.exported }?.forEach { out.add("Activity (exported)" to shortName(it.name)) }
        pi.services?.filter { it.exported }?.forEach { out.add("Service (exported)" to shortName(it.name)) }
        pi.providers?.filter { it.exported }?.forEach { out.add("Provider (exported)" to (it.authority ?: shortName(it.name))) }
        pi.receivers?.filter { it.exported }?.forEach { out.add("Receiver (exported)" to shortName(it.name)) }
        out
    }.getOrDefault(emptyList())

    private fun dirSize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)


    /** (free, total) bytes for the filesystem hosting [path]. Returns
     *  (-1, -1) when StatFs throws — happens on early-API or unmounted
     *  external paths. */
    private fun statFsBytes(path: String): Pair<Long, Long> = runCatching {
        val s = android.os.StatFs(path)
        s.availableBytes to s.totalBytes
    }.getOrDefault(-1L to -1L)

    /** (swapTotalBytes, swapFreeBytes) from /proc/meminfo. -1L on parse
     *  failure; 0L SwapTotal means the kernel has no swap configured
     *  (caller should render "disabled"). Values in /proc/meminfo are
     *  in kB — multiplied to bytes here so callers stay in sizeStr units. */
    private fun readSwapBytes(): Pair<Long, Long> = runCatching {
        val text = File("/proc/meminfo").readText()
        fun kb(field: String): Long {
            val m = Regex("(?m)^${field}:\\s+(\\d+)\\s+kB").find(text)
                ?: return -1L
            return m.groupValues[1].toLong() * 1024L
        }
        kb("SwapTotal") to kb("SwapFree")
    }.getOrDefault(-1L to -1L)

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_MACROS = "macros"

        /** The macro groups Configs ▸ About draws: this app's identity and where the build came from. */
        val ABOUT_MACROS = arrayListOf("APP & BUILD", "REPO & RELEASES")

        fun newInstance(title: String = "X-Ray", macros: ArrayList<String>? = null) = DevControlFragment().apply {
            arguments = android.os.Bundle().apply { putString(ARG_TITLE, title); macros?.let { putStringArrayList(ARG_MACROS, it) } }
        }

        /** The page's own two heading rows (title, Index) before the first block. */
        private const val HEAD_ITEMS = 2
    }
}
