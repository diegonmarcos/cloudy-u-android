package com.diegonmarcos.cloudlib.mailcore

/**
 * The JNI surface of libmail_core.so (ab_cloud-libs-shared/libs/mail-core-rs/src/jni_api.rs). Nothing calls this directly:
 * [MailCore] does. Every answer is `OK\n<json>` or `ERR\t<reason>` (the Rust side catches every panic at the boundary); a null
 * answer means the string could not even be built, and is handled as an error too.
 */
internal object NativeBridge {
    private const val LIB = "mail_core"
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
    @JvmStatic external fun nativeSync(db: String, config: String, transport: MailCoreTransport): String?
    @JvmStatic external fun nativeFetchRaw(db: String, args: String, transport: MailCoreTransport): String?
    @JvmStatic external fun nativeImapFetch(args: String, io: MailCoreImapIo): String?
    @JvmStatic external fun nativeQuery(db: String, query: String): String?
    @JvmStatic external fun nativePanicProbe(): String?
}
