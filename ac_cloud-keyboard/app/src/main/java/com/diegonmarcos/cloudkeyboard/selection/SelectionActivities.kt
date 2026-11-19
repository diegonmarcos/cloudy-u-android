package com.diegonmarcos.cloudkeyboard.selection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudkeyboard.R
import com.diegonmarcos.superapp.translate.AppField
import com.diegonmarcos.superapp.translate.BoxText
import com.diegonmarcos.superapp.translate.ClipPort
import com.diegonmarcos.superapp.translate.Generator
import com.diegonmarcos.superapp.translate.InputSource
import com.diegonmarcos.superapp.translate.KeyFocus
import com.diegonmarcos.superapp.translate.KeyOwner
import com.diegonmarcos.superapp.translate.Outcome
import com.diegonmarcos.superapp.translate.PanelButton
import com.diegonmarcos.superapp.translate.Translator
import com.diegonmarcos.superapp.translate.WriterAction
import com.diegonmarcos.superapp.translate.WriterMode
import com.diegonmarcos.superapp.translate.WriterPanel
import com.diegonmarcos.superapp.translate.WriterPanelColors
import com.diegonmarcos.superapp.translate.WriterPanelController
import com.diegonmarcos.superapp.translate.WriterPanelSpec
import helium314.keyboard.latin.AiRouter
import helium314.keyboard.latin.AiTask
import helium314.keyboard.latin.AiTaskRouting
import helium314.keyboard.latin.DraftAnswer
import helium314.keyboard.latin.TextEnhancer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * #1824 the text-selection menu entry of one Writer mode (Translator, Grammar, Enhancer, Drafter): a floating translucent window over
 * the app the text was selected in, hosting the #1818 shared panel ([WriterPanel] + [WriterPanelController]) in [mode] with the selection
 * as input. The "App box" is the selection itself: Replace answers RESULT_OK + EXTRA_PROCESS_TEXT so Android replaces the selection in
 * place, only when the source is editable (EXTRA_PROCESS_TEXT_READONLY false, [SelectionMenu.reply]); read-only text offers Copy and Share.
 */
abstract class SelectionPanelActivity(private val fallbackMode: WriterMode) : ComponentActivity() {
    private val io = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private var seq = 0
    private var inputText by mutableStateOf("")
    private var outputText by mutableStateOf("")
    private var status by mutableStateOf("")
    private var statusIsError by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var source by mutableStateOf(InputSource.APP_BOX)
    private var undoOutput: String? = null
    private lateinit var selected: String
    private var readOnly = true
    private lateinit var mode: WriterMode
    private lateinit var controller: WriterPanelController

    private val inputBox = object : BoxText {
        override fun get() = inputText
        override fun set(text: String) { inputText = text }
    }
    private val outputBox = object : BoxText {
        override fun get() = outputText
        override fun set(text: String) { undoOutput = outputText; outputText = text }
    }

    /** The App box is the selection: read gives it back; replace returns it through the result when the source is editable. */
    private val appField = object : AppField {
        override fun read(): String? = selected.ifEmpty { null }
        override fun replace(text: String): Boolean = deliver(text)
        override fun insert(text: String): Boolean = deliver(text)
    }

    private val clip = object : ClipPort {
        private fun cm() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        override fun read(): String? = cm().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@SelectionPanelActivity)?.toString()
        override fun write(text: String) { cm().setPrimaryClip(ClipData.newPlainText("selection", text)) }
    }

    private fun deliver(text: String): Boolean {
        val r = SelectionMenu.reply(readOnly, text)
        if (!r.replacesSelection) return false
        setResult(r.resultCode, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, r.text))
        finish()
        return true
    }

    private fun generator() = Generator { text, done ->
        val id = ++seq
        busy = true
        status = getString(R.string.selection_working)
        statusIsError = false
        io.execute {
            val result = runCatching { run(text) }
            ui.post { if (id == seq) { busy = false; done(result) } }
        }
    }

    /** The blocking call of the mode: the same engines and AI routes the keyboard's panels use. */
    private fun run(text: String): String = when (mode) {
        WriterMode.TRANSLATE -> {
            val r = Translator.translateNow(applicationContext, text, "", Locale.getDefault().language)
            r.text ?: throw IllegalStateException(r.error ?: getString(R.string.selection_failed))
        }
        WriterMode.GRAMMAR -> TextEnhancer.rewrite(applicationContext, AiRouter.styleById("grammar"), text,
            route = AiTaskRouting.route(applicationContext, AiTask.GRAMMAR))
        WriterMode.ENHANCE -> TextEnhancer.rewrite(applicationContext, AiRouter.enhanceStyle(applicationContext), text,
            route = AiTaskRouting.route(applicationContext, AiTask.ENHANCE))
        WriterMode.DRAFT -> AiRouter.complete(applicationContext, AiRouter.answerStyle(applicationContext).prompt,
            text.take(AiRouter.maxChars), AiTaskRouting.route(applicationContext, DraftAnswer.MODEL_TASK)).trim()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = SelectionMenu.modeOf(componentName.className) ?: fallbackMode
        selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        controller = WriterPanelController(mode, appField, clip, inputBox, outputBox, KeyFocus(KeyOwner.INPUT), generator(), InputSource.APP_BOX)
        controller.selectSource(InputSource.APP_BOX)
        setContent { Content() }
    }

    private fun say(o: Outcome) {
        statusIsError = o == Outcome.FAILED || o == Outcome.NO_FIELD
        status = when (o) {
            Outcome.OK -> ""
            Outcome.EMPTY_INPUT -> getString(R.string.selection_empty_input)
            Outcome.EMPTY_OUTPUT -> getString(R.string.selection_empty_output)
            Outcome.NO_FIELD -> getString(if (readOnly) R.string.selection_read_only else R.string.selection_no_field)
            Outcome.NOT_APPLICABLE -> ""
            Outcome.FAILED -> controller.lastError?.message ?: getString(R.string.selection_failed)
        }
    }

    private fun act(a: WriterAction) {
        when (a) {
            WriterAction.GENERATE -> controller.generate { say(it) }
            WriterAction.AUTO_REPLACE -> controller.autoReplace { say(it) }
            WriterAction.REPLACE -> say(controller.replace())
            WriterAction.PASTE -> say(controller.paste())
            WriterAction.COPY -> say(controller.copy())
        }
    }

    private fun share() {
        val out = outputText.ifBlank { inputText }
        if (out.isBlank()) { say(Outcome.EMPTY_OUTPUT); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out), null))
    }

    @Composable
    private fun Content() {
        val colors = WriterPanelColors(surface = Color(0xFF1B1B1F), chip = Color(0xFF34343A), onChip = Color(0xFFE6E6EA), accent = Color(0xFF8AB4F8),
            onAccent = Color(0xFF0B1B33), text = Color(0xFFE6E6EA), muted = Color(0xFFA6A6AE), outline = Color(0xFF55555C))
        val title = getString(when (mode) {
            WriterMode.TRANSLATE -> R.string.selection_translator_label
            WriterMode.GRAMMAR -> R.string.selection_grammar_label
            WriterMode.ENHANCE -> R.string.selection_enhancer_label
            WriterMode.DRAFT -> R.string.selection_drafter_label
        })
        val spec = WriterPanelSpec(
            mode = mode, title = title, colors = colors, selectors = emptyList(), source = source,
            onSource = { s -> source = s; say(controller.selectSource(s)) },
            onAction = { a -> act(a) },
            extras = listOf(
                // Undo: the output box goes back to what it held before the last result; Share: the result through the share sheet.
                PanelButton("undo", getString(R.string.selection_undo)) { outputText = undoOutput.orEmpty(); undoOutput = null },
                PanelButton("share", getString(R.string.selection_share)) { share() },
            ),
            status = if (readOnly && status.isEmpty()) getString(R.string.selection_read_only) else status,
            statusIsError = statusIsError, busy = busy, onClose = { finish() },
        )
        Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { finish() }.systemBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).clickable(enabled = false) {}) {
                WriterPanel(spec,
                    input = { TextBox(inputText, colors) { inputText = it; controller.onInputEdited(); source = InputSource.OWN } },
                    output = { TextBox(outputText, colors) { outputText = it } },
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    @Composable
    private fun TextBox(value: String, colors: WriterPanelColors, onChange: (String) -> Unit) {
        BasicTextField(value = value, onValueChange = onChange, textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp).verticalScroll(rememberScrollState()).padding(10.dp))
    }

    override fun onDestroy() {
        seq++
        io.shutdownNow()
        super.onDestroy()
    }
}

class SelectionTranslatorActivity : SelectionPanelActivity(WriterMode.TRANSLATE)
class SelectionGrammarActivity : SelectionPanelActivity(WriterMode.GRAMMAR)
class SelectionEnhancerActivity : SelectionPanelActivity(WriterMode.ENHANCE)
class SelectionDrafterActivity : SelectionPanelActivity(WriterMode.DRAFT)
