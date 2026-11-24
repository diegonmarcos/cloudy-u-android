package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookTest {
    private val cfg = NotebookConfig.parse(Data.asset("notebook.json"))
    private val cats = Source("s1", "Cats", "note", "Cats sleep for about fifteen hours a day. A cat's whiskers sense air movement and help it judge gaps.")
    private val tax = Source("s2", "Taxes", "note", "Freelancers in the example country file quarterly estimated income tax by the fifteenth of the month after each quarter.")
    private val sources = listOf(cats, tax)

    /** A model that must not be asked. */
    private val never: (String, String) -> String = { _, _ -> error("the model was called for a question the sources cannot answer") }

    @Test fun outOfScopeQuestionIsRefusedWithoutCallingTheModel() {
        val g = Notebook.answer("What is the capital of France?", sources, cfg, never)
        assertFalse(g.grounded)
        assertEquals(cfg.refusal, g.answer)
        assertTrue(g.cited.isEmpty())
    }

    @Test fun emptyNotebookRefuses() {
        assertFalse(Notebook.answer("How long do cats sleep?", emptyList(), cfg, never).grounded)
    }

    @Test fun inScopeQuestionIsAnsweredWithItsCitation() {
        var seenUser = ""
        val g = Notebook.answer("How many hours do cats sleep?", sources, cfg) { sys, user ->
            assertTrue("the system prompt carries the refusal line", sys.contains(cfg.refusal))
            seenUser = user
            "Cats sleep about fifteen hours a day [1]."
        }
        assertTrue(g.grounded)
        assertEquals(listOf(1), g.cited.map { it.n })
        assertTrue(seenUser.contains("fifteen hours"))
        assertFalse("an unrelated source is not sent as evidence", seenUser.contains("quarterly"))
    }

    @Test fun answerWithoutCitationIsReplacedByTheRefusal() {
        val g = Notebook.answer("How many hours do cats sleep?", sources, cfg) { _, _ -> "Cats sleep a lot, as everybody knows." }
        assertFalse(g.grounded)
        assertEquals(cfg.refusal, g.answer)
    }

    @Test fun citationOfASourceThatWasNotSuppliedIsRejected() {
        val g = Notebook.answer("How many hours do cats sleep?", sources, cfg) { _, _ -> "About fifteen hours [2]." }
        assertFalse(g.grounded)
    }

    @Test fun modelsOwnRefusalIsFlaggedAsNotGrounded() {
        val g = Notebook.answer("How many hours do cats sleep?", sources, cfg) { _, _ -> cfg.refusal }
        assertFalse(g.grounded)
        assertTrue(g.cited.isEmpty())
    }

    @Test fun passagesAreNumberedBySource() {
        val found = Notebook.retrieve("quarterly income tax freelancers", Notebook.passages(sources, cfg), cfg)
        assertEquals(listOf(2), found.map { it.first.n }.distinct())
    }

    @Test fun longSourcesAreChunkedWithOverlapAndRetrievedByTheirPassage() {
        val filler = (1..400).joinToString(" ") { "filler$it" }
        val long = Source("s3", "Long", "doc", "$filler The zebra crossing was repainted in March. $filler")
        val ps = Notebook.passages(listOf(long), cfg)
        assertTrue(ps.size > 2)
        assertTrue(ps.all { it.text.length <= cfg.chunkChars })
        val best = Notebook.retrieve("zebra crossing repainted", ps, cfg).first().first
        assertTrue(best.text.contains("zebra"))
    }

    @Test fun chunksOverlap() {
        val t = (1..300).joinToString(" ") { "w$it" }
        val c = Notebook.chunks(t, 200, 50)
        assertTrue(c.size > 1)
        assertTrue("the next chunk repeats the end of the previous one", c[1].contains(c[0].substringAfterLast(' ')))
        assertEquals("no word is lost", t.split(' ').toSet(), c.flatMap { it.split(' ') }.toSet())
    }

    @Test fun studioPromptCarriesNumberedSourcesAndIsCutToSize() {
        val g = cfg.generators.first { it.id == "summary" }
        val (sys, user) = Notebook.generatorPrompt(g, sources, cfg)
        assertTrue(sys.contains(cfg.refusal))
        assertTrue(user.contains("SOURCE [1] Cats") && user.contains("SOURCE [2] Taxes") && user.endsWith(g.prompt))
        val huge = Source("h", "Huge", "doc", "x".repeat(cfg.maxSourceChars * 2))
        assertTrue(Notebook.generatorPrompt(g, listOf(huge), cfg).second.length < cfg.maxSourceChars + 500)
    }

    @Test fun declaredGeneratorsAreSummaryFaqNotes() {
        assertEquals(setOf("summary", "faq", "notes"), cfg.generators.map { it.id }.toSet())
    }

    @Test fun htmlBecomesText() {
        val html = "<html><head><title>Hi &amp; bye</title><style>p{}</style></head><body><script>x()</script><p>Hello <b>world</b></p></body></html>"
        assertEquals("Hello world", Notebook.htmlText(html.substringAfter("</head>")))
        assertEquals("Hi & bye", Notebook.title(html, "fallback"))
        assertEquals("fallback", Notebook.title("<p>no title</p>", "fallback"))
    }

    @Test fun notebooksSurviveTheStore() {
        val d = NotebookDoc("n1", "Mine", sources, listOf(ChatTurn("user", "q"), ChatTurn("assistant", cfg.refusal, grounded = false), ChatTurn("assistant", "a [1]", cited = listOf(1), passages = listOf("Cats: x"))))
        val back = NotebookStore.parse(NotebookStore.toJson(listOf(d)))
        assertEquals(listOf(d), back)
    }

    /** #1547 the notebook is served, never an APK asset: what it uses while the dataset loads is the served file's grounding and refusal, field for field (retrieval stays BM25 only until the served copy brings the semantic knobs). */
    @Test fun theDefaultConfigHoldsTheServedGroundingAndRefusal() {
        // the generators come out of a JSON object, whose key order is the JVM's, so they are compared by id
        assertEquals(cfg.generators.sortedBy { it.id }, NotebookConfig.DEFAULT.generators.sortedBy { it.id })
        assertEquals(cfg.copy(semantic = null, generators = NotebookConfig.DEFAULT.generators), NotebookConfig.DEFAULT)
        assertTrue(NotebookConfig.DEFAULT.groundingSystem.contains(NotebookConfig.DEFAULT.refusal))
        assertEquals(cfg.refusal, Notebook.answer("What is the capital of France?", sources, NotebookConfig.DEFAULT) { _, _ -> error("model called") }.answer)
    }
}
