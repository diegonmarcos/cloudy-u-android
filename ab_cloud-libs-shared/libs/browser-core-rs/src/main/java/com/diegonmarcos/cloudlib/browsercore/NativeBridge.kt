package com.diegonmarcos.cloudlib.browsercore

/**
 * The JNI surface of libbrowser_core.so (ab_cloud-libs-shared/libs/browser-core-rs/src/jni_api.rs). Nothing calls this directly:
 * [BrowserCore] does. The string answers are `OK\n<json>` or `ERR\t<reason>` (the Rust side catches every panic at the boundary);
 * a null answer means the string could not even be built. The filter's hot call answers a boolean, false on any failure.
 */
internal object NativeBridge {
    private const val LIB = "browser_core"
    @Volatile private var loaded: Boolean? = null
    @Volatile private var loadError: String? = null

    /** Null when the library is loaded, otherwise the reason it is not (cached: one attempt per process). */
    fun unavailableReason(): String? {
        val l = loaded
        if (l == true) return null
        if (l == false) return loadError
        return synchronized(this) {
            if (loaded == null) {
                try {
                    System.loadLibrary(LIB)
                    loaded = true
                } catch (t: Throwable) {
                    loadError = "lib$LIB.so did not load: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(160)}"
                    loaded = false
                }
            }
            if (loaded == true) null else loadError
        }
    }

    @JvmStatic external fun nativeVersion(): String?
    @JvmStatic external fun nativeStore(db: String, op: String, arg: String): String?
    @JvmStatic external fun nativeSearch(entries: String, query: String, limit: Int, ranked: Boolean): String?
    @JvmStatic external fun nativeFilterNew(rules: String): Long
    @JvmStatic external fun nativeFilterFree(handle: Long)
    @JvmStatic external fun nativeFilterBlocks(handle: Long, requestHost: String, pageHost: String, mainFrame: Boolean): Boolean
    @JvmStatic external fun nativePanicProbe(): String?
}
