package org.fossify.phone.nav

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.WeakHashMap

/**
 * #1164 the scroll of the Fossify View pages, fed into the shared chrome collapse (BottomNavCollapse.onScrollDelta): reading down
 * hides the first row and compacts the bottom nav, reading up restores them. A RecyclerView moves its children, not its scrollY,
 * so each one gets a scroll listener; the (Nested)ScrollView pages are read through the window's scroll-changed callback. Pages the
 * pager swaps in later are picked up on the next layout. It only observes; nothing is consumed.
 */
object ChromeScrollHooks {
    fun install(content: ViewGroup, onDelta: (Float) -> Unit) {
        val seen: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
        var lastScrollViews = 0

        fun hook(v: View) {
            if (v is RecyclerView) {
                if (seen.add(v)) {
                    v.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            if (dy != 0) onDelta(dy.toFloat())
                        }
                    })
                }
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) hook(v.getChildAt(i))
            }
        }

        fun scrollViewOffsets(v: View): Int = when (v) {
            is NestedScrollView -> v.scrollY
            is ScrollView -> v.scrollY
            is ViewGroup -> (0 until v.childCount).sumOf { scrollViewOffsets(v.getChildAt(it)) }
            else -> 0
        }

        content.viewTreeObserver.addOnGlobalLayoutListener { hook(content) }
        content.viewTreeObserver.addOnScrollChangedListener {
            val now = scrollViewOffsets(content)
            if (now != lastScrollViews) {
                onDelta((now - lastScrollViews).toFloat())
                lastScrollViews = now
            }
        }
        hook(content)
    }
}
