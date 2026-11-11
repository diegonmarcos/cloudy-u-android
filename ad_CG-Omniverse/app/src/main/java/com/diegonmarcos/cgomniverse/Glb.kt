package com.diegonmarcos.cgomniverse

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * #1631 A GLB read the way a loader reads it, from its bytes alone: the 12-byte header (magic "glTF", version 2, declared length),
 * the first chunk (type JSON) and the glTF document in it. Answers whether the bundled model can decode at all, and which decoders
 * it needs (Draco, KTX2) that a plain GLTFLoader lacks. Pure (org.json + java.nio): the JVM suite builds GLBs and breaks them.
 */
object Glb {
    data class Report(
        val valid: Boolean, val error: String, val version: Long, val declaredLength: Long, val actualLength: Long,
        val meshes: Int, val materials: Int, val textures: Int, val images: Int, val nodes: Int, val skins: Int, val animations: Int,
        val extensionsUsed: List<String>, val extensionsRequired: List<String>, val needsDecoder: List<String>,
    ) {
        fun summary(): String = "glTF $version, $actualLength bytes, $meshes mesh(es), $materials material(s), $textures texture(s), $nodes node(s), $skins skin(s), $animations animation(s)"
    }

    /** Required extensions a stock three.js GLTFLoader cannot decode without an extra decoder being registered. */
    val DECODER_EXTENSIONS: Set<String> = setOf("KHR_draco_mesh_compression", "KHR_texture_basisu")

    private const val MAGIC = 0x46546C67L   // "glTF" little endian
    private const val CHUNK_JSON = 0x4E4F534AL   // "JSON" little endian

    private fun bad(msg: String, len: Long, v: Long = 0, declared: Long = 0) =
        Report(false, msg, v, declared, len, 0, 0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptyList())

    fun inspect(bytes: ByteArray): Report {
        val len = bytes.size.toLong()
        if (len < 20) return bad("only $len bytes: too short for a GLB header and a chunk", len)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = b.getInt(0).toLong() and 0xFFFFFFFFL
        val version = b.getInt(4).toLong() and 0xFFFFFFFFL
        val declared = b.getInt(8).toLong() and 0xFFFFFFFFL
        if (magic != MAGIC) {
            val head = String(bytes, 0, minOf(4, bytes.size), Charsets.ISO_8859_1).filter { it.code in 32..126 }
            return bad("bad magic \"$head\" (not \"glTF\"): the file is not a GLB (an LFS pointer or an HTML error page saved as .glb?)", len, version, declared)
        }
        if (version != 2L) return bad("glTF version $version (a loader reads 2)", len, version, declared)
        if (declared != len) return bad("header declares $declared bytes but the file has $len (truncated or padded)", len, version, declared)
        val chunkLen = b.getInt(12).toLong() and 0xFFFFFFFFL
        val chunkType = b.getInt(16).toLong() and 0xFFFFFFFFL
        if (chunkType != CHUNK_JSON) return bad("the first chunk is not JSON", len, version, declared)
        if (20 + chunkLen > len) return bad("the JSON chunk ($chunkLen bytes) runs past the end of the file", len, version, declared)
        val doc = runCatching { JSONObject(String(bytes, 20, chunkLen.toInt(), Charsets.UTF_8).trimEnd(' ', '\u0000')) }.getOrNull()
            ?: return bad("the JSON chunk is not valid JSON", len, version, declared)
        fun n(k: String) = doc.optJSONArray(k)?.length() ?: 0
        fun l(k: String): List<String> = doc.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val req = l("extensionsRequired")
        return Report(
            true, "", version, declared, len, n("meshes"), n("materials"), n("textures"), n("images"), n("nodes"), n("skins"), n("animations"),
            l("extensionsUsed"), req, req.filter { it in DECODER_EXTENSIONS },
        )
    }
}
