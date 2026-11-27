package com.diegonmarcos.cloudlib.healthcore

/**
 * Cloud C3's on-phone health engine. The check logic is Rust (the health-core crate); this facade only hands strings across and reads the
 * answer. There is no Kotlin fallback: with no library the engine is "not in this build" and every phone check says so (never a number).
 *
 * The [Engine] seam lets the JVM suite run the answer handling without the native library; the Rust side is covered by cargo test.
 */
object HealthCore {
    /** The native calls as strings; the default is the JNI library. */
    interface Engine {
        fun unavailable(): String?
        fun version(): String?
        fun probe(url: String, timeoutMs: Long): String?
        fun analyze(report: String, layers: String): String?
        fun merge(phone: String, fleet: String): String?
        fun planMesh(decl: String): String?
        fun redact(text: String): String?
    }

    internal object Native : Engine {
        override fun unavailable() = HealthNative.unavailableReason()
        override fun version() = HealthNative.nativeVersion()
        override fun probe(url: String, timeoutMs: Long) = HealthNative.nativeProbe(url, timeoutMs)
        override fun analyze(report: String, layers: String) = HealthNative.nativeAnalyze(report, layers)
        override fun merge(phone: String, fleet: String) = HealthNative.nativeMerge(phone, fleet)
        override fun planMesh(decl: String) = HealthNative.nativePlanMesh(decl)
        override fun redact(text: String) = HealthNative.nativeRedact(text)
    }

    @Volatile var engine: Engine = Native

    const val NOT_IN_BUILD = "phone probe engine not in this build"

    sealed class Answer {
        data class Ok(val json: String) : Answer()
        /** [reason] is the engine's own text, or "phone probe engine not in this build: <why the library did not load>". */
        data class Err(val reason: String) : Answer()
        val okJson get() = (this as? Ok)?.json
    }

    /** `OK\n<json>` or `ERR\t<reason>` (null = the string could not be built). Anything else is an error, never a result. */
    fun parse(raw: String?): Answer = when {
        raw == null -> Answer.Err("the engine returned no answer")
        raw.startsWith("OK\n") -> Answer.Ok(raw.substring(3))
        raw.startsWith("ERR\t") -> Answer.Err(raw.substring(4))
        else -> Answer.Err("unreadable engine answer: ${raw.take(60)}")
    }

    private inline fun call(f: Engine.() -> String?): Answer {
        val e = engine
        e.unavailable()?.let { return Answer.Err("$NOT_IN_BUILD: $it") }
        return parse(runCatching { e.f() }.getOrElse { return Answer.Err("engine call failed: ${it.javaClass.simpleName}") })
    }

    /** Null when the engine is loaded, else the reason it is not. */
    fun unavailable(): String? = engine.unavailable()?.let { "$NOT_IN_BUILD: $it" }
    fun version(): Answer = call { version() }
    /** One phased probe: `{status, phases{dns,tcp,tls,http}, code, error, addr}`. */
    fun probe(url: String, timeoutMs: Long = 5000): Answer = call { probe(url, timeoutMs) }
    /** Attribution over a c3-report/v1 envelope: `{status, roots[], checks[], lights[]}` with the layers in rank order. */
    fun analyze(reportJson: String, layersJson: String): Answer = call { analyze(reportJson, layersJson) }
    fun merge(phoneReportJson: String, fleetReportJson: String): Answer = call { merge(phoneReportJson, fleetReportJson) }
    fun planMesh(declJson: String): Answer = call { planMesh(declJson) }
    fun redact(text: String): Answer = call { redact(text) }
}
