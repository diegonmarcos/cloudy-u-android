package com.diegonmarcos.cloudlib.healthcore

/**
 * The JNI surface of libhealth_core_jni.so (ab_cloud-libs-shared/libs/health-core-rs/src/jni_api.rs). Nothing calls this directly:
 * [HealthCore] does. Every answer is `OK\n<json>` or `ERR\t<reason>` (the Rust side catches every panic at the boundary); a null
 * answer means the string could not even be built.
 */
internal object HealthNative {
    private const val LIB = "health_core_jni"
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
    @JvmStatic external fun nativeProbe(url: String, timeoutMs: Long): String?
    @JvmStatic external fun nativeAnalyze(report: String, layers: String): String?
    @JvmStatic external fun nativeMerge(phone: String, fleet: String): String?
    @JvmStatic external fun nativePlanMesh(decl: String): String?
    @JvmStatic external fun nativeRedact(text: String): String?
    @JvmStatic external fun nativePanicProbe(): String?
}
