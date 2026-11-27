package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudlib.healthcore.HealthCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1955 C A1 the Kotlin facade of the Rust health engine: answers are read exactly, a missing library is "not in this build" (never a number). */
class HealthCoreTest {
    private val saved = HealthCore.engine
    @After fun restore() { HealthCore.engine = saved }

    private class Fake(val gone: String? = null, val answer: String? = "OK\n{}", val boom: Boolean = false) : HealthCore.Engine {
        override fun unavailable() = gone
        private fun r(): String? { if (boom) error("x"); return answer }
        override fun version() = r()
        override fun probe(url: String, timeoutMs: Long) = r()
        override fun analyze(report: String, layers: String) = r()
        override fun merge(phone: String, fleet: String) = r()
        override fun planMesh(decl: String) = r()
        override fun redact(text: String) = r()
    }

    @Test fun `answers are OK json or ERR reason and anything else is an error`() {
        assertEquals(HealthCore.Answer.Ok("{\"a\":1}"), HealthCore.parse("OK\n{\"a\":1}"))
        assertEquals(HealthCore.Answer.Err("panic: boom"), HealthCore.parse("ERR\tpanic: boom"))
        assertTrue(HealthCore.parse(null) is HealthCore.Answer.Err)
        assertTrue(HealthCore.parse("{}") is HealthCore.Answer.Err)
    }

    @Test fun `with no library every call says the engine is not in this build`() {
        HealthCore.engine = Fake(gone = "lib did not load")
        listOf(HealthCore.probe("tcp://x:1"), HealthCore.analyze("{}", "[]"), HealthCore.merge("{}", "{}"), HealthCore.planMesh("{}"), HealthCore.redact("t"), HealthCore.version()).forEach {
            assertTrue(it.toString(), it is HealthCore.Answer.Err && it.reason.startsWith(HealthCore.NOT_IN_BUILD))
        }
        assertTrue(HealthCore.unavailable()!!.startsWith("phone probe engine not in this build"))
    }

    @Test fun `a loaded engine's answer passes through and a throwing engine is an error, not a crash`() {
        HealthCore.engine = Fake(answer = "OK\n{\"status\":\"ok\"}")
        assertEquals("{\"status\":\"ok\"}", HealthCore.probe("tcp://x:1").okJson)
        HealthCore.engine = Fake(boom = true)
        assertTrue(HealthCore.analyze("{}", "[]") is HealthCore.Answer.Err)
    }

    @Test fun `the real JNI library is absent on the JVM and says so`() {
        HealthCore.engine = saved
        assertTrue("the host JVM has no libhealth_core_jni.so", HealthCore.unavailable() != null)
        assertTrue(HealthCore.probe("tcp://127.0.0.1:1") is HealthCore.Answer.Err)
    }
}
