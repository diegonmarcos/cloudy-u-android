package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R
import androidx.activity.ComponentActivity

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.diegonmarcos.cloudwriter.core.Answer
import com.diegonmarcos.cloudwriter.core.CatalogueModel
import com.diegonmarcos.cloudwriter.core.OpenRouter
import com.diegonmarcos.cloudwriter.core.Route
import com.diegonmarcos.cloudwriter.ui.ChoiceRow
import com.diegonmarcos.cloudwriter.ui.SettingRow
import com.diegonmarcos.cloudwriter.ui.ToggleRow
import java.util.Locale
import java.util.concurrent.Executors

/**
 * PAGE 5 — Routes (#800): one switch per function, the Camera/Calc #799 shape.
 *
 *   Speech-to-text (Listen)   Model (OpenRouter, Account token)  |  On-device ML (Vosk, offline)
 *   Translation               Model (OpenRouter, Account token)  |  On-device ML (ML Kit, offline)
 *
 * Default = Model, with an automatic fallback to on-device when offline, with no token, or on an
 * error; the editor and /api/writer/route say which route answered. The model pickers list the LIVE
 * OpenRouter catalogue filtered by what each function needs — audio in for speech, text in for
 * translation — because decision models (typesafe/jev-*) can do neither. Listen's language and its
 * auto-translate target and layout live here too.
 */
class RoutesPage(activity: ComponentActivity) : WriterSettingsPage(activity) {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val catalogue: MutableState<List<CatalogueModel>> = mutableStateOf(emptyList())
    private val catalogueNote: MutableState<String?> = mutableStateOf(null)
    private val accountNote: MutableState<String?> = mutableStateOf(null)
    private val generation: MutableState<Int> = mutableStateOf(0)

    override fun pageTitle(): String = getString(R.string.settings_screen_routes)

    @Composable
    override fun PageContent() {
        generation.value.let { }
        val routes = listOf(
            getString(R.string.route_model) to Route.MODEL.id,
            getString(R.string.route_ml) to Route.ML.id,
        )

        Category(getString(R.string.routes_speech))
        Group {
            ChoiceRow(
                getString(R.string.routes_route_title),
                getString(R.string.routes_speech_summary),
                routes,
                WriterRoutes.route(this, WriterRoutes.Function.SPEECH).id,
                WriterRoutes.defaultRoute(WriterRoutes.Function.SPEECH).id,
            ) { WriterRoutes.setRoute(this, WriterRoutes.Function.SPEECH, Route.of(it, Route.MODEL)); generation.value++ }
            ChoiceRow(
                getString(R.string.routes_model_title),
                getString(R.string.routes_speech_model_summary),
                modelItems(OpenRouter.speechModels(catalogue.value), WriterRoutes.Function.SPEECH),
                WriterRoutes.model(this, WriterRoutes.Function.SPEECH),
                WriterRoutes.defaultModel(WriterRoutes.Function.SPEECH),
            ) { WriterRoutes.setModel(this, WriterRoutes.Function.SPEECH, it) }
            ChoiceRow(
                getString(R.string.routes_listen_language),
                getString(R.string.routes_listen_language_summary),
                listOf(getString(R.string.routes_language_auto) to WriterRoutes.LANGUAGE_AUTO) + languages(),
                WriterRoutes.listenLanguage(this),
                WriterRoutes.LANGUAGE_AUTO,
            ) { WriterPrefs.put(this, WriterRoutes.KEY_LISTEN_LANGUAGE, it) }
            Note(lastLine(WriterRoutes.lastSpeech))
        }

        Category(getString(R.string.routes_translation))
        Group {
            ChoiceRow(
                getString(R.string.routes_route_title),
                getString(R.string.routes_translation_summary),
                routes,
                WriterRoutes.route(this, WriterRoutes.Function.TRANSLATION).id,
                WriterRoutes.defaultRoute(WriterRoutes.Function.TRANSLATION).id,
            ) { WriterRoutes.setRoute(this, WriterRoutes.Function.TRANSLATION, Route.of(it, Route.MODEL)); generation.value++ }
            ChoiceRow(
                getString(R.string.routes_model_title),
                getString(R.string.routes_translation_model_summary),
                modelItems(OpenRouter.textModels(catalogue.value), WriterRoutes.Function.TRANSLATION),
                WriterRoutes.model(this, WriterRoutes.Function.TRANSLATION),
                WriterRoutes.defaultModel(WriterRoutes.Function.TRANSLATION),
            ) { WriterRoutes.setModel(this, WriterRoutes.Function.TRANSLATION, it) }
            Note(lastLine(WriterRoutes.lastTranslation))
        }

        Category(getString(R.string.routes_listen_translate))
        Group {
            ToggleRow(
                getString(R.string.routes_listen_translate_title),
                getString(R.string.routes_listen_translate_summary),
                WriterRoutes.listenTranslate(this),
            ) { WriterPrefs.putFlag(this, WriterRoutes.KEY_LISTEN_TRANSLATE, it) }
            ChoiceRow(
                getString(R.string.routes_listen_target),
                null,
                languages(),
                WriterRoutes.listenTarget(this),
                WriterRoutes.translation.optString("default_target", "english"),
            ) { WriterPrefs.put(this, WriterRoutes.KEY_LISTEN_TARGET, it) }
            ChoiceRow(
                getString(R.string.routes_listen_mode),
                null,
                listOf(
                    getString(R.string.routes_mode_alongside) to "alongside",
                    getString(R.string.routes_mode_instead) to "instead",
                ),
                WriterRoutes.listenMode(this),
                "alongside",
            ) { WriterPrefs.put(this, WriterRoutes.KEY_LISTEN_MODE, it) }
        }

        Category(getString(R.string.routes_catalogue))
        Group {
            SettingRow(
                getString(R.string.routes_refresh),
                getString(R.string.routes_refresh_summary),
                catalogueNote.value,
            ) { refreshCatalogue() }
            accountNote.value?.let { Note(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        catalogue.value = WriterRoutes.cachedCatalogue(this)
        if (catalogue.value.isNotEmpty()) catalogueNote.value = countLine(catalogue.value)
        worker.execute {
            val key = WriterRoutes.accountHasKey(this)
            val engine = VoiceEngineBinder(this).installed()
            val line = getString(
                R.string.routes_account_line,
                getString(if (key) R.string.routes_yes else R.string.routes_no),
                getString(if (engine) R.string.routes_yes else R.string.routes_no),
                WriterRoutes.voiceEngine.optString("label"),
            )
            main.post { if (!activity.isFinishing) accountNote.value = line }
        }
        generation.value++
    }

    private fun refreshCatalogue() {
        catalogueNote.value = getString(R.string.routes_refreshing)
        worker.execute {
            val r = WriterRoutes.fetchCatalogue(this)
            main.post {
                if (activity.isFinishing) return@post
                r.onSuccess { catalogue.value = it; catalogueNote.value = countLine(it) }
                    .onFailure { catalogueNote.value = getString(R.string.routes_refresh_failed, it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun countLine(all: List<CatalogueModel>): String =
        getString(R.string.routes_catalogue_count, OpenRouter.speechModels(all).size, OpenRouter.textModels(all).size)

    /** The catalogue's models for one function, cheapest first; the chosen and default ids always present. */
    private fun modelItems(models: List<CatalogueModel>, f: WriterRoutes.Function): List<Pair<String, String>> {
        val rows = models.map { m ->
            val price = m.promptUsdPerMillion?.let { " — \$" + String.format(Locale.US, "%.3f", it) + "/M" } ?: ""
            (m.name + price) to m.id
        }
        val ids = rows.map { it.second }.toSet()
        val keep = listOf(WriterRoutes.model(this, f), WriterRoutes.defaultModel(f)).distinct().filter { it.isNotBlank() && it !in ids }
        return keep.map { it to it } + rows
    }

    /** Language Output rows that carry an on-device tag (everything but "keep my language"). */
    private fun languages(): List<Pair<String, String>> =
        WriterRegistry.languages.filter { WriterRoutes.tagOf(it.id) != null }.map { it.label to it.id }

    private fun lastLine(a: Answer?): String = when {
        a == null -> getString(R.string.routes_last_none)
        !a.ok -> getString(R.string.routes_last_failed, a.error.orEmpty())
        a.fellBack -> getString(R.string.routes_last_fell_back, routeName(a.route), a.fallbackReason.orEmpty())
        else -> getString(R.string.routes_last_ok, routeName(a.route))
    }

    private fun routeName(r: Route?): String = getString(if (r == Route.ML) R.string.route_ml else R.string.route_model)
}

