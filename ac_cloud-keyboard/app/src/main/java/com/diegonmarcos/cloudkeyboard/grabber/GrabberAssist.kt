package com.diegonmarcos.cloudkeyboard.grabber

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * #1825 accessibility-free Grabber: the default digital assistant. When Cloud Keyboard holds ROLE_ASSISTANT, a long-press of the
 * side/home button starts [GrabberVoiceSession]; Android hands it the AssistStructure (every view with its text and bounds) and,
 * when the user allows it in the assistant settings, a screenshot. No accessibility permission. The session flattens the structure
 * to [TextBlock]s ([AssistFlatten], pure) and opens [GrabberAssistActivity], where blocks are picked by tap or drag; the screenshot
 * is read by the same on-device OCR as Screen Selection (#1754) for text that exists only as pixels.
 */

/** A node of the assist structure, copied into plain data: the flattening is a JVM test. Child bounds are relative to the parent. */
class AssistNode(
    val text: String?, val desc: String?,
    val left: Int, val top: Int, val width: Int, val height: Int,
    val scrollX: Int = 0, val scrollY: Int = 0,
    val visible: Boolean = true, val password: Boolean = false,
    val children: List<AssistNode> = emptyList(),
)

/** One piece of on-screen text with its absolute bounds in screen pixels. */
data class TextBlock(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun intersects(l: Int, t: Int, r: Int, b: Int) = left < r && right > l && top < b && bottom > t
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

object AssistFlatten {
    const val MAX_BLOCKS = 400

    /**
     * Every visible, non-password node with text, in reading order (top, then left), at absolute screen bounds: a child's bounds are
     * the parent's absolute origin plus the child's left/top minus the parent's scroll. Invisible subtrees, empty or zero-size nodes,
     * off-screen blocks (when [screenW]/[screenH] are given) and exact duplicates are dropped. A leaf with no text uses its
     * content description (an icon button's label).
     */
    fun flatten(roots: List<AssistNode>, screenW: Int = 0, screenH: Int = 0, max: Int = MAX_BLOCKS): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        fun walk(n: AssistNode, ox: Int, oy: Int) {
            if (!n.visible) return
            val l = ox + n.left; val t = oy + n.top
            val label = n.text?.trim()?.takeIf { it.isNotEmpty() } ?: if (n.children.isEmpty()) n.desc?.trim()?.takeIf { it.isNotEmpty() } else null
            if (label != null && !n.password && n.width > 0 && n.height > 0) {
                val b = TextBlock(label, l, t, l + n.width, t + n.height)
                val onScreen = (screenW <= 0 || screenH <= 0) || b.intersects(0, 0, screenW, screenH)
                if (onScreen) out += b
            }
            if (!n.password) n.children.forEach { walk(it, l - n.scrollX, t - n.scrollY) }
        }
        roots.forEach { walk(it, 0, 0) }
        return out.distinct().sortedWith(compareBy({ it.top }, { it.left })).take(max)
    }

    /** The blocks picked, in reading order, one per line. */
    fun join(picked: Collection<TextBlock>): String =
        picked.sortedWith(compareBy({ it.top }, { it.left })).joinToString("\n") { it.text }

    fun hit(blocks: List<TextBlock>, x: Int, y: Int): TextBlock? =
        blocks.filter { it.contains(x, y) }.minByOrNull { (it.right - it.left).toLong() * (it.bottom - it.top) }

    fun inRect(blocks: List<TextBlock>, l: Int, t: Int, r: Int, b: Int): List<TextBlock> =
        blocks.filter { it.intersects(minOf(l, r), minOf(t, b), maxOf(l, r) + 1, maxOf(t, b) + 1) }
}

/** One grab handed from the session to the activity (same process, memory only). */
class AssistGrab(val blocks: List<TextBlock>, val screenW: Int, val screenH: Int, val pkg: String, val at: Long)

object GrabberAssistBus {
    var current by mutableStateOf<AssistGrab?>(null)
    var screenshot by mutableStateOf<Bitmap?>(null)
}

/** The role row of the Grabber page, pure: what is shown and which settings pages are tried, in order. */
object GrabberAssistRole {
    enum class State { HELD, NOT_HELD, UNAVAILABLE }

    fun state(sdk: Int, roleAvailable: Boolean, held: Boolean): State = when {
        sdk >= 29 && !roleAvailable -> State.UNAVAILABLE
        held -> State.HELD
        else -> State.NOT_HELD
    }

    /** Settings > Apps > Default apps > Digital assistant app: the voice-input page, the role request, the default-apps list, Settings. */
    val SETTINGS_ACTIONS = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, "ROLE_REQUEST", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)

    fun held(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
            return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        val cur = Settings.Secure.getString(ctx.contentResolver, "assistant").orEmpty()
        return cur.substringBefore('/') == ctx.packageName
    }

    fun current(ctx: Context): State = state(Build.VERSION.SDK_INT,
        Build.VERSION.SDK_INT < 29 || ctx.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true, held(ctx))

    /** Opens the first of [SETTINGS_ACTIONS] that starts; false when none did. */
    fun openSettings(ctx: Context): Boolean {
        for (a in SETTINGS_ACTIONS) {
            val i = if (a == "ROLE_REQUEST") {
                if (Build.VERSION.SDK_INT < 29) continue
                ctx.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) ?: continue
            } else Intent(a)
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}

/** The service Android binds while Cloud Keyboard is the assistant. Nothing to do on its own: the session does the work. */
class GrabberVoiceService : VoiceInteractionService()

/** Required by the platform's voice-interaction-service XML on some versions; this app does no speech recognition through it. */
class GrabberRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

class GrabberVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = GrabberVoiceSession(this)
}

/** One long-press of the assistant gesture: structure first, then (optionally) the screenshot. */
class GrabberVoiceSession(private val ctx: Context) : VoiceInteractionSession(ctx) {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false) // no assistant window of its own: the panel is a normal activity
    }

    override fun onCreateContentView(): View? = null

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        GrabberAssistBus.screenshot = null
        val roots = ArrayList<AssistNode>()
        var pkg = ""
        if (structure != null) {
            pkg = structure.activityComponent?.packageName.orEmpty()
            var budget = 5000
            fun conv(n: AssistStructure.ViewNode): AssistNode {
                val kids = ArrayList<AssistNode>()
                for (k in 0 until n.childCount) { if (budget-- <= 0) break; kids += conv(n.getChildAt(k)) }
                return AssistNode(n.text?.toString(), n.contentDescription?.toString(), n.left, n.top, n.width, n.height, n.scrollX, n.scrollY,
                    n.visibility == View.VISIBLE, SelectionTrigger.isPasswordInput(n.inputType), kids)
            }
            for (w in 0 until structure.windowNodeCount) {
                val wn = structure.getWindowNodeAt(w)
                roots += AssistNode(null, null, wn.left, wn.top, wn.width, wn.height, 0, 0, true, false, listOf(conv(wn.rootViewNode)))
            }
        }
        val dm = ctx.resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        val grab = AssistGrab(AssistFlatten.flatten(roots, sw, sh), sw, sh, pkg, System.currentTimeMillis())
        GrabberAssistBus.current = grab
        val i = Intent(ctx, GrabberAssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (runCatching { startAssistantActivity(i) }.isFailure) runCatching { ctx.startActivity(i) }
        // the screenshot, when allowed, arrives after this call: keep the session a few seconds, then let it go
        handler.postDelayed({ runCatching { hide() } }, 6000)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        GrabberAssistBus.screenshot = screenshot
    }
}
