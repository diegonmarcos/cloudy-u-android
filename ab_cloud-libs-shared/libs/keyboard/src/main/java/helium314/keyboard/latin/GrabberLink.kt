package helium314.keyboard.latin

import android.content.Context
import android.inputmethodservice.InputMethodService

/**
 * #1793 the keyboard's door to the Grabber, which lives in the app (ac_cloud-keyboard grabber.GrabberActivity), not in this lib.
 * The app sets both hooks in App.onCreate; a build without them (the lib alone) leaves the GRABBER key saying so.
 *
 *  - [opener]: the GRABBER toolbar key opens the Grabber panel on the field's selection (empty when nothing is selected).
 *  - [selectionHook]: LatinIME.onUpdateSelection reports every non-empty selection; the app decides (its own Configs switch,
 *    "Grabber on selection: keyboard", off by default) whether to open the panel, debounced. No permission: the keyboard is the
 *    active input method of that field, so the platform already tells it the selection.
 */
object GrabberLink {
    @JvmField @Volatile var opener: ((Context, String) -> Unit)? = null
    @JvmField @Volatile var selectionHook: ((InputMethodService, Int, Int) -> Unit)? = null

    @JvmStatic fun available(): Boolean = opener != null

    /** true = the app took it. */
    @JvmStatic fun open(ctx: Context, text: String): Boolean {
        val o = opener ?: return false
        return runCatching { o(ctx, text) }.isSuccess
    }

    /** A selection [start, end) changed in the field; never throws into the IME. */
    @JvmStatic fun selectionChanged(ime: InputMethodService, start: Int, end: Int) {
        val h = selectionHook ?: return
        runCatching { h(ime, start, end) }
    }
}
