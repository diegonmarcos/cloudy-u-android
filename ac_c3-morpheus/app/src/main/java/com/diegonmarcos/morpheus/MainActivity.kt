package com.diegonmarcos.morpheus

import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsMenu
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONObject

/**
 * Cloud-Morpheus, v1.
 *
 * THE SPLIT: c3-watchdog keeps what is running ALIVE (reactive, one machine).
 * c3-morpheus decides what RUNS (intentional, the whole fleet). This app is
 * morpheus's face on a phone: the workflows, the probe registry, the fleet and
 * the PM board, in one shell.
 *
 * WHY IT IS MOSTLY WEBVIEWS, AND WHY THAT IS NOT A COP-OUT.
 * Dagu and Paca are real, live, authenticated surfaces that already draw
 * themselves. Paca in particular has NO board API — /api/health, /api/v1/boards
 * and /api/boards all 404 and every route 302s to auth.diegonmarcos.com behind
 * Authelia forward_auth — so the board IS the Paca app, and the SuperApp
 * already reached the same conclusion and renders it inline. Reimplementing
 * either would be inventing a second truth. ac_c3-watchdog is a WebView for
 * the same reason.
 *
 * NO DEAD BUTTONS. Every tab either shows a real surface or states, on screen,
 * exactly what is not wired and why. A page that fails to load says so instead
 * of going blank — which matters most for Boards, because Paca is MESH-ONLY
 * (there is no public edge certificate for paca.diegonmarcos.com) and a phone
 * off the mesh would otherwise show an empty white rectangle that reads like an
 * outage.
 */
class MainActivity : AppCompatActivity() {

    private var web: WebView? = null
    /** Non-null while a notice replaces the page; the WebView is then GONE, as before. */
    private var notice by mutableStateOf<String?>(null)
    private var current by mutableStateOf(Tab.WORKFLOWS)

    private enum class Tab(val label: String) {
        WORKFLOWS("Workflows"), HEALTH("Health"), FLEET("Fleet"), BOARDS("Boards")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun c(id: Int) = ContextCompat.getColor(this, id)
        val palette = KitPalette.fromArgb(
            surface = c(R.color.kit_surface),
            surfaceSelected = c(R.color.kit_surface_selected),
            textPrimary = c(R.color.kit_text_primary),
            textSecondary = c(R.color.kit_text_secondary),
            accent = c(R.color.kit_accent),
            hairline = c(R.color.kit_hairline),
            tileInk = c(R.color.kit_tile_ink),
        )

        setContent {
            CloudKitTheme(palette) {
                val p = LocalKitPalette.current
                LaunchedEffect(Unit) { select(Tab.WORKFLOWS) }
                Column(Modifier.fillMaxSize().background(p.surface)) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Tab.values().forEach { t ->
                            Button(
                                onClick = { select(t) },
                                shape = RectangleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = p.surfaceSelected, contentColor = p.accent,
                                ),
                            ) { Text(t.label) }
                        }
                        // #1352 Configs ▸ General / ▸ About (libs:ui-nav-bars)
                        FleetConfigsMenu(tint = p.accent)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                CloudWeb.create(ctx, WebProfile.APP_PAGE).apply {   // Dagu and Paca: fleet SPAs
                                    setBackgroundColor(p.surface.toArgb())
                                    webViewClient = object : CloudWebClient(WebProfile.APP_PAGE) {
                                        override fun onReceivedError(
                                            view: WebView?, req: WebResourceRequest?, err: WebResourceError?
                                        ) {
                                            // Main-frame failures only: a sub-resource that 404s is not
                                            // the page being unreachable, and treating it as one would
                                            // put a false outage over a page that rendered fine.
                                            if (req?.isForMainFrame != true) return
                                            showUnreachable(err?.description?.toString() ?: "no answer")
                                        }
                                    }
                                    web = this
                                }
                            },
                        )
                        // Scrollable, because these notices are long on purpose: an
                        // explanation that is cut off at the fold explains nothing.
                        notice?.let { text ->
                            Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState())) {
                                Text(
                                    text,
                                    color = p.textPrimary,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun select(t: Tab) {
        current = t
        when (t) {
            // The Dagu web UI: the real workflow list, with the real Start
            // button. The SAME surface Cloud/C3/Observability already reads
            // through DaguClient — no second client, no second truth.
            Tab.WORKFLOWS -> loadUrl(BuildConfig.DAGU_URL)
            Tab.HEALTH -> showHealthRegistry()
            Tab.FLEET -> showFleet()
            // Paca, rendered inline. It has no board API to wrap.
            Tab.BOARDS -> loadUrl(BuildConfig.PACA_URL)
        }
    }

    private fun loadUrl(url: String) {
        notice = null
        web?.visibility = View.VISIBLE
        web?.loadUrl(url)
    }

    private fun showHtml(html: String) {
        notice = null
        web?.visibility = View.VISIBLE
        web?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun showNotice(text: String) {
        web?.visibility = View.GONE
        notice = text
    }

    /**
     * A page that would not load says so, and says what it means for THIS
     * target. Blank is the one thing it must never be — a white rectangle
     * where a board should be reads as "the board is gone".
     */
    private fun showUnreachable(reason: String) {
        val where = when (current) {
            Tab.BOARDS ->
                "Paca is MESH-ONLY. There is no public edge certificate for " +
                    "paca.diegonmarcos.com — it measures 000 from the public edge " +
                    "while the service is perfectly healthy on 10.0.0.6:8095.\n\n" +
                    "So this is almost certainly THIS PHONE being off the WireGuard " +
                    "mesh, and is NOT evidence that Paca is down. Connect the mesh " +
                    "and reopen the tab."
            Tab.WORKFLOWS ->
                "Dagu sits behind Authelia forward_auth. An unauthenticated " +
                    "request is answered with a redirect to auth.diegonmarcos.com, " +
                    "which is the edge working. A hard failure like this one is " +
                    "usually no route rather than Dagu being down."
            else -> "The page did not load."
        }
        showNotice("COULD NOT LOAD\n\n$reason\n\n$where")
    }

    private val PROBES_DATASET = "fleet/morpheus-probes"

    /**
     * The probe registry — WHAT "HEALTHY" IS DECLARED TO MEAN.
     *
     * Declarations, and it says so at the top. This app does not run probes
     * and does not display live results: those are produced by the
     * cloud-health-*.sh family, scheduled by Dagu, and published to ntfy. A
     * tile here coloured green would be a colour with nothing behind it, which
     * is precisely the failure this whole product is trying to stop.
     *
     * The registry is the one shipped in the CLI (da_morpheus/data/probes.json),
     * served at runtime (tier 2, fleet/morpheus-probes) from the private repo, so the public repo holds no copy.
     */
    private fun showHealthRegistry() {
        // #1566 the registry names internal hosts, so it is a fleet/infra (tier 2) dataset served by Cloud Account from the private repo,
        // never an APK asset: read through the fleet data client (last-known-good copy offline). Null = not served yet.
        val raw = com.diegonmarcos.superapp.fleetdata.FleetData.get(this, PROBES_DATASET) { it }
        if (raw == null) {
            showNotice(
                "PROBE REGISTRY NOT SERVED YET\n\n" +
                    "Cloud Account has not delivered the registry (fleet/morpheus-probes) to this phone yet, so " +
                    "this page shows nothing rather than an empty list that would read as " +
                    "'no probes are declared'. Open Cloud Account once with the fleet bearer granted, then retry."
            )
            return
        }

        val html = StringBuilder(HEAD)
        html.append("<p class=b>DECLARATIONS ONLY. Nothing on this page was measured. ")
        html.append("The probes run in Dagu and report to ntfy; this is the inventory of ")
        html.append("what is declared, not a status board.</p>")
        try {
            val doc = JSONObject(raw)

            html.append("<h2>Endpoint probes</h2>")
            html.append("<p class=n>Measured by the <code>c3-morpheus</code> CLI ")
            html.append("(<code>c3-morpheus probe</code>). Three results, never two: ")
            html.append("up, down, and <b>unavailable</b> — could not measure. ")
            html.append("A probe that cannot run is neither healthy nor failing.</p>")
            val eps = doc.optJSONArray("endpoint_probes")
            for (i in 0 until (eps?.length() ?: 0)) {
                val p = eps!!.getJSONObject(i)
                html.append("<div class=r><b>${esc(p.optString("name"))}</b> ")
                html.append("<span class=t>${esc(p.optString("reach"))}</span><br>")
                html.append("<code>${esc(p.optString("url"))}</code><br>")
                html.append("<span class=n>${esc(p.optString("description"))}</span><br>")
                html.append("<span class=n>ntfy: ${esc(p.optString("topic"))}</span></div>")
            }

            html.append("<h2>Script probes</h2>")
            html.append("<p class=n>Owned by cloud-infra, scheduled by Dagu. ")
            html.append("Morpheus declares and validates them; it does not reimplement ")
            html.append("them, and v1 cannot trigger them — see below.</p>")
            val sps = doc.optJSONArray("script_probes")
            for (i in 0 until (sps?.length() ?: 0)) {
                val p = sps!!.getJSONObject(i)
                html.append("<div class=r><b>${esc(p.optString("name"))}</b><br>")
                html.append("<span class=n>${esc(p.optString("description"))}</span><br>")
                html.append("<span class=n>dag: ${esc(p.optString("dag"))} &middot; ")
                html.append("ntfy: ${esc(p.optString("topic"))}</span>")
                val defect = p.optString("known_defect")
                if (defect.isNotBlank()) {
                    html.append("<div class=d>KNOWN DEFECT — ${esc(defect)}</div>")
                }
                html.append("</div>")
            }
        } catch (t: Throwable) {
            showNotice(
                "PROBE REGISTRY IS NOT VALID JSON\n\n${t.message}\n\n" +
                    "Showing nothing rather than a partial list that would look complete."
            )
            return
        }

        html.append("<h2>Not wired</h2>")
        html.append("<div class=r>This app cannot START a probe or a workflow. ")
        html.append("Triggering is a privileged capability and the authenticated path ")
        html.append("is being built once, in <code>libs/ops</code>: Dagu ")
        html.append("<code>POST /api/v1/dags/{name}/start</code> with a fresh ")
        html.append("client_credentials token per run, plus a server-side GHA dispatch ")
        html.append("proxy. A second client here would be a second thing to keep in sync ")
        html.append("with Authelia. Until it lands, use the Start button in the ")
        html.append("Workflows tab, which is Dagu's own.</div>")
        html.append("</body></html>")
        showHtml(html.toString())
    }

    /**
     * The fleet control panel. The SuperApp already owns it — a Constellation
     * page managing ~57 apps — so this hands off to it rather than shipping a
     * second updater that would fight the first over package ids.
     *
     * When the SuperApp is not installed this says exactly that. It does not
     * silently do nothing, and it does not pretend a fleet panel exists here.
     */
    private fun showFleet() {
        val pm = packageManager
        val intent: Intent? = pm.getLaunchIntentForPackage(BuildConfig.SUPERAPP_PACKAGE)
        if (intent == null) {
            showNotice(
                "FLEET PANEL NOT AVAILABLE ON THIS DEVICE\n\n" +
                    "The fleet control panel is the SuperApp's Constellation page " +
                    "(${BuildConfig.SUPERAPP_PACKAGE}), which manages the whole app " +
                    "fleet and its updates. It is not installed here, so there is " +
                    "nothing to open.\n\n" +
                    "Morpheus deliberately does not ship a second fleet updater: two " +
                    "updaters keyed on the same package ids would fight each other."
            )
            return
        }
        showNotice(
            "Opening the fleet panel in the SuperApp (Constellation).\n\n" +
                "Morpheus does not ship its own — the SuperApp already manages the " +
                "fleet, and a second updater would fight it."
        )
        startActivity(intent)
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.visibility == View.VISIBLE && w.canGoBack()) w.goBack()
        else super.onBackPressed()
    }

    private companion object {
        const val HEAD = """<!doctype html><html><head><meta name=viewport
content="width=device-width,initial-scale=1"><style>
body{background:#0b0e14;color:#c8d4e0;font:15px/1.5 -apple-system,sans-serif;padding:16px;margin:0}
h2{color:#78c8ff;font-size:16px;margin:24px 0 8px}
code{color:#8fd6a0;font-size:13px;word-break:break-all}
.r{border-left:3px solid #2a3a4a;padding:8px 12px;margin:8px 0;background:#101520}
.n{color:#7f8c9a;font-size:13px}
.t{color:#ffb44a;font-size:12px;text-transform:uppercase}
.b{background:#1a1408;border-left:3px solid #ffb44a;padding:10px 12px;font-size:14px}
.d{background:#2a1010;border-left:3px solid #ff6b6b;color:#ffb0b0;padding:8px 10px;margin-top:8px;font-size:13px}
</style></head><body>"""
    }
}
