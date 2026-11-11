package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R
import androidx.activity.ComponentActivity

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.diegonmarcos.cloudwriter.ui.ChoiceRow
import com.diegonmarcos.cloudwriter.ui.ToggleRow
import java.util.Locale
import java.util.concurrent.Executors

/**
 * PAGE 2 — "Traducción" / Translation.
 *
 * The same four rows as cloud-keyboard's `TranslationInfoScreen`, under the same
 * "Barra de traducción" heading, with the same wording and the same defaults, stored in
 * cloud-writer's own preference file.
 *
 *   Barra de traducción  (section heading)
 *   1  Idioma de destino predeterminado   list    default "" = Idioma del teclado activo
 *   2  Detectar automáticamente…          switch  default on
 *   3  Intro o acción principal           list    default Insertar en el cursor
 *   4  Escribir directamente en el campo  switch  default on
 *
 * ONLY ROW 1 REACHES THIS APPLICATION'S TRANSLATE BUTTON, and the page says so in
 * `translate_writer_note` rather than leaving it to be found out. Rows 2, 3 and 4 describe what a
 * translate BAR does — detect the source as you type, what Enter does, whether the translation is
 * committed into the app's own field — and cloud-writer has a box and a button, not a bar sitting
 * over someone else's text field. They are stored, they are this application's own, and they are
 * here because the owner asked for the same pages; a row quietly missing is a page that does not
 * match, and a row quietly doing nothing is worse than either.
 *
 * THE WORDING SAYS "TECLADO ACTIVO" AND IS COPIED THAT WAY ON PURPOSE. In cloud-writer that phrase
 * is the keyboard's, not this app's. It is a question for the owner and not a decision to take
 * here: changing it silently is how the pages stopped matching last time.
 */
class TranslationPage(activity: ComponentActivity) : WriterSettingsPage(activity) {

    /**
     * The engine's own list once it answers; the fallback is drawn meanwhile.
     *
     * A Compose state rather than a @Volatile field with a hand-written page rebuild: the row below
     * READS it during composition, so the arrival of the real list redraws the picker because there
     * is nothing else it could do. The View version had to call rebuild() from the worker's post,
     * guarded against looping.
     */
    private val engineLanguages: MutableState<List<String>?> = mutableStateOf(null)

    /** ONE for the whole page. A runner per call would be a second binding to the same service. */
    private val runner by lazy { WriterToolRunner(this) }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun pageTitle(): String = getString(R.string.settings_screen_translation)

    @Composable
    override fun PageContent() {
        Category(getString(R.string.translate_category_behavior))

        Group {
            // "" is a real stored value meaning "let the engine decide", and it is the first entry
            // rather than a missing one: a picker whose default is absent cannot show what is set.
            val tags = engineLanguages.value ?: WriterRegistry.translateFallbackLanguages
            val items = listOf(getString(R.string.translate_default_target_active) to "") +
                tags.sortedBy { Locale(it).displayLanguage }.map { languageName(it) to it }
            ChoiceRow(
                getString(R.string.translate_default_target_title),
                null,
                items,
                WriterPrefs.translateTarget(this),
                "",
            ) { WriterPrefs.put(this, WriterPrefs.KEY_TRANSLATE_TARGET, it) }

            ToggleRow(
                getString(R.string.translate_auto_detect_title),
                getString(R.string.translate_auto_detect_summary),
                WriterPrefs.flag(this, WriterPrefs.KEY_TRANSLATE_AUTO_DETECT, WriterPrefs.DEFAULT_TRANSLATE_AUTO_DETECT),
            ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_TRANSLATE_AUTO_DETECT, it) }

            ChoiceRow(
                getString(R.string.translate_apply_mode_title),
                null,
                listOf(
                    getString(R.string.translate_apply_insert) to WriterPrefs.APPLY_INSERT,
                    getString(R.string.translate_apply_replace) to WriterPrefs.APPLY_REPLACE,
                ),
                WriterPrefs.string(this, WriterPrefs.KEY_TRANSLATE_APPLY_MODE, WriterPrefs.DEFAULT_TRANSLATE_APPLY_MODE),
                WriterPrefs.DEFAULT_TRANSLATE_APPLY_MODE,
            ) { WriterPrefs.put(this, WriterPrefs.KEY_TRANSLATE_APPLY_MODE, it) }

            ToggleRow(
                getString(R.string.translate_live_commit_title),
                getString(R.string.translate_live_commit_summary),
                WriterPrefs.flag(this, WriterPrefs.KEY_TRANSLATE_LIVE_COMMIT, WriterPrefs.DEFAULT_TRANSLATE_LIVE_COMMIT),
            ) { WriterPrefs.putFlag(this, WriterPrefs.KEY_TRANSLATE_LIVE_COMMIT, it) }
        }

        Note(getString(R.string.translate_writer_note))
    }

    /**
     * Replace the fallback list with the engine's real one.
     *
     * BLOCKS — it is a binder call — so it runs on [worker] and the result is posted back to the
     * main thread, where writing the state recomposes the picker. Asked ONCE, from onResume rather
     * than from the page body: a binder call started during composition would be started again on
     * every recomposition, which is a new bind per keystroke-equivalent redraw.
     */
    override fun onResume() {
        super.onResume()
        if (engineLanguages.value != null) return
        worker.execute {
            val reported = runner.translateLanguages()
            if (reported.isEmpty()) return@execute
            main.post { if (!activity.isFinishing) engineLanguages.value = reported }
        }
    }

    private fun languageName(tag: String): String = Locale(tag).displayLanguage + " (" + tag + ")"
}

/** The activity shell: the manifest declares this class, the page is [TranslationPage]. */
class TranslationActivity : WriterSettingsActivity() {
    override fun createPage(): WriterSettingsPage = TranslationPage(this)
}
