package com.diegonmarcos.cloudkeyboard.handwrite

import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.RecognitionContext
import helium314.keyboard.keyboard.handwrite.HandwritingEngine
import helium314.keyboard.keyboard.handwrite.InkStroke
import helium314.keyboard.keyboard.handwrite.Recognition

/**
 * #1781 HandWrite's engine: Google ML Kit Digital Ink Recognition, on the device. A language's model is downloaded on demand through
 * [RemoteModelManager] (the only network use of HandWrite, from the HandWrite tab); recognition itself never touches the network.
 * Registered for the keyboard in App.onCreate (libs:keyboard holds only the [HandwritingEngine] interface, so the proprietary SDK stays in the app).
 */
class MlKitHandwritingEngine : HandwritingEngine {
    private val recognizers = HashMap<String, DigitalInkRecognizer>()
    private val manager get() = RemoteModelManager.getInstance()

    /** Null when ML Kit has no handwriting model for this language tag. */
    private fun model(tag: String): DigitalInkRecognitionModel? = try {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)?.let { DigitalInkRecognitionModel.builder(it).build() }
    } catch (e: MlKitException) { null }

    override fun isModelReady(languageTag: String, onResult: (Boolean) -> Unit) {
        val m = model(languageTag) ?: return onResult(false)
        manager.isModelDownloaded(m).addOnSuccessListener { onResult(it) }.addOnFailureListener { onResult(false) }
    }

    override fun download(languageTag: String, onResult: (Boolean, String) -> Unit) {
        val m = model(languageTag) ?: return onResult(false, "no handwriting model for $languageTag")
        manager.download(m, DownloadConditions.Builder().build())
            .addOnSuccessListener { onResult(true, "downloaded $languageTag") }
            .addOnFailureListener { onResult(false, it.message ?: "download failed") }
    }

    override fun delete(languageTag: String, onResult: (Boolean) -> Unit) {
        val m = model(languageTag) ?: return onResult(false)
        recognizers.remove(languageTag)?.close()
        manager.deleteDownloadedModel(m).addOnSuccessListener { onResult(true) }.addOnFailureListener { onResult(false) }
    }

    override fun recognize(strokes: List<InkStroke>, languageTag: String, preContext: String, onResult: (Recognition) -> Unit) {
        val m = model(languageTag) ?: return onResult(Recognition.Failed("no handwriting model for $languageTag"))
        manager.isModelDownloaded(m).addOnSuccessListener { ready ->
            if (!ready) return@addOnSuccessListener onResult(Recognition.NoModel)
            val recognizer = recognizers.getOrPut(languageTag) { DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build()) }
            val context = RecognitionContext.builder().setPreContext(preContext).build()
            recognizer.recognize(InkConversion.toInk(strokes), context)
                .addOnSuccessListener { r -> onResult(Recognition.Candidates(r.candidates.map { it.text })) }
                .addOnFailureListener { onResult(Recognition.Failed(it.message ?: "recognition failed")) }
        }.addOnFailureListener { onResult(Recognition.Failed(it.message ?: "model check failed")) }
    }

    companion object {
        /** The languages the HandWrite tab offers (BCP-47 tags ML Kit Digital Ink knows); a tag ML Kit does not know shows as unavailable. */
        val LANGUAGES = listOf("en-US" to "English", "es-ES" to "Spanish", "fr-FR" to "French", "de-DE" to "German", "it-IT" to "Italian",
            "pt-BR" to "Portuguese", "nl-NL" to "Dutch", "ru-RU" to "Russian", "ja-JP" to "Japanese", "ko-KR" to "Korean", "zh-Hani-CN" to "Chinese")
    }
}
