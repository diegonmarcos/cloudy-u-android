// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import com.diegonmarcos.superapp.translate.TranslateEngineClient
import com.diegonmarcos.superapp.translate.TranslateEngines
import com.diegonmarcos.superapp.translate.TranslatePrefs
import helium314.keyboard.latin.utils.Log
import java.util.Locale

/**
 * Translation ▸ Routing: wraps the on-device client the app registered (ML Kit in-process, or the AIDL
 * companion) and sends each call where [AiTaskRouting] resolves the Translation task:
 *  - ML libs (on-device): the wrapped client, exactly as before; OpenRouter only when ML Kit is not bound.
 *  - OpenRouter: the Translation table's model; when offline or without a key, the on-device client takes over.
 * libs:translate stays unaware of any of this: it still calls TranslateEngines.client.
 */
class RoutedTranslateEngineClient(context: Context, val ml: TranslateEngineClient?) : TranslateEngineClient {
    private val ctx = context.applicationContext

    private fun active(): AiEngine? = AiTaskRouting.resolution(ctx, AiTask.TRANSLATE).active

    private fun langName(tag: String) = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    private fun viaOpenRouter(text: String, sourceTag: String, targetTag: String): Array<String> = try {
        val system = "Translate the user's text into ${langName(targetTag)}. Reply with the translation only: " +
            "no quotes, notes or explanations. Keep line breaks, names, numbers and URLs as they are."
        arrayOf(sourceTag, AiRouter.complete(ctx, system, text, AiTaskRouting.route(ctx, AiTask.TRANSLATE)).trim())
    } catch (e: Exception) {
        Log.w(TAG, "OpenRouter translation failed: ${e.message}")
        // Fallback: the on-device engine, when it can answer.
        if (ml?.isConnected() == true) {
            if (sourceTag == "und") ml.translate(text, targetTag) else ml.translateFrom(text, sourceTag, targetTag)
        } else arrayOf(sourceTag, "", "OpenRouter: ${e.message ?: e.javaClass.simpleName}")
    }

    override fun translate(text: String, targetTag: String): Array<String> =
        if (active() == AiEngine.OPENROUTER) viaOpenRouter(text, "und", targetTag)
        else ml?.translate(text, targetTag) ?: arrayOf("und", "", "no on-device engine")

    override fun translateFrom(text: String, sourceTag: String, targetTag: String): Array<String> =
        if (active() == AiEngine.OPENROUTER) viaOpenRouter(text, sourceTag, targetTag)
        else ml?.translateFrom(text, sourceTag, targetTag) ?: arrayOf(sourceTag, "", "no on-device engine")

    override fun supportedLanguages(): List<String> =
        ml?.supportedLanguages().orEmpty().ifEmpty { TranslatePrefs.FALLBACK_LANGS }

    override fun isConnected(): Boolean = active() != null

    companion object {
        private const val TAG = "RoutedTranslate"

        /** Wraps the registered client once; called from LatinIME.onCreate, after the app registered its own. */
        @JvmStatic
        fun install(context: Context) {
            val current = TranslateEngines.client
            if (current is RoutedTranslateEngineClient) return
            TranslateEngines.client = RoutedTranslateEngineClient(context, current)
        }

        /** The on-device client underneath, whether or not the wrapper is installed. */
        fun onDevice(): TranslateEngineClient? =
            TranslateEngines.client.let { (it as? RoutedTranslateEngineClient)?.ml ?: it }
    }
}
