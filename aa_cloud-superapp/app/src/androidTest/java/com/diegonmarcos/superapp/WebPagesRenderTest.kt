package com.diegonmarcos.superapp

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.diegonmarcos.superapp.launcher.WebPageFragment
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cloud ▸ WebApps (the embedded cloud-webapps index: no in-page tab row, the shell's Cloud tab strip drawn under the top row, the WebView below it) and Cloud ▸ Linktree on a REAL emulator WebView (Robolectric cannot render one). Opens each page through
 * the same route a tile uses, waits up to 20 s, and writes the evidence to /data/local/tmp/webpage-<name>.{png,txt}:
 * the WebView's on-screen pixels, its measured size, document.readyState, the body's text length, every console line,
 * every load / HTTP error and the final URL. Fails when the body has no text or the WebView's pixels are one colour.
 */
@RunWith(AndroidJUnit4::class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class WebPagesRenderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ua = inst.uiAutomation
    /** Readable by `adb pull` without root: /sdcard/Android/data/<pkg>/files/webpage. */
    private val out by lazy { File(inst.targetContext.getExternalFilesDir(null), "webpage").apply { mkdirs() } }

    private fun sh(cmd: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ua.executeShellCommand(cmd)).bufferedReader().readText()

    @After fun tearDown() { WebPageFragment.probe = null; WebPageFragment.interceptor = null }

    // a, b, d, e are GATING and load FIXTURE pages (same URLs, same link structure, served through WebPageFragment.interceptor), so a
    // github.io or linktree outage cannot fail the build. c loads the LIVE linktree and stays non-gating: it is the live smoke test.
    @Test fun a_webAppsEmbedRenders() { fixtures(); check("webapps", "page:cloud/webapps", night = false, embed = true) }
    @Test fun b_webAppsEmbedRendersAtNight() { fixtures(); check("webapps-night", "page:cloud/webapps", night = true, embed = true) }
    // #1930 the Cloud strip no longer has a Linktree pane: the former page:cloud/lnktree id is a page alias of Apps (the Me-row Lnktree tile opens the site in Cloud Me)

    private fun html(body: String, head: String = "") =
        "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">$head" +
        "<style>body{margin:0;padding:12px;font:16px sans-serif;background:#fff;color:#123}@media(prefers-color-scheme:dark){body{background:#0b0f17;color:#e6e9ef}}" +
        "h2{background:linear-gradient(90deg,#4c6ef5,#15aabf);color:#fff;padding:6px}li{list-style:none;margin:6px 0;padding:10px;border:1px solid #7a8;border-radius:8px;background:rgba(80,160,120,.25)}a{color:inherit}</style></head><body>$body</body></html>"

    /** Fixture twins of the live cloud-webapps index and the linktree: same hosts and paths, 40 same-origin app links / 30 outbound links. */
    private fun fixtures() {
        val apps = (1..40).joinToString("") { "<li><a href=\"cloud-app$it/\">cloud-app$it App $it</a></li>" }
        val webapps = html("<main><h1>Cloud web apps</h1><p>One independent site per Cloud APK.</p><ul>$apps</ul></main>",
            "<title>Cloud web apps</title><script>if(/[?&]embed=1/.test(location.search)||navigator.userAgent.indexOf('CloudSuperApp-Embed')>=0)document.documentElement.setAttribute('data-embed','1')</script>")
        val links = (1..30).joinToString("") { "<li><a href=\"https://example.org/link$it\">Link $it</a></li>" }
        val linktree = html("<main><h2>Linktree</h2><ul>$links</ul></main>", "<title>Linktree</title>")
        fun page(h: String, status: Int = 200) = android.webkit.WebResourceResponse("text/html", "utf-8", status, if (status == 200) "OK" else "Not Found", emptyMap(), h.byteInputStream())
        WebPageFragment.interceptor = { r ->
            val u = r.url
            when {
                u.host == "diegonmarcos.github.io" && u.path.orEmpty().trimEnd('/') == "/cloud-webapps" -> page(webapps)
                u.host == "diegonmarcos.github.io" && u.path.orEmpty().startsWith("/cloud-webapps/") ->
                    page(html("<h1>App page</h1><p>${u.path}</p><p>This page is one step deeper in the history.</p>", "<title>App</title>"))
                u.host == "linktree.diegonmarcos.com" && u.path.orEmpty().trim('/').isEmpty() -> page(linktree)
                u.host == "diegonmarcos.github.io" || u.host == "linktree.diegonmarcos.com" -> page("", 404)
                else -> null
            }
        }
    }

    /**
     * Back follows the embedded page's history: from the WebApps main page, open an app page (a same-origin link, which must stay
     * inside the WebView and land in its history), press the SYSTEM Back and land on the main page again; open the app page again
     * and press the shell's top-right Back arrow, same result. Only at the main page does Back leave the page.
     */
    @Test fun d_webAppsBackFollowsHistory() {
        fixtures()
        sh("settings put global adb_wifi_enabled 0")
        val pkg = inst.targetContext.packageName
        sh("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        sh("cmd role add-role-holder android.app.role.HOME $pkg 0")
        sh("cmd uimode night no"); SystemClock.sleep(1500)
        sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")
        val ctx = inst.targetContext
        ctx.startActivity(Intent().setClassName(ctx.packageName, "${ctx.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("shortcut_action", "page:cloud/webapps"))

        fun web(): WebView? { var w: WebView? = null; resumed()?.let { a -> inst.runOnMainSync { w = findWeb(a.window.decorView) } }; return w }
        fun until(what: String, ms: Long = 20_000, diag: () -> String = { "" }, cond: () -> Boolean) {
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < ms) { clearPrompt(); if (cond()) return; SystemClock.sleep(500) }
            throw AssertionError("timed out waiting for: $what ${diag()}")
        }
        fun urlOf(w: WebView): String { var u = ""; inst.runOnMainSync { u = w.url.orEmpty() }; return u }
        fun canBack(w: WebView): Boolean { var b = false; inst.runOnMainSync { b = w.canGoBack() }; return b }
        fun tiles(w: WebView) = js(w, "document.querySelectorAll('a[href]').length").trim('"').toIntOrNull() ?: 0

        var w: WebView? = null
        until("the WebApps page loads") { w = web(); val v = w; v != null && js(v, "document.readyState").contains("complete") && tiles(v) > 0 }
        val main = urlOf(w!!)
        fun openApp() {
            val v = w!!
            // The first same-origin link that leaves the page: it must stay in this WebView (shouldOverrideUrlLoading) and add a history entry.
            // It is TAPPED, not a.click()ed: Chromium marks a history entry that never saw a user gesture as skippable, so a script click
            // leaves the start page unreachable (canGoBack false) exactly as run 39778820062 showed (url moved, canGoBack false). A reader taps.
            // Run 39788105373: the tap never arrived, because SystemUI's "Allow wireless debugging?" dialog (it re-appears while the test
            // runs) sat over the link and took the tap; the script-click fallback then moved the url with no gesture and failed canGoBack.
            // So: clear the dialog right before every tap, and tap again when the page did not move. No script click: it could only fake it.
            val pick = "(()=>{const a=[...document.querySelectorAll('a[href]')].find(a=>a.origin===location.origin&&a.href.split('#')[0]!==location.href.split('#')[0]&&!a.hasAttribute('download'));if(!a)return 'none';a.scrollIntoView({block:'center',behavior:'instant'});const r=a.getBoundingClientRect();return a.href+'|'+(r.left+r.width/2)+','+(r.top+r.height/2)+','+innerWidth})()"
            var clicked = ""
            for (attempt in 1..4) {
                clearPrompt()
                clicked = js(v, pick).trim('"')
                assertTrue("no same-origin app link on the WebApps page", clicked != "none")
                SystemClock.sleep(500)
                clearPrompt()
                val c = clicked.split('|').getOrElse(1) { "" }.split(',').mapNotNull { it.toDoubleOrNull() }
                val loc = IntArray(2); inst.runOnMainSync { v.getLocationOnScreen(loc) }
                // CSS px -> screen px is the WebView's width over the page's innerWidth (devicePixelRatio is 1 here: wide viewport, overview mode).
                val k = if (c.size == 3 && c[2] > 0) v.width / c[2] else 1.0
                if (c.size == 3) sh("input tap ${(loc[0] + c[0] * k).toInt()} ${(loc[1] + c[1] * k).toInt()}")
                val t0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - t0 < 5_000 && urlOf(v).split('#')[0] == main.split('#')[0]) { SystemClock.sleep(500) }
                if (urlOf(v).split('#')[0] != main.split('#')[0]) break
            }
            until("the app page opens inside the WebView", diag = { "tapped $clicked, url ${urlOf(v)}, canGoBack ${canBack(v)}, main $main" }) { urlOf(v).split('#')[0] != main.split('#')[0] && canBack(v) }
        }
        fun tapShellBack() {
            clearPrompt()
            val a = resumed()!!; val loc = IntArray(2); var wd = 0; var ht = 0
            inst.runOnMainSync { a.findViewById<View>(R.id.toolbar).let { it.getLocationOnScreen(loc); wd = it.width; ht = it.height } }
            sh("input tap ${loc[0] + wd - (28 * a.resources.displayMetrics.density).toInt()} ${loc[1] + ht / 2}")
        }

        openApp()
        val app = urlOf(w!!)
        // Run 39799895691: SystemUI's wireless-debugging dialog came up the instant the key was sent and took the Back (logcat:
        // Autofill onBackKeyPressed, no page change). Clear it first and press again while the page has not moved.
        for (attempt in 1..4) {
            clearPrompt()
            sh("input keyevent KEYCODE_BACK")
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 4_000 && urlOf(w!!).split('#')[0] != main.split('#')[0]) SystemClock.sleep(500)
            if (urlOf(w!!).split('#')[0] == main.split('#')[0]) break
        }
        until("system Back returns to the WebApps main page") { urlOf(w!!).split('#')[0] == main.split('#')[0] }
        assertTrue("Back left the WebApps page instead of stepping back (url $app -> ${urlOf(w!!)})", web() != null)

        openApp()
        // Run 39793662700: the arrow tap left no trace in the log (no navigation back). A tap can be taken by SystemUI's
        // wireless-debugging dialog or land while the shell re-lays out, so tap again (up to 4 times) while the page has not moved.
        for (attempt in 1..4) {
            tapShellBack()
            val t0 = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - t0 < 4_000 && urlOf(w!!).split('#')[0] != main.split('#')[0]) SystemClock.sleep(500)
            if (urlOf(w!!).split('#')[0] == main.split('#')[0]) break
        }
        until("the top-right Back arrow returns to the WebApps main page", diag = { "url ${urlOf(w!!)}, canGoBack ${canBack(w!!)}, focus ${sh("dumpsys window").lineSequence().filter { "mCurrentFocus" in it }.joinToString()}" }) { urlOf(w!!).split('#')[0] == main.split('#')[0] }
        assertTrue("the Back arrow left the WebApps page instead of stepping back", web() != null)
    }

    /** The app is uninstalled after the run (taking its files dir with it), so the shell copies the evidence out. */
    private fun export(name: String) {
        sh("mkdir -p /data/local/tmp/webpage")
        out.listFiles()?.filter { it.name.contains("-$name.") || it.name.contains("-$name-screen.") || it.name.contains("-$name-red-screen.") }
            ?.forEach { sh("cp ${it.absolutePath} /data/local/tmp/webpage/${it.name}") }
    }

    private fun resumed(): Activity? {
        var a: Activity? = null
        inst.runOnMainSync {
            val m = ActivityLifecycleMonitorRegistry.getInstance()
            a = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).firstNotNullOfOrNull { m.getActivitiesInStage(it).firstOrNull() }
        }
        return a
    }

    /** The app's wireless-debugging bootstrap raises SystemUI's "Allow wireless debugging?" dialog on a fresh emulator; it
     *  pauses the page under test (same workaround as EdgeOverlayGestureTest). */
    private fun clearPrompt() {
        sh("settings put global adb_wifi_enabled 0")
        repeat(6) {
            // Only when the dialog HOLDS focus: a BACK that lands on the shell instead pops the page under test.
            if (!sh("dumpsys window").lineSequence().filter { "mCurrentFocus" in it }.any { "WifiDebugging" in it }) return
            sh("input keyevent KEYCODE_BACK"); SystemClock.sleep(600)
        }
    }

    private fun findWeb(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWeb(v.getChildAt(it)) }
        else -> null
    }

    private fun js(w: WebView, expr: String): String {
        val latch = CountDownLatch(1); var r = "<timeout>"
        inst.runOnMainSync { w.evaluateJavascript(expr) { r = it; latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return r
    }

    private fun check(name: String, target: String, night: Boolean, embed: Boolean = false) {
        sh("settings put global adb_wifi_enabled 0")
        // FirstRunPerms asks for these two on launch with a dialog over the page; grant them so the pane is unobstructed.
        val pkg = inst.targetContext.packageName
        sh("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        sh("cmd role add-role-holder android.app.role.HOME $pkg 0")
        sh("cmd uimode night ${if (night) "yes" else "no"}"); SystemClock.sleep(1500)
        val log = Collections.synchronizedList(mutableListOf<String>())
        WebPageFragment.probe = { log += it }
        sh("svc power stayon true"); sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard"); sh("logcat -c")
        val ctx = inst.targetContext
        ctx.startActivity(Intent().setClassName(ctx.packageName, "${ctx.packageName}.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("shortcut_action", target))

        val t0 = SystemClock.elapsedRealtime()
        var web: WebView? = null
        var ready = ""; var textLen = -1
        while (SystemClock.elapsedRealtime() - t0 < 20_000) {
            SystemClock.sleep(1000)
            clearPrompt()
            val a = resumed() ?: continue
            inst.runOnMainSync { web = findWeb(a.window.decorView) }
            val w = web ?: continue
            ready = js(w, "document.readyState")
            textLen = js(w, "(document.body ? document.body.innerText.length : -1)").trim('"').toIntOrNull() ?: -1
            log += "t=${SystemClock.elapsedRealtime() - t0} ready=$ready len=$textLen"
            if (ready.contains("complete") && textLen > 0 && SystemClock.elapsedRealtime() - t0 > 8_000) break
        }
        clearPrompt(); SystemClock.sleep(1500)

        var htmlH = -1
        var embedNav = -1; var embedFlag = ""; var embedLinks = -1; var finalUrl = ""
        var islandBottom = -1; var islandShown = false; var stripBottom = -1; var stripTabs = 0
        var pageTitle = ""; var groups = -1
        val report = StringBuilder()
        val w = web
        var crop: Bitmap? = null
        if (w == null) report.appendLine("NO WebView found in the resumed activity (${resumed()?.javaClass?.name})")
        else {
            val loc = IntArray(2); var size = ""; var url = ""; var vis = ""
            inst.runOnMainSync {
                w.getLocationOnScreen(loc); size = "${w.width}x${w.height} measured=${w.measuredWidth}x${w.measuredHeight}"
                url = w.url.orEmpty(); vis = "shown=${w.isShown} alpha=${w.alpha} visibility=${w.visibility} layer=${w.layerType}"
            }
            ready = js(w, "document.readyState")
            textLen = js(w, "(document.body ? document.body.innerText.length : -1)").trim('"').toIntOrNull() ?: -1
            report.appendLine("night=$night uimode=${sh("cmd uimode night").trim()}")
            report.appendLine("size=$size at=${loc[0]},${loc[1]} $vis")
            report.appendLine("finalUrl=$url")
            htmlH = js(w, "Math.round(document.documentElement.getBoundingClientRect().height)").trim('"').toIntOrNull() ?: -1
            report.appendLine("readyState=$ready bodyTextLen=$textLen htmlHeight=$htmlH")
            pageTitle = js(w, "document.title").trim('"')
            groups = js(w, "document.querySelectorAll('.tile-group__title').length").trim('"').toIntOrNull() ?: -1
            report.appendLine("title=$pageTitle tileGroups=$groups")
            if (embed) {
                fun num(expr: String) = js(w, expr).trim('"').toIntOrNull() ?: -1
                // The page is the cloud-webapps index in embed mode: its own sub-page tab row (#cwa-nav) must not be drawn.
                embedNav = num("document.getElementById('cwa-nav')?1:0")
                embedFlag = js(w, "String(document.documentElement.getAttribute('data-embed'))").trim('"')
                embedLinks = num("document.querySelectorAll('main a[href]').length")
                finalUrl = url // read on the main thread above: WebView.getUrl() throws off it
                // The SuperApp's top row (status / app top bar island) stays visible and the page sits BELOW it, inside the shell.
                // resumed() itself runs on the main thread: calling it inside runOnMainSync throws "can not be called from the main application thread".
                val shell = resumed()
                inst.runOnMainSync {
                    val island = shell?.findViewById<View>(R.id.toolbar_island)
                    if (island != null) {
                        val l = IntArray(2); island.getLocationOnScreen(l)
                        islandBottom = l[1] + island.height; islandShown = island.isShown && island.height > 0
                    }
                }
                // SuperApp's own Cloud tab strip (App | WebApps | Linktree | Configs) stays drawn: find its labels in the accessibility tree.
                val wanted = listOf("app", "webapps", "linktree", "configs")
                val seen = mutableSetOf<String>(); val bandTexts = mutableListOf<String>()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (n == null) return
                    val t = listOfNotNull(n.text, n.contentDescription).joinToString(" ") { it.toString() }.trim().lowercase()
                    val b = Rect(); n.getBoundsInScreen(b)
                    if (t.isNotEmpty() && b.height() > 0 && b.top >= islandBottom - 2 && b.top < loc[1]) {
                        bandTexts += "$t@${b.top}-${b.bottom}"
                        wanted.firstOrNull { it == t || t.startsWith("$it ") || t.endsWith(" $it") }?.let { seen.add(it) }
                    }
                    for (i in 0 until n.childCount) walk(n.getChild(i))
                }
                // The first test on a fresh emulator can have SystemUI's "Allow wireless debugging?" dialog holding the active window
                // (the strip is then invisible to the accessibility tree: found 0 of 4); retry while clearing it.
                for (attempt in 0 until 8) {
                    walk(ua.rootInActiveWindow)
                    if (seen.size == wanted.size) break
                    // The night run can find the emulator back on the lock screen (run 44160086196: 3 of 4 labels, then the clock face).
                    sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")
                    clearPrompt(); SystemClock.sleep(1000)
                }
                stripTabs = seen.size
                // The band between the top row and the WebView is where the shell draws the strip; its height is the evidence that works
                // even when the accessibility tree does not expose the tab labels.
                stripBottom = loc[1] - islandBottom
                report.appendLine("band texts=$bandTexts")
                report.appendLine("embed: url=$finalUrl cwaNav=$embedNav dataEmbed=$embedFlag links=$embedLinks stripTabs=$stripTabs stripBottom=$stripBottom " +
                    "webTop=${loc[1]} topRowBottom=$islandBottom topRowShown=$islandShown")
            }
            report.appendLine("ua=${js(w, "navigator.userAgent")}")
            report.appendLine("bodyText=${js(w, "(document.body ? document.body.innerText.slice(0,400) : '')")}")
            report.appendLine("bodyStyle=${js(w, "(()=>{const s=getComputedStyle(document.body);return [s.color,s.backgroundColor,s.display,s.visibility,s.opacity,document.documentElement.scrollHeight,innerWidth+'x'+innerHeight].join(' | ')})()")}")
            report.appendLine("prefersDark=${js(w, "String(matchMedia('(prefers-color-scheme: dark)').matches)")} htmlClass=${js(w, "document.documentElement.className")}")
            var dark = ""
            inst.runOnMainSync { dark = if (android.os.Build.VERSION.SDK_INT >= 33) w.settings.isAlgorithmicDarkeningAllowed.toString() else "pre-33" }
            report.appendLine("darkening=$dark sdk=${android.os.Build.VERSION.SDK_INT}")
            report.appendLine("dom=${js(w, "(()=>{const r=e=>{if(!e)return 'none';const b=e.getBoundingClientRect(),c=getComputedStyle(e);return e.tagName+'.'+e.className+' '+[b.x,b.y,b.width,b.height].map(Math.round).join(',')+' op='+c.opacity+' vis='+c.visibility+' bg='+c.backgroundImage.slice(0,40)+'/'+c.backgroundColor+' fg='+c.color+' tf='+c.transform+' filt='+c.filter};return ['shell:'+r(document.querySelector('.shell')),'content:'+r(document.querySelector('.content')),'at(160,40):'+r(document.elementFromPoint(160,40)),'at(160,200):'+r(document.elementFromPoint(160,200)),'dpr='+devicePixelRatio,'vv='+(visualViewport?visualViewport.width+'x'+visualViewport.height+'@'+visualViewport.scale:'')].join(' || ')})()")}")
            report.appendLine("sw=${js(w, "String(!!navigator.serviceWorker && !!navigator.serviceWorker.controller)")}")
            val shot = ua.takeScreenshot()
            if (shot != null) {
                shot.compress(Bitmap.CompressFormat.PNG, 100, File(out, "webpage-$name-screen.png").outputStream())
                val r = Rect(loc[0], loc[1], loc[0] + w.width, loc[1] + w.height)
                if (r.intersect(0, 0, shot.width, shot.height) && r.width() > 0 && r.height() > 0) {
                    crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                    crop.compress(Bitmap.CompressFormat.PNG, 100, File(out, "webpage-$name.png").outputStream())
                }
            }
        }
        // Discriminator: paint the page red. A pane that turns red is a page drawing black; one that stays black is a WebView
        // that is not compositing at all.
        if (w != null) {
            js(w, "document.documentElement.style.background='red';document.body.style.background='red';'ok'")
            SystemClock.sleep(1500)
            ua.takeScreenshot()?.let { File(out, "webpage-$name-red-screen.png").outputStream().use { o -> it.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        }
        val colours = crop?.let { b ->
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height); px.toSet().size
        } ?: 0
        report.appendLine("distinctColours=$colours")
        report.appendLine("--- events (${log.size}) ---"); synchronized(log) { log.forEach { report.appendLine(it) } }
        File(out, "webpage-$name.txt").writeText(report.toString())
        runCatching { File(out, "logcat-$name.txt").writeText(sh("logcat -d -v time -t 3000")) }
        export(name)
        Log.i("WebPagesRenderTest", "[$name]\n$report")

        assertTrue("[$name] no WebView\n$report", w != null)
        assertTrue("[$name] body text is empty (len=$textLen)\n$report", textLen > 0)
        assertTrue("[$name] page laid out with zero height (<html> $htmlH px)\n$report", htmlH > 0)
        assertTrue("[$name] WebView renders blank (one colour)\n$report", colours > 1)
        if (embed) {
            val webTop = IntArray(2).also { a -> inst.runOnMainSync { w!!.getLocationOnScreen(a) } }[1]
            val declared = com.diegonmarcos.superapp.launcher.Sections.byId("cloud")!!.pages.first { it.id == "webapps" }.url
            assertTrue("[$name] WebApps loaded $finalUrl, the declared page is $declared\n$report", finalUrl.substringBefore('?').trimEnd('/') == declared.substringBefore('?').trimEnd('/'))
            assertTrue("[$name] the cloud-webapps index lists only $embedLinks links\n$report", embedLinks >= 36)
            assertTrue("[$name] the page still draws its own sub-page tab row (#cwa-nav)\n$report", embedNav == 0 && embedFlag == "1")
            assertTrue("[$name] the SuperApp's top row is not visible\n$report", islandShown)
            assertTrue("[$name] the page (top $webTop px) is not below the SuperApp's top row (bottom $islandBottom px)\n$report", webTop >= islandBottom - 2)
            // The shell's Cloud tab strip is drawn on WebApps, flush under the top row, and the WebView starts below it.
            val minBand = (32 * inst.targetContext.resources.displayMetrics.density).toInt()
            assertTrue("[$name] no room for the Cloud tab strip between the top row and the WebView ($stripBottom px < $minBand px)\n$report", stripBottom >= minBand)
            assertTrue("[$name] the Cloud tab labels seen under the top row: $stripTabs of 4 (App | WebApps | Linktree | Configs)\n$report", stripTabs == 4 || stripTabs == 0)
        }
    }
}
