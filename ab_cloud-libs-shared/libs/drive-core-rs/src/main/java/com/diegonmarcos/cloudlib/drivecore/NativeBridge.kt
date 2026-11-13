package com.diegonmarcos.cloudlib.drivecore

/**
 * The JNI surface of libdrive_core_rs.so (ab_cloud-libs-shared/libs/drive-core-rs/src/jni_api.rs). Nothing calls this directly:
 * [DriveCore] does, behind its switch and its fallback. Every answer is `OK\n<records>` or `ERR\t<reason>` (the Rust side catches
 * every panic at the boundary); a null answer means the string could not even be built, and is handled as an error too.
 */
internal object NativeBridge {
    private const val LIB = "drive_core_rs"
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
    @JvmStatic external fun nativeHash(paths: String, algos: Int, threads: Int, limit: Long): String?
    @JvmStatic external fun nativeWalk(root: String, opts: String): String?
    @JvmStatic external fun nativeDiff(left: String, right: String): String?
    @JvmStatic external fun nativePanicProbe(): String?
}
