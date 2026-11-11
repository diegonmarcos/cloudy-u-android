package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R
import androidx.activity.ComponentActivity

import androidx.compose.runtime.Composable
import com.diegonmarcos.cloudwriter.ui.ChoiceRow
import com.diegonmarcos.cloudwriter.ui.ToggleRow

/**
 * PAGE 3 — "Revisión gramatical" / Grammar check.
 *
 * The same seven rows as cloud-keyboard's `GrammarCheckScreen`, in the same order, under the same
 * "Correcciones" heading, with the same wording — stored in cloud-writer's own preference file.
 *
 *   1  Modo de gramática               list    off | local | remote | ai
 *   Correcciones  (section heading)
 *   2  Poner en mayúscula la "i" inglesa    switch  default on
 *   3  Poner en mayúscula el inicio…        switch  default on
 *   4  Eliminar las palabras repetidas      switch  default on
 *   5  URL del servidor de LanguageTool     text    default the mesh endpoint
 *   6  Variante del portugués               list    default Europeo (pt-PT)
 *   7  Conexión con la API de n-gramas      text    default empty
 *
 * THE LANGUAGETOOL URL IS THE MESH ONE AND STAYS THE MESH ONE.
 * `https://languagetool.diegonmarcos.com/v2/check` is infra-ai_languagetool, which answers on the
 * WireGuard mesh and on no public route. It is copied from the keyboard's default character for
 * character; "fixing" it to a public LanguageTool would quietly start sending the owner's text to
 * a third party that has never had it.
 *
 * ROW 1'S DEFAULT IS THE ONE PLACE THESE TWO PAGES DISAGREE ON FIRST OPEN, and it is deliberate —
 * see [WriterPrefs.DEFAULT_GRAMMAR_MODE]. The keyboard defaults to Remoto and can reach
 * LanguageTool itself; cloud-writer opens no socket, so a Remoto default would answer the owner's
 * first tap of Grammar Check with a refusal where today it works. Remoto is still on the menu and
 * still explains itself when chosen. Every one of the four modes is honoured by
 * [WriterToolRunner]; none of them silently does another mode's work.
 *
 * THE UI ENHANCEMENT GROUPED THE ROWS AND CHANGED NOTHING ELSE. Seven settings and two notes used
 * to be one flat column with no visual break anywhere in it; the mode, the three on-phone fixes
 * and the two server addresses are now three surfaces, which is the structure the page always had
 * and never showed. Every stored value, default and key is the one that was there before.
 */
class GrammarCheckPage(activity: ComponentActivity) : WriterSettingsPage(activity) {

    override fun pageTitle(): String = getString(R.string.settings_screen_grammar)

    @Composable
    override fun PageContent() {
        Group {
            ChoiceRow(
                getString(R.string.grammar_mode_title),
                null,
                listOf(
                    getString(R.string.grammar_mode_off) to WriterPrefs.GRAMMAR_OFF,
                    getString(R.string.grammar_mode_local) to WriterPrefs.GRAMMAR_LOCAL,
                    getString(R.string.grammar_mode_remote) to WriterPrefs.GRAMMAR_REMOTE,
                    getString(R.string.grammar_mode_ai) to WriterPrefs.GRAMMAR_AI,
                ),
                WriterPrefs.grammarMode(this),
                WriterPrefs.DEFAULT_GRAMMAR_MODE,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_GRAMMAR_MODE, it) }
        }

        Category(getString(R.string.grammar_category_fixes))

        Group {
            ToggleRow(
                getString(R.string.grammar_fix_capitalize_i),
                null,
                WriterPrefs.flag(this, WriterPrefs.KEY_GRAMMAR_FIX_CAPITALIZE_I, WriterPrefs.DEFAULT_GRAMMAR_FIX_CAPITALIZE_I),
            ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_GRAMMAR_FIX_CAPITALIZE_I, it) }

            ToggleRow(
                getString(R.string.grammar_fix_sentence_caps),
                null,
                WriterPrefs.flag(this, WriterPrefs.KEY_GRAMMAR_FIX_SENTENCE_CAPS, WriterPrefs.DEFAULT_GRAMMAR_FIX_SENTENCE_CAPS),
            ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_GRAMMAR_FIX_SENTENCE_CAPS, it) }

            ToggleRow(
                getString(R.string.grammar_fix_repeated_words),
                null,
                WriterPrefs.flag(this, WriterPrefs.KEY_GRAMMAR_FIX_REPEATED_WORDS, WriterPrefs.DEFAULT_GRAMMAR_FIX_REPEATED_WORDS),
            ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_GRAMMAR_FIX_REPEATED_WORDS, it) }
        }

        Group {
            TextRow(
                getString(R.string.grammar_remote_url_title),
                getString(R.string.grammar_remote_url_summary),
                WriterPrefs.grammarRemoteUrl(this),
                WriterPrefs.DEFAULT_GRAMMAR_REMOTE_URL,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_GRAMMAR_REMOTE_URL, it) }

            ChoiceRow(
                getString(R.string.grammar_pt_variant_title),
                getString(R.string.grammar_pt_variant_summary),
                listOf(
                    getString(R.string.grammar_pt_variant_pt) to "pt-PT",
                    getString(R.string.grammar_pt_variant_br) to "pt-BR",
                ),
                WriterPrefs.string(this, WriterPrefs.KEY_GRAMMAR_PT_VARIANT, WriterPrefs.DEFAULT_GRAMMAR_PT_VARIANT),
                WriterPrefs.DEFAULT_GRAMMAR_PT_VARIANT,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_GRAMMAR_PT_VARIANT, it) }

            // Placeholder for the future n-gram confusion-pair API, exactly as in the keyboard: the
            // row exists, the summary says it is not wired to anything, and it stores what is typed.
            TextRow(
                getString(R.string.grammar_ngram_url_title),
                getString(R.string.grammar_ngram_url_summary),
                WriterPrefs.string(this, WriterPrefs.KEY_GRAMMAR_NGRAM_URL, WriterPrefs.DEFAULT_GRAMMAR_NGRAM_URL),
                getString(R.string.ai_table_unknown),
            ) { WriterPrefs.put(this, WriterPrefs.KEY_GRAMMAR_NGRAM_URL, it) }
        }
    }
}

