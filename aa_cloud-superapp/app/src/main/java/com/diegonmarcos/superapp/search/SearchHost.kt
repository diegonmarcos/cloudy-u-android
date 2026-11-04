package com.diegonmarcos.superapp.search

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.launcher.CloudSearchHandoff
import com.diegonmarcos.superapp.launcher.GroupedTilesFragment
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.kitComposeView
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What a pick does, the same from every place the SuperApp searches. A hit carries its own
 * dispatch ([SearchHit]); a target or a command goes to the activity's tile dispatcher, the one
 * place that knows what `section:` / `page:` / `action:` / `extapp:` strings mean.
 */
class SearchActions(private val activity: Activity) {

    fun open(hit: SearchHit) {
        // Locals: the hit's fields belong to libs:search, so they do not smart-cast here.
        val app = hit.phoneApp
        val settings = hit.settingsComponent
        val copy = hit.copyValue
        val intent = hit.intent
        val target = hit.target
        when {
            app != null -> {
                if (com.diegonmarcos.superapp.launcher.SelfTile.open(activity, app.component.packageName)) return
                val launcher = activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps ?: return
                runCatching { launcher.startMainActivity(app.component, app.user, null, null) }
            }
            settings != null -> start(Intent().setComponent(settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), hit.label)
            copy != null -> {
                val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText(hit.label, copy))
                Toast.makeText(activity, "Copied: $copy", Toast.LENGTH_SHORT).show()
            }
            intent != null -> start(intent, hit.label)
            target != null -> target(target)
        }
    }

    fun run(cmd: SearchCommand) = target(cmd.target)

    /** #937 the query goes to Cloud Search ([CloudSearchHandoff]), which opens on its Search page
     *  and runs it; a Cloud Search that cannot take it (absent, too old) is opened by its tile. */
    fun cloudSearch(q: String) {
        val target = GroupedTilesFragment.CLOUD_SEARCH_TARGET
        val app = Sections.externalApp(target.removePrefix("extapp:"))
        val intent = CloudSearchHandoff.intent(activity.packageManager, CloudSearchHandoff.packages(app), q)
        if (intent == null || runCatching { activity.startActivity(intent) }.isFailure) target(target)
    }

    private fun target(t: String) {
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(t)
    }

    private fun start(i: Intent, label: String) {
        runCatching { activity.startActivity(i) }.onFailure {
            Toast.makeText(activity, "Can't open “$label”", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The search placed INLINE at the top of a page — Cloud ▸ Apps (directly under its tab icons) and
 * the Home swipe sheet: [bar] goes in the page's flow above the content, [dropdown] is layered
 * over the content ([SearchDropdown]). The dropdown view is GONE while the bar is idle, so the
 * page under it gets every touch; with text it covers the page, opaque. The page mounts the two
 * (GroupedTilesFragment.mountSearch), so this file builds no View of its own.
 */
class InlineSearch(fragment: Fragment, entry: SearchEntry, placeholder: String) {
    private val ctx = fragment.requireContext()
    val controller = SearchController(entry, SuperappSearchIndex.Source(ctx), SearchScopePrefs(ctx))

    private fun withActions(fragment: Fragment, block: SearchActions.() -> Unit) {
        val a = fragment.activity ?: return
        reset()
        SearchActions(a).block()
    }

    private val callbacks = SearchCallbacks(
        onHit = { hit -> withActions(fragment) { open(hit) } },
        onCommand = { cmd -> withActions(fragment) { run(cmd) } },
        onCloudSearch = { q -> withActions(fragment) { cloudSearch(q) } },
    )

    private val palette = LauncherPalette.kit(ctx)
    private val surface = Color(LauncherPalette.opaqueSurface(ctx))

    val bar: ComposeView = ctx.kitComposeView(palette) {
        SearchBox(controller, placeholder, onGo = { controller.go(callbacks) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }

    val dropdown: ComposeView = ctx.kitComposeView(palette) { SearchDropdown(controller, surface, callbacks) }.apply {
        visibility = View.GONE
        elevation = 8f * ctx.resources.displayMetrics.density
        // A view only while there is something to show in it.
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var scope: CoroutineScope? = null
            override fun onViewAttachedToWindow(v: View) {
                scope = MainScope().also { s ->
                    s.launch {
                        snapshotFlow { controller.query.isNotBlank() || controller.focused }.collect { show ->
                            v.visibility = if (show) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
            override fun onViewDetachedFromWindow(v: View) { scope?.cancel(); scope = null }
        })
    }

    /** Back with a query clears it (the page returns) before Back leaves the page. */
    fun handleBack(): Boolean {
        if (controller.query.isBlank()) return false
        reset()
        return true
    }

    /** Leaving through search resets it: coming back shows the page, not a stale filter. */
    fun reset() {
        controller.query = ""
        controller.blur()
        val imm = bar.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(bar.windowToken, 0)
        // Hand the window's focus to the page the bar sits in (made focusable for exactly this
        // by GroupedTilesFragment.mountSearch). Clearing the bar's focus alone does not stick:
        // Android gives the focus straight back to the first focusable view, which is the bar.
        (bar.parent as? View)?.takeIf { it.isFocusable }?.requestFocus()
    }
}
