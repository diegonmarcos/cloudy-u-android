package app.cloudnotes.pro

import androidx.compose.ui.graphics.toArgb
import app.cloudnotes.pro.theme.ObsidianTokens
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.WebProfile
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.cloudnotes.pro.plugin.CloudNotesThemePlugin
import app.cloudnotes.pro.plugin.ExternalFilePlugin
import app.cloudnotes.pro.plugin.VaultPlugin
import app.cloudnotes.pro.plugin.LocalAIButtonPlugin
import app.cloudnotes.pro.plugin.LocalAuthPlugin
import app.cloudnotes.pro.plugin.LocalHashCashPlugin
import app.cloudnotes.pro.plugin.NbStorePlugin
import app.cloudnotes.pro.plugin.MobileBackPlugin
import app.cloudnotes.pro.plugin.PreviewPlugin
import app.cloudnotes.pro.utils.dp2px
import app.cloudnotes.pro.utils.px2dp
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebChromeClient
import com.getcapacitor.WebViewListener
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.updateMargins
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

// DE-CLOUDED (#469): upstream wired this activity to the Cloud Notes: AI chat
// (FAB + AIActivity), cloud auth (AuthPlugin/HashCashPlugin), GraphQL and SSE
// services, and log upload. None of that exists in this local-first build.
// What remains is the editor WebView, the Android IME bridge and the local
// storage plugins.
//
// #543: the de-clouding deleted those plugins' NATIVE side but not the web code
// that calls them, and an unregistered Capacitor plugin throws rather than
// degrades — which killed local workspace creation. They are back as local-only
// implementations (DeCloudedPlugins.kt); still no server, but the bridge
// resolves. This list is the ONE registration site for app-local plugins and
// `cap sync` does not rewrite it, so the fix survives a re-vendor.
@AndroidEntryPoint
class MainActivity : BridgeActivity(), CloudNotesThemePlugin.Callback {

    init {
        registerPlugins(
            listOf(
                CloudNotesThemePlugin::class.java,
                NbStorePlugin::class.java,
                MobileBackPlugin::class.java,
                PreviewPlugin::class.java,
                ExternalFilePlugin::class.java,
                VaultPlugin::class.java,
                LocalAuthPlugin::class.java,
                LocalHashCashPlugin::class.java,
                LocalAIButtonPlugin::class.java,
            )
        )
    }

    private var navHeight = 0
    /** The native shell; the theme callback reaches it (#1369). */
    private var shell: NotesShell? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            navHeight = px2dp(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        setupOpenFileFab()
        setupNativeNav()
    }

    // #1164: the shared nav scaffold (first row, second row, bottom nav, page background, scroll-collapse) is the activity's
    // content and the Capacitor layout (editor WebView + open-file button) sits inside it. The web half hides its own tabs once
    // it sees cloudNotesNativeNav and adds no system-bar padding (setup.ts).
    private fun setupNativeNav() {
        val decl = NavDecl.fromBuildConfig(
            BuildConfig.UI_SECTIONS_B64,
            BuildConfig.UI_BOTTOM_NAV,
            BuildConfig.UI_DEFAULT_SECTION,
        )
        val container = bridge.webView.parent as CoordinatorLayout
        val run: (String) -> Unit = { js -> bridge.webView.post { bridge.webView.evaluateJavascript(js, null) } }
        val shell = NotesShell(NativeNav(decl, run), BuildConfig.AI_GATEWAY_URL, run).also { this.shell = it }
        // the editor's scroll bridge (NavChrome) must be on the WebView in the turn that starts its load: a JavaScript interface added
        // later never reaches the already-loaded single page; the shell sets the collapse target when it composes
        com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(bridge.webView, null)
        bridge.webView.addJavascriptInterface(shell.host(), "CloudNotesHost")
        (container.parent as? ViewGroup)?.removeView(container)
        setContentView(ComposeView(this).apply { setContent { shell.Content(container, bridge.webView) } })
    }

    // #469: the ONE feature over upstream — open a file from emulated storage.
    // The FAB asks the editor for a path; the editor reads it through the
    // ExternalFilePlugin and imports the markdown into the local workspace.
    private fun setupOpenFileFab() {
        val fab = FloatingActionButton(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(dp2px(52), dp2px(52)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                updateMargins(0, 0, dp2px(24), dp2px(24))
            }
            customSize = dp2px(52)
            setImageResource(R.drawable.ic_open_file)
            // #1369 Obsidian's --interactive-accent (generated ObsidianTokens), the same in light and dark
            backgroundTintList = android.content.res.ColorStateList.valueOf(ObsidianTokens.Light.interactiveAccent.toArgb())
            imageTintList = android.content.res.ColorStateList.valueOf(ObsidianTokens.Light.textOnAccent.toArgb())
            setOnClickListener {
                bridge.webView.post {
                    bridge.webView.evaluateJavascript(
                        "window.dispatchEvent(new Event('cloud-notes:open-file'))",
                        null,
                    )
                }
            }
            val parent = bridge.webView.parent as CoordinatorLayout
            parent.addView(this)
        }
    }

    override fun load() {
        super.load()
        configureAndroidIMEBridge()
        configureEditorWebView()
        persistWebConsoleErrors()
    }

    // #522: THE ONLY PLACE THE REAL CAUSE IS EVER WRITTEN.
    //
    // This app is a web application in a WebView. When it fails to boot, the
    // thing that actually failed is a JavaScript exception, and the web layer
    // does what web layers do: it catches it, writes it to console.error and
    // renders a fixed string — "An internal error occurred." (the
    // error.INTERNAL_SERVER_ERROR i18n key, which is itself a lie: this build
    // has no server). See core/src/desktop/pages/index/index.tsx, where the
    // first-workspace bootstrap is caught and discarded.
    //
    // Capacitor's BridgeWebChromeClient already forwards that console line to
    // LOGCAT, but nothing persisted it: FileTree logs Kotlin only, and logcat
    // is a ring buffer that is empty by the time anyone is asked to look. So
    // two tickets in a row (#513, #522) had to reason about a web-layer boot
    // failure from source, with the exception sitting unreadable on the phone.
    //
    // Console ERRORs now go through Timber, which means FileTree writes them to
    // filesDir/logs/<date>.log and they survive the process, the reboot and the
    // question "what did it actually say?". super() is still called, so
    // Capacitor's own console handling is unchanged.
    private fun persistWebConsoleErrors() {
        bridge.webView.webChromeClient = object : BridgeWebChromeClient(bridge) {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Timber.e(
                        "web console error: %s (%s:%d)",
                        consoleMessage.message(),
                        consoleMessage.sourceId(),
                        consoleMessage.lineNumber(),
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun configureAndroidIMEBridge() {
        val trustedOrigin = normalizeCloudNotesOrigin(bridge.localUrl)
        bridge.setWebViewClient(CloudNotesWebViewClient(bridge, trustedOrigin))
        bridge.addWebViewListener(object : WebViewListener() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                (view as? CloudNotesEditorWebView)?.updateAndroidIMEBridge(url, trustedOrigin)
            }
        })

        (bridge.webView as? CloudNotesEditorWebView)?.updateAndroidIMEBridge(
            bridge.webView.url ?: bridge.localUrl,
            trustedOrigin,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            bridge.webView.evaluateJavascript(
                "window.dispatchEvent(new Event('cloud-notes:memory-pressure'))",
                null,
            )
        }
    }

    private fun configureEditorWebView() {
        // Capacitor builds the view and keeps its own client; the fleet's APP_PAGE profile (#1371) sets the rest:
        // JS, storage, compatibility-mode mixed content (a debug CAP_SERVER_URL on an HTTP dev server), GPU, recovery
        CloudWeb.configure(bridge.webView, WebProfile.APP_PAGE)
        bridge.webView.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            settings.apply {
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
        }
    }

    override fun onThemeChanged(darkMode: Boolean) {
        // #1369 the web half's resolved theme drives the native shell (Obsidian light or dark).
        runOnUiThread { shell?.setDark(darkMode) }
    }

    override fun getSystemNavBarHeight(): Int {
        return navHeight
    }
}
