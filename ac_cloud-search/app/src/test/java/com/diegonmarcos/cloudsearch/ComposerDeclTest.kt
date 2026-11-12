package com.diegonmarcos.cloudsearch

import com.diegonmarcos.superapp.ai.AiComposerDecl
import com.diegonmarcos.superapp.ai.AiDesign
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Cloud Search's composer declaration (the served dataset data/fleet/app/com.diegonmarcos.cloudsearch/composer.json, #1547) holds to libs:ai's shared design: every control is a real one, all eight are enabled. */
class ComposerDeclTest {
    private val design = AiDesign.parse(File("../../ab_cloud-libs-shared/libs/ai/src/main/assets/ai/ai-design.json").readText())

    @Test fun researchEnablesTheWholeSharedBar() {
        val decl = AiComposerDecl.parse(File("../../data/fleet/app/com.diegonmarcos.cloudsearch/composer.json").readText(), design)
        assertEquals("cloud-search", decl.app)
        assertEquals(listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more"), design.ordered(decl.controls).map { it.id })
    }
}
