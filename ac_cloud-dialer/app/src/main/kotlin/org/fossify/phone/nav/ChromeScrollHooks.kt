package org.fossify.phone.nav

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

/**
 * #1760 the finger of the Fossify View pages, fed into the same collapse connection as their scroll. A scroll alone cannot restore the
 * bars once a held drag has carried the page back to its top (Home): a new downward swipe there moves nothing, so no scroll event arrives.
 * The Compose and WebView hosts restore from the finger's own downward travel (BottomNavCollapse.onGestureUp, per gesture); this feeds the
 * View pages the same three signals, with the touch thresholds of libs:bottomnav's ChromeWebScroll.
 */
class ChromeTouchFeed(private val onFinger: (Boolean) -> Unit, private val onGestureUp: () -> Unit, private val onRelease: () -> Unit) {
    private var lastY = 0f

    fun onTouch(action: Int, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> { onFinger(true); lastY = y }
            // the finger travelled DOWN the screen: it reads up the page, the bars come back (unless this same gesture hid them)
            MotionEvent.ACTION_MOVE -> { if (y - lastY > SLOP) onGestureUp(); lastY = y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onFinger(false); onRelease() }
        }
    }

    companion object { const val SLOP = 12f }
}

/** The page root that watches every touch below it (children included) and hands it to a [ChromeTouchFeed]; it never consumes one. */
class ChromeTouchFrame(context: Context, private val feed: ChromeTouchFeed) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        feed.onTouch(ev.actionMasked, ev.y)
        return super.dispatchTouchEvent(ev)
    }
}
