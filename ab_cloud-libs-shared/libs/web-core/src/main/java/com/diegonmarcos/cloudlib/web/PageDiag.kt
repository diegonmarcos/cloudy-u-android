package com.diegonmarcos.cloudlib.web

import org.json.JSONObject

/**
 * The "Page diagnostics" sheet (#1537), the pure half: what the page in front of the user can actually use, measured IN that
 * page (not in the hidden probe view) by [SCRIPT], and worded as rows. Rendering APIs (canvas 2D, WebGL, WebGL2, the WebGPU
 * adapter) and the features sites gate on (cross-origin isolation, SharedArrayBuffer, OffscreenCanvas, workers, WebAssembly,
 * createImageBitmap, WebCodecs, service workers, IndexedDB, Cache Storage).
 */
data class PageDiag(
    val flags: Map<String, Boolean>,
    val userAgent: String,
    val webgpuAdapter: String,
    val maxTexture: String,
) {
    fun rows(): List<GpuRow> {
        val out = ArrayList<GpuRow>()
        for ((key, label) in LABELS) out += GpuRow(label, if (flags[key] == true) "yes" else "no")
        out += GpuRow("WebGPU adapter", webgpuAdapter)
        out += GpuRow("Max texture size", maxTexture)
        out += GpuRow("User agent", userAgent)
        return out
    }

    fun text(errors: List<PageConsole.Entry>): String =
        rows().joinToString("\n") { "${it.label}: ${it.value}" } + "\n\nConsole errors (${errors.size}):\n" + PageConsole.text(errors)

    companion object {
        val LABELS: List<Pair<String, String>> = listOf(
            "canvas2d" to "Canvas 2D", "webgl" to "WebGL", "webgl2" to "WebGL2", "webgpu" to "navigator.gpu (WebGPU API)",
            "crossOriginIsolated" to "crossOriginIsolated", "sab" to "SharedArrayBuffer", "offscreen" to "OffscreenCanvas",
            "worker" to "Web Workers", "wasm" to "WebAssembly", "bitmap" to "createImageBitmap", "webcodecs" to "WebCodecs",
            "sw" to "Service workers", "idb" to "IndexedDB", "caches" to "Cache Storage", "secure" to "Secure context",
        )

        /**
         * Runs in the page; stores the answer in `window.__cloudDiag` (the WebGPU adapter answers asynchronously, so the host reads
         * it a moment later with [READ]). No JS interface is added to the page: a foreign page must not get a bridge.
         */
        const val SCRIPT = "(function(){var d={f:{},ua:navigator.userAgent,adapter:'pending',tex:'n/a'};window.__cloudDiag=d;" +
            "function cx(t){try{return document.createElement('canvas').getContext(t)}catch(e){return null}}" +
            "d.f.canvas2d=!!cx('2d');var g=cx('webgl2');d.f.webgl2=!!g;var g1=g||cx('webgl');d.f.webgl=!!g1;" +
            "try{if(g1)d.tex=String(g1.getParameter(g1.MAX_TEXTURE_SIZE))}catch(e){}" +
            "d.f.webgpu=!!navigator.gpu;d.f.crossOriginIsolated=window.crossOriginIsolated===true;" +
            "d.f.sab=typeof SharedArrayBuffer!=='undefined';d.f.offscreen=typeof OffscreenCanvas!=='undefined';" +
            "d.f.worker=typeof Worker!=='undefined';d.f.wasm=typeof WebAssembly==='object';d.f.bitmap=typeof createImageBitmap==='function';" +
            "d.f.webcodecs=typeof VideoDecoder!=='undefined';d.f.sw='serviceWorker' in navigator;d.f.idb=typeof indexedDB!=='undefined';" +
            "d.f.caches=typeof caches!=='undefined';d.f.secure=window.isSecureContext===true;" +
            "if(navigator.gpu){navigator.gpu.requestAdapter().then(function(a){d.adapter=a?'available':'null (requestAdapter returned null)'})" +
            ".catch(function(e){d.adapter='error: '+(e&&e.message||e)})}else{d.adapter='none (navigator.gpu is undefined)'}})();"

        /** Reads the answer back (call after a moment, [SCRIPT] first). */
        const val READ = "JSON.stringify(window.__cloudDiag||null)"

        /** [raw] is what evaluateJavascript returned for [READ]: a JSON string, itself quoted. Null when the page has not answered. */
        fun parse(raw: String?): PageDiag? {
            val inner = raw?.takeIf { it.isNotEmpty() && it != "null" }?.let { r ->
                runCatching { if (r.startsWith("\"")) org.json.JSONTokener(r).nextValue() as String else r }.getOrNull()
            } ?: return null
            val o = runCatching { JSONObject(inner) }.getOrNull() ?: return null
            val f = o.optJSONObject("f") ?: return null
            return PageDiag(
                flags = f.keys().asSequence().associateWith { f.optBoolean(it) },
                userAgent = o.optString("ua"),
                webgpuAdapter = o.optString("adapter", "pending"),
                maxTexture = o.optString("tex", "n/a"),
            )
        }
    }
}
