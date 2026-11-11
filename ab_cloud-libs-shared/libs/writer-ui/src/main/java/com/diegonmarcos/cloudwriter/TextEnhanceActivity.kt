package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R
import androidx.activity.ComponentActivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwriter.ui.ActionRow
import com.diegonmarcos.cloudwriter.ui.ChoiceRow
import com.diegonmarcos.cloudwriter.ui.PageGutter
import com.diegonmarcos.cloudwriter.ui.SectionHeader
import com.diegonmarcos.cloudwriter.ui.ToggleRow
import com.diegonmarcos.cloudwriter.ui.WriterTextField

/**
 * PAGE 1 — "Mejoras de texto" / Text Enhancements.
 *
 * The same eight rows as cloud-keyboard's `TextEnhanceScreen`, in the same order, with the same
 * wording and the same defaults — and every one of them stored in cloud-writer's own preference
 * file. Change the tone here and the keyboard's tone does not move; change it in the keyboard and
 * this page does not move. That is what the owner asked for and what task 209 did not deliver.
 *
 *   1  Qué se mejora            list     auto | selection | field          default auto
 *   2  Estilo de mejora         list     writer_ai.styles                  default clarity
 *   3  Tono                     list     writer_ai.tones                   default keep
 *   4  Extensión                list     writer_ai.lengths                 default keep
 *   5  Idioma del resultado     list     writer_ai.languages               default keep
 *   6  Mostrar en la barra…     switch                                     default on
 *   7  La instrucción que se envía   read-only, GENERATED
 *   8  Pruébalo                 live run against the settings above
 *
 * ROW 7 IS GENERATED, NOT WRITTEN DOWN. It is `WriterPrefs.enhancePrompt(this)` — the very call
 * [WriterToolRunner] makes when the owner taps Text Enhance on the main screen — so the page cannot
 * describe a request the application does not send. It is also re-read after every pick on this
 * page, so changing the tone changes the paragraph under the owner's thumb immediately. A preview
 * that happened to match today and was a literal would be a lie a week later, and this is the third
 * time that class of defect has been paid for here.
 *
 * HOW "RE-READ AFTER EVERY PICK" WORKS NOW, because the mechanism changed even though the
 * behaviour did not. The View version held the preview's TextView and re-pointed it by hand from
 * [refreshPrompt]; forgetting that call on one menu was a live failure mode and is why the tester
 * counts the call sites. Here [refreshPrompt] bumps [promptGeneration], and [PageContent] READS
 * that state — which is what subscribes this page to it, so a bump re-runs the body and the row is
 * handed a freshly composed prompt. The four shaping menus still call [refreshPrompt] and the
 * tester still counts them; what they notify is a state rather than a widget.
 *
 * ROW 8 ACTUALLY RUNS. It goes through [WriterToolRunner], which is the same object and the same
 * binder call the main screen uses, so "it worked in the test box" and "it worked on the button"
 * cannot disagree.
 */
class TextEnhancePage(activity: ComponentActivity) : WriterSettingsPage(activity) {

    private val runner by lazy { WriterToolRunner(this) }

    /** Bumped by [refreshPrompt]; read by [PageContent], which is what makes the preview follow. */
    private val promptGeneration: MutableState<Int> = mutableStateOf(0)

    private val testInput: MutableState<String> = mutableStateOf("")
    private val testOutput: MutableState<String> = mutableStateOf("")
    private val testStatus: MutableState<String?> = mutableStateOf(null)

    override fun pageTitle(): String = getString(R.string.settings_screen_enhance)

    @Composable
    override fun PageContent() {
        Group {
            ChoiceRow(
                getString(R.string.enhance_scope_title),
                getString(R.string.enhance_scope_summary),
                listOf(
                    getString(R.string.enhance_scope_auto) to WriterPrefs.SCOPE_AUTO,
                    getString(R.string.enhance_scope_selection) to WriterPrefs.SCOPE_SELECTION,
                    getString(R.string.enhance_scope_field) to WriterPrefs.SCOPE_FIELD,
                ),
                WriterPrefs.enhanceScope(this),
                WriterPrefs.DEFAULT_ENHANCE_SCOPE,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_SCOPE, it) }

            ChoiceRow(
                getString(R.string.enhance_style_title),
                getString(R.string.enhance_style_summary),
                WriterRegistry.styles.map { it.label to it.id },
                WriterPrefs.enhanceStyleId(this),
                WriterRegistry.defaultStyle,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_STYLE, it); refreshPrompt() }

            // Tone and length are extra prompt lines appended after the style; their "keep" entries
            // carry an empty prompt, so an untouched page sends exactly the plain style prompt.
            ChoiceRow(
                getString(R.string.enhance_tone_title),
                getString(R.string.enhance_tone_summary),
                WriterRegistry.tones.map { it.label to it.id },
                WriterPrefs.enhanceToneId(this),
                WriterRegistry.defaultTone,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_TONE, it); refreshPrompt() }

            ChoiceRow(
                getString(R.string.enhance_length_title),
                getString(R.string.enhance_length_summary),
                WriterRegistry.lengths.map { it.label to it.id },
                WriterPrefs.enhanceLengthId(this),
                WriterRegistry.defaultLength,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_LENGTH, it); refreshPrompt() }

            // Picking anything but "keep" turns the enhancement into a translation as well; the
            // registry lists the five starred languages first, then the rest alphabetically, and
            // JSONObject keeps insertion order, so that IS the menu order.
            ChoiceRow(
                getString(R.string.enhance_language_title),
                getString(R.string.enhance_language_summary),
                WriterRegistry.languages.map { it.label to it.id },
                WriterPrefs.enhanceLanguageId(this),
                WriterRegistry.defaultLanguage,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_ENHANCE_LANGUAGE, it); refreshPrompt() }

            ToggleRow(
                getString(R.string.enhance_toolbar_title),
                getString(R.string.enhance_toolbar_summary),
                WriterPrefs.flag(this, WriterPrefs.KEY_ENHANCE_TOOLBAR_KEY, WriterPrefs.DEFAULT_ENHANCE_TOOLBAR_KEY),
            ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_ENHANCE_TOOLBAR_KEY, it) }
        }
        Note(getString(R.string.enhance_toolbar_writer_note))

        // THE READ THAT MAKES THE PREVIEW FOLLOW THE MENUS. Naming this state here subscribes this
        // composition to refreshPrompt(), so a pick on any of the four shaping menus above re-runs
        // PageContent and the row below is handed a freshly composed prompt. It looks like a
        // statement that does nothing and it is the entire update mechanism: delete it and every
        // menu goes on storing its value while the preview silently freezes, which is precisely
        // the "indistinguishable from a hardcoded paragraph" defect this page has shipped before.
        promptGeneration.value

        Group {
            ReadOnlyRow(
                getString(R.string.enhance_prompt_title),
                getString(R.string.enhance_prompt_summary),
                WriterPrefs.enhancePrompt(this),
            )
        }

        TestBox()
    }

    /**
     * Tell the preview its inputs moved.
     *
     * Not "append the new line" and not "rebuild the string from the ids I just wrote": one
     * composition rule, in [WriterRegistry.enhancePrompt], read from the store the run reads. This
     * only invalidates; the recomposition is what re-reads it.
     */
    private fun refreshPrompt() {
        promptGeneration.value = promptGeneration.value + 1
    }

    /**
     * "Pruébalo" — the settings above, run on the owner's own text, without leaving the screen.
     *
     * THE REAL RUNNER, NOT A REHEARSAL OF ONE. [WriterToolRunner.run] resolves this application's
     * composed prompt, this application's provider and the model pinned for Text Enhance, and
     * makes the binder call to whichever peer serves ITextTools. Everything the main screen's
     * Text Enhance button does, including the refusals — an empty box and a run already in flight
     * each answer with a sentence rather than with nothing.
     */
    @Composable
    private fun TestBox() {
        SectionHeader(getString(R.string.enhance_test_title), Modifier.padding(horizontal = PageGutter))
        Group {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    getString(R.string.enhance_test_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WriterTextField(
                    value = testInput.value,
                    onValueChange = { testInput.value = it },
                    label = getString(R.string.enhance_test_hint),
                    minLines = 3,
                )
                ActionRow {
                    Button(onClick = { runTest() }) { Text(getString(R.string.enhance_test_run)) }
                }
                testStatus.value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                WriterTextField(
                    value = testOutput.value,
                    onValueChange = { testOutput.value = it },
                    label = getString(R.string.enhance_test_output),
                    minLines = 3,
                )
            }
        }
    }

    private fun runTest() {
        testStatus.value = getString(R.string.working, getString(R.string.tool_enhance))
        runner.run(WriterTool.ENHANCE, testInput.value) { outcome ->
            val produced = outcome.text
            if (produced != null) {
                testOutput.value = produced
                testStatus.value = getString(R.string.done, getString(R.string.tool_enhance))
            } else {
                // The engine's own reason, verbatim. A generic apology in its place is how a
                // provider outage, a missing key and an empty box become one state.
                testStatus.value = outcome.error ?: getString(R.string.run_no_reason)
            }
        }
    }
}

