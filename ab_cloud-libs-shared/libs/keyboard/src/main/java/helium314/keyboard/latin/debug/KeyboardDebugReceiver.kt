// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import android.view.inputmethod.ExtractedTextRequest
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.autofill.AutocompleteContract
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.emoji.EmojiSearchUi
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AiEngine
import helium314.keyboard.latin.AiTask
import helium314.keyboard.latin.AiTaskModels
import helium314.keyboard.latin.AiTaskRouting
import helium314.keyboard.latin.GrammarHighlights
import helium314.keyboard.latin.GrammarPopup
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.asStore
import helium314.keyboard.latin.autocomplete.AutocompletePage
import helium314.keyboard.latin.autocomplete.AutocompleteStore
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.GrammarMode
import helium314.keyboard.latin.utils.prefs

/**
 * The CI screenshot job's hands (ship-cloud-keyboard.yml, ac_cloud-keyboard/ci/keyboard-screenshots.sh). Registered ONLY in
 * a debuggable build, only for a sender holding android.permission.DUMP (the adb shell), so a release keyboard has no such
 * door. Each command does what a tap would do, deterministically, and logs one line under [TAG] the job reads back:
 *
 *   adb shell am broadcast -a com.diegonmarcos.cloudkeyboard.debug.CMD --es cmd <cmd> --es arg <arg>
 *
 *   code <KEY>    press a key code by name (ALPHA, EMOJI, AUTOCOMPLETE, GRAMMAR_MODE, ...)
 *   text <chars>  type the characters (into the emoji search box when it is open)
 *   search        what a tap on the emoji panel's search field sends (EmojiSearchUi.TAP_CODES)
 *   page <id>     show an Autocomplete page (ids, addresses, urls, secrets)
 *   seed <json>   replace the Autocomplete store with the rows of <json> (made-up rows only)
 *   geometry      log where the toolbar strip is on the screen
 *   grammar <id>  set the Grammar Check mode (auto_correct | highlight | off)
 *   select <n|end> move the cursor (a tap into a highlighted word opens its popup)
 *   popup <replace|ignore> press the highlight popup action
 *   marks         log the highlight marks, the ignores, the popup and the field text
 *   state         log the panel, the grammar mode, the Autocomplete page and the emoji search box height
 */
class KeyboardDebugReceiver(private val ime: LatinIME) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("arg").orEmpty()
        val result = runCatching { run(cmd, arg) }.getOrElse { "error ${it.javaClass.simpleName}: ${it.message}" }
        Log.i(TAG, "$cmd -> $result")
    }

    private fun press(code: Int) = ime.mKeyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)

    private fun run(cmd: String, arg: String): String {
        val switcher = KeyboardSwitcher.getInstance()
        return when (cmd) {
            "code" -> { press(code(arg)); "ok" }
            "text" -> { arg.codePoints().forEach { press(it) }; "ok" }
            "search" -> { EmojiSearchUi.TAP_CODES.forEach { press(it) }; "ok" }
            "page" -> {
                val p = AutocompletePage.of(arg) ?: return "unknown page"
                switcher.autocompleteView.showPage(p); "ok"
            }
            "seed" -> {
                val rows = AutocompleteContract.parse(arg)
                AutocompleteStore.open(ime).replaceAll(rows, System.currentTimeMillis())
                "seeded ${rows.size}"
            }
            "geometry" -> {
                val strip = switcher.stripContainer
                val at = IntArray(2).also { strip.getLocationOnScreen(it) }
                "strip=${at[0]},${at[1]},${strip.width},${strip.height} density=${ime.resources.displayMetrics.density}"
            }
            "state" -> {
                val ac = switcher.autocompleteView
                "mode=${switcher.viewMode} grammar=${GrammarMode.read(ime.prefs()).id} autocompletePage=${ac.currentPage.id} " +
                    "rows=${ac.rowCount} emojiBarActive=${ime.isEmojiSearchBarActive} emojiBarHeightPx=${ime.emojiSearchBarHeightPx} " +
                    "density=${ime.resources.displayMetrics.density}"
            }
            // Grammar Check ▸ Highlight popup (CI screenshots): set the mode, move the cursor, press the popup's actions.
            "grammar" -> { GrammarMode.write(ime.prefs(), GrammarMode.fromId(arg) ?: return "unknown mode"); "ok" }
            "select" -> {
                val ic = ime.currentInputConnection ?: return "no connection"
                val at = if (arg == "end") fieldText().length else arg.toInt()
                ic.setSelection(at, at); "ok $at"
            }
            "popup" -> when (arg) {
                "replace" -> "replace=${GrammarPopup.replace(ime)}"
                "ignore" -> "ignore=${GrammarPopup.ignore(ime)}"
                else -> "unknown action"
            }
            // Configs ▸ AI Routing (CI screenshots): "aimodel translate=google/gemini-2.5-flash", "airoute grammar=openrouter".
            "aimodel" -> {
                val (t, id) = arg.split("=", limit = 2)
                AiTaskModels.set(ime.prefs().asStore(), AiTask.entries.first { it.id == t }, id); "ok"
            }
            "airoute" -> {
                val (t, e) = arg.split("=", limit = 2)
                val task = AiTask.entries.first { it.id == t }
                AiTaskRouting.choose(ime.prefs().asStore(), task, AiEngine.fromId(e) ?: return "unknown engine")
                val r = AiTaskRouting.resolution(ime, task)
                "chosen=${r.chosen.id} active=${r.active?.id ?: "none"} fallback=${r.fallback?.id ?: "none"} reason=${r.reason}"
            }
            "marks" -> {
                val s = GrammarHighlights.session
                "marks=${s.all.size} ignored=${s.ignoredCount} popup=${GrammarPopup.isShowing} " +
                    "shown=${GrammarPopup.shownIssue?.let { "${it.original}->${it.suggestion}@${it.start}" } ?: "-"} " +
                    "at=${s.all.joinToString(",") { "${it.start}..${it.end}" }} field=[${fieldText()}]"
            }
            else -> "unknown command"
        }
    }

    private fun fieldText(): String =
        ime.currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty()

    private fun code(name: String): Int = when (name) {
        "ALPHA" -> KeyCode.ALPHA
        "EMOJI" -> KeyCode.EMOJI
        "EMOJI_SEARCH" -> KeyCode.EMOJI_SEARCH
        "AUTOCOMPLETE" -> KeyCode.AUTOCOMPLETE
        "GRAMMAR_MODE" -> KeyCode.GRAMMAR_MODE
        "CLIPBOARD" -> KeyCode.CLIPBOARD
        "NUMPAD" -> KeyCode.NUMPAD
        else -> error("unknown key $name")
    }

    companion object {
        const val ACTION = "com.diegonmarcos.cloudkeyboard.debug.CMD"
        const val TAG = "KbdShot"

        /** The receiver, registered, in a debuggable build; null (and nothing registered) in any other. */
        @JvmStatic
        fun registerIfDebuggable(ime: LatinIME): KeyboardDebugReceiver? {
            if (ime.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            val receiver = KeyboardDebugReceiver(ime)
            ContextCompat.registerReceiver(ime, receiver, IntentFilter(ACTION), "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED)
            return receiver
        }
    }
}
