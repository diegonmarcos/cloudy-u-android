package app.sterna.ui.message

/**
 * #1869 step 10: a small pool of the reader's body views, so a page (or a swipe to the next one) reuses a WebView
 * instead of building one (the renderer start-up is the bulk of a page's first frame).
 *
 * Generic over the view and its context so a JVM test drives it; the reader instantiates it with its `BodyWebView` on a
 * `MutableContextWrapper` ([rebind] points it at the page's context, [reset] back at the application's, so an idle
 * view holds no activity). [reset] runs on EVERY release, before the view is kept or destroyed: the page it showed is
 * blanked and its per-load state cleared, so a reused view can never show, report or settle the previous message.
 * At most [max] views are kept idle; a surplus one is [destroy]ed. Main thread only, like every view.
 */
internal class ReaderViewPool<C, T : Any>(
    private val max: Int,
    private val create: (context: C) -> T,
    private val rebind: (view: T, context: C) -> Unit,
    private val reset: (view: T) -> Unit,
    private val destroy: (view: T) -> Unit,
    /** False while a released view is still blanking: it is not handed out until its blank page has finished. */
    private val ready: (view: T) -> Boolean = { true },
) {
    private val idle = ArrayDeque<T>()

    /** An idle view rebound to [context], or a new one. Never the same instance twice without a release between. */
    fun acquire(context: C): T {
        val view = idle.firstOrNull(ready) ?: return create(context)
        idle.remove(view)
        rebind(view, context)
        return view
    }

    /** Back to the pool, reset; past [max] idle views it is destroyed instead. */
    fun release(view: T) {
        reset(view)
        if (idle.size < max && idle.none { it === view }) idle.addLast(view) else if (idle.none { it === view }) destroy(view)
    }

    val idleCount: Int get() = idle.size
}
