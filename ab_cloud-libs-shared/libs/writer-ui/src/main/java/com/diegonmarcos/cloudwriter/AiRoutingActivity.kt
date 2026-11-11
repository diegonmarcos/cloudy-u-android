package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R
import androidx.activity.ComponentActivity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudwriter.ui.ChoiceRow
import java.util.Locale

/**
 * PAGE 4 — "Enrutamiento de modelos de IA" / AI Model Routing.
 *
 * The same rows as cloud-keyboard's `AiRoutingScreen`, in the same order, over cloud-writer's own
 * copy of the registry and cloud-writer's own preference file.
 *
 *   1  Proveedor                 list        writer_ai.providers       default openrouter
 *   2  Instrucciones compartidas read-only   the two preambles, GENERATED from the registry
 *   3  Clave de API              READ-ONLY   see DIVERGENCE below
 *   4  Modelo                    list        the chosen provider's models, sorted by size
 *   5  Precios                   table       one line per model, eight columns, scrolls sideways
 *
 * THE ACCUMULATED FIXES THIS TABLE CARRIES, because every one of them was paid for once already:
 *
 *   214  ONE LINE PER MODEL. Eight columns do not fit a phone and the answer is to scroll, not to
 *        fold. Every cell is single-line without exception — one cell wrapping is enough to take a
 *        model onto a second line, which is the layout 214 was raised about. The scroller wraps
 *        the whole table, so the columns cannot drift out from under their headings.
 *   217  THE PRICE COLUMN IS NOT SCALED. The registry holds US dollars per million tokens, which
 *        is the unit the provider publishes, and [usdPerMillion] prints that number unchanged.
 *        There is no multiplication anywhere between the JSON and the cell. 217 was cents rendered
 *        as whole euros — a hundredfold error — and it happened because a unit was converted on
 *        the way to the screen.
 *   219  THE DATE IS ON THE PAGE. cloud-writer declares no INTERNET permission and fetches no
 *        catalogue, so every price here is a baked one; the note under the table names the day
 *        they were taken and the provider they came from. 219 was a baked 0.966 against a live
 *        0.280 with nothing on screen admitting the number had an age.
 *   247  NOTHING ON THIS PAGE CAN VETO A BUILD. The price check that talks to the third party is a
 *        reporting tester, not a gate; this application makes no catalogue request at all, so
 *        openrouter.ai being slow, moved or down cannot keep the owner's APK from shipping.
 *   186/187  Number format is fixed to three decimals and a dot regardless of locale, the size,
 *        quantisation and trained-for columns are present, short names are used in both the picker
 *        and the table, the exact model code is its own last column because it is the string you
 *        copy when something needs the literal identifier, per-model notes have a column, and the
 *        sort is by size then precision then name — presentation only; a run resolves the stored
 *        model id, never a row position.
 *
 * THE UI ENHANCEMENT DID NOT TOUCH ANY OF THE ABOVE, AND THAT WAS THE HARD CONSTRAINT ON IT.
 * Restyling a table is the most natural way in the world to undo 214 (let a cell wrap so it "fits")
 * or 217 (tidy the number on the way to the cell), so the column list, the widths, the dim
 * boundary, the format string and the as-of note are character-for-character what they were; what
 * changed is that the cells are Material 3 type-scale styles and colour roles instead of a 12f
 * literal and a hex constant. The table's own contract is restated on [WriterSettingsActivity.Table].
 *
 * DIVERGENCE — ROW 3. The keyboard's API key row is an editable field. cloud-writer holds no
 * credential and has no preference slot for one; its own tester fails the build if a token-shaped
 * key ever appears in [WriterPrefs], and the manifest publishes no service precisely so the key
 * never needs to be here. So this row is READ-ONLY and says where the key actually lives. Dropping
 * the row instead would leave the owner hunting for a setting that is simply somewhere else.
 */
class AiRoutingPage(activity: ComponentActivity) : WriterSettingsPage(activity) {

    override fun pageTitle(): String = getString(R.string.settings_screen_ai_routing)

    @Composable
    override fun PageContent() {
        // The provider owns the model list and the price table below, so both are read during
        // composition and both redraw when it changes. The View version had to tear the whole page
        // down and rebuild it by hand to get the same effect; leaving them showing the previous
        // provider's rows would offer a model this one has never heard of, and that route fails at
        // call time with no screen having said so.
        var generation by remember { mutableStateOf(0) }
        generation.let { }
        val provider = WriterRegistry.provider(WriterPrefs.providerId(this))

        Group {
            ChoiceRow(
                getString(R.string.ai_provider_title),
                getString(R.string.ai_provider_summary),
                WriterRegistry.providers.map { it.label to it.id },
                provider.id,
                WriterRegistry.defaultProvider,
            ) {
                WriterPrefs.put(this, WriterPrefs.KEY_PROVIDER, it)
                generation++
            }

            val models = WriterRegistry.byModelSize(provider.models)
            ChoiceRow(
                getString(R.string.ai_model_title),
                null,
                models.map { label(it) to it.id },
                WriterPrefs.modelId(this, provider.id),
                provider.defaultModel,
            ) { WriterPrefs.putModel(this, provider.id, it) }
        }

        Group {
            // The two preambles every feature routed through here prepends. Stores nothing, and is
            // read out of the registry rather than restated, for the same reason the Enhance page's
            // prompt preview is: a second copy of a paragraph is a paragraph that will disagree.
            ReadOnlyRow(
                getString(R.string.ai_preamble_title),
                getString(R.string.ai_preamble_summary),
                getString(R.string.ai_preamble_rewrite) + "\n\n" + WriterRegistry.rewritePreamble +
                    "\n\n" + getString(R.string.ai_preamble_summary_label) + "\n\n" + WriterRegistry.summaryPreamble,
            )

            ReadOnlyRow(
                getString(R.string.ai_token_title),
                null,
                getString(R.string.ai_token_writer_summary),
            )
        }

        val models = WriterRegistry.byModelSize(provider.models)
        if (WriterRegistry.hasPricing(provider)) PriceTable(provider, models)
        else Note(getString(R.string.ai_pricing_none, provider.label))
    }

    @Composable
    private fun PriceTable(provider: WriterRegistry.Provider, models: List<WriterRegistry.Model>) {
        Note(getString(R.string.ai_pricing_title))
        Note(getString(R.string.ai_pricing_summary))
        val unknown = getString(R.string.ai_table_unknown)
        Table(
            headers = listOf(
                getString(R.string.ai_pricing_col_model),
                getString(R.string.ai_pricing_col_in),
                getString(R.string.ai_pricing_col_out),
                getString(R.string.ai_pricing_col_size),
                getString(R.string.ai_pricing_col_quant),
                getString(R.string.ai_pricing_col_trained),
                getString(R.string.ai_pricing_col_note),
                getString(R.string.ai_pricing_col_slug),
            ),
            rows = models.map { m ->
                listOf(
                    label(m),
                    m.promptUsdPerMillion?.let { usdPerMillion(it) } ?: unknown,
                    m.completionUsdPerMillion?.let { usdPerMillion(it) } ?: unknown,
                    m.paramsB?.let { it.toString() + "B" } ?: unknown,
                    m.quant.joinToString("/").ifEmpty { unknown },
                    m.trainedFor.joinToString(", ").ifEmpty { unknown },
                    m.note ?: unknown,
                    m.id,
                )
            },
            // Each column's longest value, not a share of the screen: the row scrolls, so a wide
            // column costs sideways travel and nothing else. The model code is last and widest
            // because it must never be cut and nothing sits to its right to be pushed out of line.
            widthsDp = listOf(208, 72, 72, 56, 128, 240, 320, 280),
            // Name and both prices stay at full contrast; the rest is dimmed. They are what the
            // page is for and what a row shows before it has been scrolled.
            dimFrom = 3,
        )
        Note(getString(R.string.ai_pricing_baked, provider.pricingAsOf ?: "?", provider.label))
    }

    /** The registry's short readable name, suffixed for open-weight models; the exact id is the last column. */
    private fun label(m: WriterRegistry.Model): String =
        m.name + if (m.open) getString(R.string.ai_model_open_suffix) else ""

    /**
     * Always three decimals, always a dot.
     *
     * THREE, not two: at two decimals 0.065, 0.07 and 0.075 per million all render "0.07", which is
     * a want of precision. And [Locale.US] explicitly, so the column's shape cannot follow the
     * phone's locale and turn a decimal point into a comma in a table of numbers.
     */
    private fun usdPerMillion(v: Double): String = String.format(Locale.US, "%.3f", v)
}

