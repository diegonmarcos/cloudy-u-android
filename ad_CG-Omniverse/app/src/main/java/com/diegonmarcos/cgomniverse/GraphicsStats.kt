package com.diegonmarcos.cgomniverse

import org.json.JSONObject

/**
 * The graphics stats HUD's data, for both screens. Avatar: cgc.js stats() (three.js renderer.info, exact). Galaxy: [PAGE_SCRIPT] injected
 * into a third-party world (a requestAnimationFrame loop for fps and frame time, a wrap of the WebGL draw calls for draw calls and
 * triangles: best effort, a page that blocks it or never draws shows n/a). Pure (org.json only) so the JVM suite holds the parsing and layout.
 */
object GraphicsStats {
    /** Every field is optional: a missing one is shown as n/a, never a crash. */
    data class Stats(
        val fps: Double? = null,
        val frameMs: Double? = null,
        val drawCalls: Long? = null,
        val triangles: Long? = null,
        val vertices: Long? = null,
        val geometries: Long? = null,
        val textures: Long? = null,
        val gpu: String? = null,
        val note: String? = null,
    )

    const val NA = "n/a"

    /** Injected once per page load (idempotent). Draw counts are per rAF frame; instanced draws multiply by the instance count. */
    const val PAGE_SCRIPT = """(function(){try{if(window.__cgcStats)return;var s=window.__cgcStats={fps:0,ms:0,calls:null,tris:null,hooked:false},fc=0,ft=0,n=0,t=performance.now(),l=t;
var tr=function(m,c){return m===4?c/3:(m===5||m===6)?Math.max(c-2,0):0};
try{[window.WebGLRenderingContext,window.WebGL2RenderingContext].forEach(function(C){if(!C)return;var p=C.prototype;
['drawArrays','drawElements','drawArraysInstanced','drawElementsInstanced','drawRangeElements'].forEach(function(k){var o=p[k];if(!o)return;
p[k]=function(m,a,b,c,d){var cnt=k==='drawArrays'||k==='drawArraysInstanced'?b:k==='drawRangeElements'?c:a;
var inst=k==='drawArraysInstanced'?c:k==='drawElementsInstanced'?d:1;fc++;ft+=tr(m,cnt)*(inst||1);return o.apply(this,arguments)}});s.hooked=true})}catch(e){}
function f(now){n++;s.ms=now-l;l=now;if(s.hooked){s.calls=fc;s.tris=Math.round(ft)}fc=0;ft=0;
if(now-t>=1000){s.fps=Math.round(n*10000/(now-t))/10;n=0;t=now}requestAnimationFrame(f)}requestAnimationFrame(f)}catch(e){}})()"""

    /** Evaluates to the page's stats object, or null when the script never ran (blocked page). */
    const val PAGE_READ = "(function(){var s=window.__cgcStats;return s?{fps:s.fps,ms:s.ms,calls:s.calls,tris:s.tris,hooked:s.hooked}:null})()"

    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun JSONObject.lng(k: String): Long? = dbl(k)?.toLong()

    /** cgc.js stats() as returned by evaluateJavascript; anything else gives null. */
    fun parseAvatar(js: String?): Stats? {
        val o = runCatching { JSONObject(js ?: return null) }.getOrNull() ?: return null
        return Stats(
            fps = o.dbl("fps"), frameMs = o.dbl("frameMs"), drawCalls = o.lng("drawCalls"), triangles = o.lng("triangles"),
            vertices = o.lng("vertices"), geometries = o.lng("geometries"), textures = o.lng("textures"),
            gpu = o.optString("gpu").ifEmpty { null },
            note = listOfNotNull(
                if (o.optBoolean("lite")) "lite" else null,
                o.optString("background").ifEmpty { null }?.let { "bg $it" },
                o.optJSONArray("canvas")?.let { "${it.optInt(0)}x${it.optInt(1)}" },
            ).joinToString(" ").ifEmpty { null },
        )
    }

    /** [PAGE_READ]'s result plus the GPU name the app's own probe measured; a null page result still shows the GPU. */
    fun parseWorld(js: String?, gpu: String?): Stats {
        val o = runCatching { JSONObject(js ?: "") }.getOrNull()
        val hooked = o?.optBoolean("hooked") == true
        return Stats(
            fps = o?.dbl("fps"), frameMs = o?.dbl("ms"),
            drawCalls = if (hooked) o?.lng("calls") else null, triangles = if (hooked) o?.lng("tris") else null,
            gpu = gpu?.ifEmpty { null },
            note = if (o == null) "page blocks injection" else if (!hooked) "draw calls not hookable" else null,
        )
    }

    private fun n(v: Long?) = v?.let { "%,d".format(java.util.Locale.US, it) } ?: NA
    private fun d(v: Double?, suffix: String) = v?.let { "%.1f".format(java.util.Locale.US, it) + suffix } ?: NA

    /** The HUD's monospace lines, in order. */
    fun lines(s: Stats?): List<String> {
        if (s == null) return listOf("FPS        $NA", "FRAME      $NA", "DRAWS      $NA", "TRIS       $NA", "VERTS      $NA", "GEOM       $NA", "TEX        $NA", "GPU        $NA")
        return listOf(
            "FPS        ${d(s.fps, "")}", "FRAME      ${d(s.frameMs, " ms")}", "DRAWS      ${n(s.drawCalls)}", "TRIS       ${n(s.triangles)}",
            "VERTS      ${n(s.vertices)}", "GEOM       ${n(s.geometries)}", "TEX        ${n(s.textures)}", "GPU        ${s.gpu ?: NA}",
        ) + listOfNotNull(s.note?.let { "           $it" })
    }
}
