package com.diegonmarcos.superapp.image.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.diegonmarcos.superapp.mlocr.MlKitOcr
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.text.Text
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * #1805 the scene method's models, all in this APK and on device: the two object detectors of
 * data/models.json (staged stored, so both runtimes map them) and ML Kit's five script models.
 * Each model is created once and serialised: neither MediaPipe's detector nor a LiteRT interpreter
 * may be entered from two binder threads at once.
 */
internal class SceneOnDevice(private val ctx: Context) : SceneDetector.Backends {

    private val efficientDet by lazy {
        ObjectDetector.createFromOptions(
            ctx,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(BuildConfig.SCENE_EFFICIENTDET_ASSET).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(FLOOR)
                .setMaxResults(MAX_RESULTS)
                .build(),
        )
    }

    private val yolo by lazy { Yolo(map(BuildConfig.SCENE_YOLO_ASSET), BuildConfig.SCENE_YOLO_LABELS.split('|'), BuildConfig.SCENE_YOLO_PAD) }

    override fun model(detector: String): String = when (detector) {
        SceneDetector.YOLO11N -> BuildConfig.SCENE_YOLO_NAME
        else -> BuildConfig.SCENE_EFFICIENTDET_NAME
    }

    override fun objects(detector: String, frame: Bitmap, minConfidence: Float, max: Int, iou: Float): List<SceneObj> = when (detector) {
        SceneDetector.YOLO11N -> synchronized(yolo) { yolo.detect(frame, minConfidence, max, iou) }
        else -> synchronized(efficientDet) {
            val argb = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
            try {
                efficientDet.detect(BitmapImageBuilder(argb).build()).detections().mapNotNull { d ->
                    val c = d.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                    if (c.score() < minConfidence) return@mapNotNull null
                    val b: RectF = d.boundingBox()
                    SceneObj(c.index(), c.categoryName().ifBlank { c.displayName() }, c.score(), Rect(b.left, b.top, b.right, b.bottom))
                }.let { SceneMath.nms(it, iou, max) }
            } finally {
                if (argb !== frame) argb.recycle()
            }
        }
    }

    /** A failed Latin read is no text, never a lost frame: the objects of the same frame still answer. */
    override fun textRegions(frame: Bitmap): List<SceneMath.Block> =
        MlKitOcr.text(frame, MlKitOcr.Script.LATIN).map { blocks(it, "latin") }.getOrDefault(emptyList())

    override fun ocr(frame: Bitmap, script: String): Result<List<SceneMath.Block>> =
        MlKitOcr.text(frame, SCRIPT.getValue(script)).map { blocks(it, script) }

    private fun blocks(t: Text, script: String): List<SceneMath.Block> = t.textBlocks.mapNotNull { b ->
        val bb = b.boundingBox ?: return@mapNotNull null
        SceneMath.Block(
            b.text, Rect(bb.left.toFloat(), bb.top.toFloat(), bb.right.toFloat(), bb.bottom.toFloat()), script, b.recognizedLanguage,
            b.lines.mapNotNull { l ->
                val lb = l.boundingBox ?: return@mapNotNull null
                SceneMath.Line(
                    l.text, Rect(lb.left.toFloat(), lb.top.toFloat(), lb.right.toFloat(), lb.bottom.toFloat()), l.confidence,
                    l.elements.mapNotNull { e ->
                        val eb = e.boundingBox ?: return@mapNotNull null
                        SceneMath.Word(e.text, Rect(eb.left.toFloat(), eb.top.toFloat(), eb.right.toFloat(), eb.bottom.toFloat()), e.confidence)
                    },
                )
            },
        )
    }

    private fun map(asset: String): ByteBuffer = runCatching<ByteBuffer> {
        ctx.assets.openFd(asset).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) as MappedByteBuffer }
        }
    }.getOrElse {
        // A compressed asset cannot be mapped: read it whole into a direct buffer instead.
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }

    /**
     * YOLO11n on the LiteRT Interpreter: the frame letterboxed into the model's square (grey
     * [pad]), quantised as the input tensor declares (int8 / uint8 with its scale and zero point,
     * or float 0..1), NCHW or NHWC as its shape says; the [1, 84, 8400] answer decoded by
     * [SceneMath.decodeYolo].
     */
    private class Yolo(model: ByteBuffer, private val labels: List<String>, private val pad: Int) {
        private val interp = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
        private val inT = interp.getInputTensor(0)
        private val outT = interp.getOutputTensor(0)
        private val nchw = inT.shape()[1] == 3
        private val side = if (nchw) inT.shape()[2] else inT.shape()[1]
        private val input = ByteBuffer.allocateDirect(inT.numBytes()).order(ByteOrder.nativeOrder())
        private val output = ByteBuffer.allocateDirect(outT.numBytes()).order(ByteOrder.nativeOrder())
        private val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        private val pixels = IntArray(side * side)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun detect(frame: Bitmap, minConfidence: Float, max: Int, iou: Float): List<SceneObj> {
            val lb = SceneMath.letterbox(frame.width, frame.height, side)
            Canvas(square).apply {
                drawColor(Color.rgb(pad, pad, pad))
                drawBitmap(frame, null, RectF(lb.padX.toFloat(), lb.padY.toFloat(), (lb.padX + lb.newW).toFloat(), (lb.padY + lb.newH).toFloat()), paint)
            }
            square.getPixels(pixels, 0, side, 0, 0, side, side)
            fill()
            output.rewind()
            interp.run(input, output)
            output.rewind()
            val shape = outT.shape()
            val out = FloatArray(outT.numElements())
            when (outT.dataType()) {
                DataType.FLOAT32 -> output.asFloatBuffer().get(out)
                else -> {
                    val q = outT.quantizationParams()
                    for (i in out.indices) out[i] = ((if (outT.dataType() == DataType.UINT8) output.get(i).toInt() and 0xff else output.get(i).toInt()) - q.zeroPoint) * q.scale
                }
            }
            val channelsFirst = shape[1] == labels.size + 4
            val channels = if (channelsFirst) shape[1] else shape[2]
            val anchors = if (channelsFirst) shape[2] else shape[1]
            return SceneMath.decodeYolo(out, channels, anchors, channelsFirst, labels, lb, frame.width, frame.height, minConfidence, iou, max)
        }

        private fun fill() {
            input.rewind()
            val type = inT.dataType()
            val q = inT.quantizationParams()
            val plane = side * side
            fun put(index: Int, v: Int) {
                when (type) {
                    DataType.FLOAT32 -> input.putFloat(index * 4, v / 255f)
                    DataType.INT8 -> input.put(index, ((v / 255f) / q.scale + q.zeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                    else -> input.put(index, (if (q.scale > 0f) ((v / 255f) / q.scale + q.zeroPoint).roundToInt() else v).coerceIn(0, 255).toByte())
                }
            }
            for (i in 0 until plane) {
                val c = pixels[i]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                if (nchw) { put(i, r); put(plane + i, g); put(2 * plane + i, b) }
                else { put(i * 3, r); put(i * 3 + 1, g); put(i * 3 + 2, b) }
            }
            input.rewind()
        }
    }

    private companion object {
        /** MediaPipe and YOLO both answer everything over this floor; the request's min_confidence filters. */
        const val FLOOR = 0.15f
        const val MAX_RESULTS = 25
        const val THREADS = 4
        val SCRIPT = mapOf(
            "latin" to MlKitOcr.Script.LATIN, "chinese" to MlKitOcr.Script.CHINESE, "devanagari" to MlKitOcr.Script.DEVANAGARI,
            "japanese" to MlKitOcr.Script.JAPANESE, "korean" to MlKitOcr.Script.KOREAN,
        )
    }
}
