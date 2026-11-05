package com.diegonmarcos.cloudsuperintel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1499 The APIs page: sample rows, masked tokens, reveal only from the private source, and no real-secret-shaped value in the repo. */
class AiApisTest {
    private val cfg = Apis.parse(Data.ai("apis.json"))
    private val none = PrivateSource { null }

    @Test fun fourRowsInTheOrdersOrder() {
        assertEquals(listOf("Claude Account 1", "Claude API 1", "OpenRouter 1", "OpenRouter 2"), cfg.rows.map { it.label })
        cfg.rows.forEach { r ->
            listOf(r.provider, r.login, r.keyName, r.created, r.lastUsed).forEach { assertTrue(r.id, it.isNotBlank()) }
            assertEquals(listOf("console", "docs"), r.links.map { it.label })
        }
    }

    @Test fun bundledRowsAreSampleAndTheirTokensAreMasked() {
        assertTrue(Data.ai("apis.json").contains("SAMPLE"))
        cfg.rows.forEach { r ->
            assertTrue(r.sample)
            assertTrue(r.tokenMasked, r.tokenMasked.startsWith("••••"))
            assertTrue(r.login, r.login.endsWith("@example.invalid"))
        }
    }

    @Test fun withoutThePrivateStoreTheSampleShowsAndNothingRevealsFromIt() {
        assertEquals(cfg.rows, Apis.rows(cfg, none))
        cfg.rows.forEach { assertNull(Apis.reveal(cfg, PrivateSource { "would-be-secret" }, it)) }
    }

    @Test fun privateRowsReplaceTheSampleAndATokenRevealsOnlyFromThePrivateSource() {
        val asked = mutableListOf<String>()
        val src = PrivateSource { k ->
            asked += k
            when (k) {
                cfg.rowsKey -> """[{"id":"claude-api-1","label":"Claude API 1","token_masked":"••••-1234","links":[]}]"""
                cfg.tokenKey.replace("{id}", "claude-api-1") -> "placeholder-token"
                else -> null
            }
        }
        val rows = Apis.rows(cfg, src)
        assertEquals(1, rows.size)
        assertFalse(rows[0].sample)
        assertEquals("••••-1234", rows[0].tokenMasked)
        assertEquals(listOf(cfg.rowsKey), asked)   // loading the rows asks for no token
        assertEquals("placeholder-token", Apis.reveal(cfg, src, rows[0]))
        assertEquals(listOf(cfg.rowsKey, "ai.api.claude-api-1.token"), asked)
    }

    @Test fun badPrivateRowsFallBackToTheSample() {
        assertEquals(cfg.rows, Apis.rows(cfg, PrivateSource { "not json" }))
    }

    @Test fun noRealSecretShapedValueInTheAppTree() {
        val shapes = listOf(
            Regex("sk-ant-[A-Za-z0-9_-]{8,}"), Regex("sk-or-[A-Za-z0-9_-]{8,}"), Regex("sk-[A-Za-z0-9]{20,}"), Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
            Regex("AKIA[0-9A-Z]{16}"), Regex("xox[abprs]-[A-Za-z0-9-]{10,}"), Regex("eyJ[A-Za-z0-9_-]{15,}\\.[A-Za-z0-9_-]{15,}"),
            Regex("Bearer\\s+[A-Za-z0-9._-]{16,}"), Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        )
        File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("json", "kt", "xml", "gradle") }.forEach { f ->
            val t = f.readText()
            shapes.forEach { assertNull("${f.path} holds a secret-shaped value", it.find(t)) }
        }
        assertNotNull(cfg)
    }
}
