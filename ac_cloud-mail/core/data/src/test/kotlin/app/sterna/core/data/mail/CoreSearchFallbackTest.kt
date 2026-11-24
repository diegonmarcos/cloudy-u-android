package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1837 phase 3: searchIndexOrCore's rule, driven with a fake core. */
class CoreSearchFallbackTest {
    private fun e(id: String) = Email(id = id)

    @Test fun theRoomIndexWinsAndTheCoreIsNotAsked() = runBlocking {
        val r = searchWithCoreFallback(listOf(e("room")), listOf("a"), 10, { error("must not ask") }, { _, _ -> error("must not load") })
        assertEquals(listOf("room"), r.map { it.id })
    }

    @Test fun anEmptyRoomAnswerAsksTheCorePerAccountAndLoadsItsRows() = runBlocking {
        val loaded = mutableListOf<Pair<String, List<String>>>()
        val r = searchWithCoreFallback(emptyList(), listOf("a", "b"), 10,
            { acc -> if (acc == "a") listOf("1", "2") else listOf("3") },
            { acc, ids -> loaded += acc to ids; ids.map { e(it) } })
        assertEquals(listOf("1", "2", "3"), r.map { it.id })
        assertEquals(listOf("a" to listOf("1", "2"), "b" to listOf("3")), loaded)
    }

    @Test fun theLimitCapsTheCoreAnswer() = runBlocking {
        val r = searchWithCoreFallback(emptyList(), listOf("a"), 2, { listOf("1", "2", "3") }, { _, ids -> ids.map { e(it) } })
        assertEquals(2, r.size)
    }

    @Test fun aCoreThatIsOffOrFailsKeepsTheEmptyAnswer() = runBlocking {
        assertEquals(emptyList<Email>(), searchWithCoreFallback(emptyList(), listOf("a"), 10, { null }, { _, _ -> error("no") }))
        assertEquals(emptyList<Email>(), searchWithCoreFallback(emptyList(), listOf("a"), 10, { error("core down") }, { _, _ -> emptyList() }))
    }
}
