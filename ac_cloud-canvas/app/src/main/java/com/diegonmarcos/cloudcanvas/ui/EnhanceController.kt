package com.diegonmarcos.cloudcanvas.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.cloudcanvas.draw.ExportNames
import com.diegonmarcos.cloudcanvas.enhance.AiSettings
import com.diegonmarcos.cloudcanvas.enhance.EnhanceClient
import com.diegonmarcos.cloudcanvas.enhance.EnhanceRequest
import com.diegonmarcos.cloudcanvas.enhance.PresetLibrary
import com.diegonmarcos.cloudcanvas.enhance.PromptExpander
import com.diegonmarcos.cloudcanvas.enhance.Task
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue
import java.io.ByteArrayOutputStream

/** The state of the Draw Enhancer page, held by the shell: the input picture, the chosen preset, the editable expanded prompt, the run and its result. */
class EnhanceController(private val ctx: Context, private val draw: DrawController) {
    val settings = AiSettings(ctx)

    var input by mutableStateOf<Bitmap?>(null)
    var inputName by mutableStateOf("")
    var presetId by mutableStateOf<String?>(null)
    var custom by mutableStateOf("")
    var promptText by mutableStateOf("")
    var promptEdited by mutableStateOf(false)
    var promptOpen by mutableStateOf(false)
    var running by mutableStateOf(false)
    var result by mutableStateOf<Bitmap?>(null)
    private var resultBytes: ByteArray? = null
    var error by mutableStateOf<String?>(null)
    var note by mutableStateOf<String?>(null)

    /** The image models of the fleet catalogue (the Visual Media section): what Configs > AI offers per task. */
    val imageModels: List<ModelCatalogue.Row> by lazy {
        runCatching {
            val text = ctx.assets.open("models/catalogue.json").bufferedReader().use { it.readText() }
            ModelCatalogue.parse(text).sections.filter { it.kind == ModelCatalogue.Kind.IMAGE }.flatMap { it.rows }
        }.getOrDefault(emptyList())
    }

    fun modelFor(task: Task): ModelCatalogue.Row? =
        imageModels.firstOrNull { it.id == settings.choices.model(task) } ?: imageModels.firstOrNull()

    fun useDrawing() { input = draw.flattenBitmap(); inputName = "the current drawing"; reset() }

    fun load(uri: Uri) {
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
        if (bmp == null) { error = "That file could not be opened as a picture."; return }
        input = bmp; inputName = "the uploaded picture"; reset()
    }

    private fun reset() { result = null; resultBytes = null; error = null; note = null }

    /** Re-expands the prompt from the preset and Configs unless the user edited it for this run. */
    fun refreshPrompt(lib: PresetLibrary?) {
        if (promptEdited) return
        val q = lib?.quality(settings.choices.quality)
        val s = lib?.size(settings.choices.size)
        promptText = PromptExpander.expand(lib?.preset(presetId), custom, q, s)
    }

    fun pick(lib: PresetLibrary?, id: String?) { presetId = id; promptEdited = false; refreshPrompt(lib) }

    fun task(lib: PresetLibrary?): Task = lib?.preset(presetId)?.task ?: Task.DrawEnhance

    fun estimate(lib: PresetLibrary?): Double? {
        val row = modelFor(task(lib)) ?: return null
        val px = lib?.size(settings.choices.size)?.maxPx ?: 1024
        val p = row.snapshot ?: return null
        return EnhanceRequest.estimateUsd(p.unit.name.lowercase(), p.output, px)
    }

    /** Runs the enhancement on a worker thread; progress, errors and the result land in the state above. */
    fun run(lib: PresetLibrary?) {
        val src = input ?: return
        if (running) return
        val row = modelFor(task(lib))
        if (row == null) { error = "No image model is available. Open Configs > AI."; return }
        val cap = settings.choices.costCap(lib?.defaultCostCapUsd ?: 0.25)
        val est = estimate(lib)
        if (EnhanceRequest.overCap(est, cap)) { error = "The estimated cost (about $" + "%.3f".format(est) + ") is over your cap of $" + "%.2f".format(cap) + ". Raise it in Configs > AI."; return }
        val maxPx = (lib?.size(settings.choices.size)?.maxPx ?: 1024).coerceAtMost(lib?.maxInputPx ?: 2048)
        val prompt = promptText
        running = true; error = null; note = null
        Thread {
            val key = settings.resolve()
            if (key.key == null) {
                error = "No OpenRouter key. Add one in Configs > AI, or sign in to the fleet Account."; running = false
            } else {
                val bytes = EnhanceClient.prepare(src, maxPx, lib?.maxInputBytes ?: 6_000_000)
                when (val r = EnhanceClient.run(ctx, key.key, row.id, prompt, bytes)) {
                    is EnhanceRequest.Result.Image -> {
                        val bmp = EnhanceClient.decode(r.bytes)
                        if (bmp == null) error = "The returned image could not be read." else { result = bmp; resultBytes = r.bytes }
                    }
                    is EnhanceRequest.Result.Failure -> error = r.message
                }
                running = false
            }
        }.start()
    }

    fun saveTo(tree: Uri) {
        val b = resultBytes ?: return
        val png = result?.let { ByteArrayOutputStream().also { o -> it.compress(Bitmap.CompressFormat.PNG, 100, o) }.toByteArray() } ?: b
        val name = ExportNames.png(System.currentTimeMillis() / 1000).replace("cloud-canvas-", "cloud-canvas-enhanced-")
        note = if (Folders.write(ctx, tree, name, "image/png", png) != null) "Saved $name" else "The folder could not be written. Pick it again."
    }

    fun sendToDraw() {
        val r = result ?: return
        draw.addImageLayer("Enhanced", r)
        note = "Sent to Draw as a new layer."
    }
}
