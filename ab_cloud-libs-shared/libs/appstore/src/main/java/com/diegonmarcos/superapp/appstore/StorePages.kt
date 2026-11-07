package com.diegonmarcos.superapp.appstore

/**
 * #896 Which child page of a Store section is showing, and who draws the strip for it.
 *
 * Cloud Store declares child pages in build.json::ui (Phone: installed | declared, Feed:
 * commits | cicd) and its host draws them with libs:bottomnav's PageTabs, then calls [select].
 * A host that does NOT draw them (SuperApp's embedded Store) leaves [hostDrawsStrip] false and
 * the fragment draws the same pages itself with [StoreTabs], the strip Cloud already wears. (View-free on purpose: the page frame and the strip builder live in StoreBar.kt.)
 * Either way the fragment reads the page from here, so there is one state and one meaning.
 */
object StorePages {
    /** Set once by a host that draws the child-page strip itself (Cloud Store's MainActivity). */
    @Volatile var hostDrawsStrip = false

    /** The Fleet Data page as an embeddable pane (authority, host modifier); set by the app that hosts the provider and bundles libs:fleet-data-ui, null elsewhere. */
    @Volatile var fleetDataPane: (@androidx.compose.runtime.Composable (String, androidx.compose.ui.Modifier) -> Unit)? = null

    private val current = HashMap<String, String>()
    private val observers = HashMap<String, MutableList<(String) -> Unit>>()

    /** The child page a section opens on when nothing was chosen: Phone keeps its long-standing
     *  Declared default (the mode Profile > Store deep-links into); any other section opens on its first. */
    fun defaultPage(section: String, pages: List<String>): String =
        (if (section == StorePhoneFragment.SECTION) StorePhoneFragment.PAGE_DECLARED else null)
            ?.takeIf { it in pages } ?: pages.first()

    fun page(section: String, default: String): String = synchronized(this) { current[section] } ?: default

    fun select(section: String, page: String) {
        val listeners = synchronized(this) {
            if (current[section] == page) return
            current[section] = page
            observers[section]?.toList().orEmpty()
        }
        listeners.forEach { it(page) }
    }

    /** Calls [fn] on every later change of [section]'s page; the returned lambda stops it. */
    fun observe(section: String, fn: (String) -> Unit): () -> Unit {
        synchronized(this) { observers.getOrPut(section) { ArrayList() }.add(fn) }
        return { synchronized(this) { observers[section]?.remove(fn) } }
    }
}
