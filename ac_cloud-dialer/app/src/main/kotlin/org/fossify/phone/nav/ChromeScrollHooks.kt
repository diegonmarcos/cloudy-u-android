package org.fossify.phone.nav

import android.view.MotionEvent
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
    fun install(content: ViewGroup, onDelta: (Float) -> Unit, touch: ChromeTouchFeed? = null) {
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
                // #1760 a (Nested)ScrollView page (Home) reports its own touches to the chrome: a swipe down at the page's top moves no scroll,
                // so only the finger can bring the bars back there (the Compose watcher loses the Fossify pager's stream). Never consumes.
                if (touch != null && (v is ScrollView || v is NestedScrollView) && seen.add(v)) {
                    v.setOnTouchListener { _, e -> touch.onTouch(e.actionMasked, e.y); false }
                }
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

/**
 * #1760 the finger of a View page as the three signals of BottomNavCollapse (onFinger, onGestureUp on a downward travel, onRelease), with the
 * touch thresholds of libs:bottomnav's ChromeWebScroll. Plain Kotlin: it holds no View.
 */
class ChromeTouchFeed(private val onFinger: (Boolean) -> Unit, private val onGestureUp: () -> Unit, private val onRelease: () -> Unit) {
    private var lastY = 0f

    fun onTouch(action: Int, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> { onFinger(true); lastY = y }
            MotionEvent.ACTION_MOVE -> { if (y - lastY > SLOP) onGestureUp(); lastY = y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onFinger(false); onRelease() }
        }
    }

    companion object { const val SLOP = 12f }
}
