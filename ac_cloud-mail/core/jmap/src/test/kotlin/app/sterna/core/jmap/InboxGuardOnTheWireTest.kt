package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Mailbox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #1674: every message keeps the Inbox. Asserted on the `Email/set` patch the client actually sends:
 * a move / archive / label removal out of the Inbox only ADDS the target; a delete (Trash) or a spam
 * report (Junk) is the only thing that may send `mailboxIds/<inbox>: null`.
 */
class InboxGuardOnTheWireTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()
    private val auth = BasicAuth("u", "p")
    private lateinit var session: JmapSession

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        session = JmapSession(apiUrl = server.url("/jmap/api/").toString())
        server.enqueue(MockResponse().setBody(MAILBOXES_JSON))
        runBlocking { client.getMailboxes(session, "acc1", auth) }
        server.takeRequest()
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `fetching mailboxes records the inbox guard`() {
        val g = client.inboxGuardOf("acc1")!!
        assertEquals("mbInbox", g.inboxId)
        assertEquals(setOf("mbTrash", "mbJunk"), g.leaveInboxFor)
    }

    @Test fun `archive from inbox keeps inbox`() {
        val p = sent { client.move(session, "acc1", "e1", "mbArchive", auth, sourceMailboxId = "mbInbox") }
        assertEquals(setOf("mailboxIds/mbArchive"), p.keys)
    }

    @Test fun `move to a label from inbox keeps inbox`() {
        val p = sent { client.move(session, "acc1", "e1", "mbNoAlerts", auth, sourceMailboxId = "mbInbox") }
        assertFalse("mailboxIds/mbInbox" in p.keys)
    }

    @Test fun `removing the inbox label is refused`() {
        val p = sent { client.removeFromMailbox(session, "acc1", "e1", "mbInbox", auth) }
        assertFalse("mailboxIds/mbInbox" in p.keys)
    }

    @Test fun `bulk move keeps inbox per id`() {
        server.enqueue(MockResponse().setBody(SET_OK_2))
        runBlocking {
            client.move(session, "acc1", listOf("e1", "e2"), "mbArchive", auth, mapOf("e1" to "mbInbox", "e2" to "mbWork"))
        }
        val upd = args().getValue("update").jsonObject
        assertEquals(setOf("mailboxIds/mbArchive"), upd.getValue("e1").jsonObject.keys)
        assertEquals(JsonNull, upd.getValue("e2").jsonObject["mailboxIds/mbWork"])
    }

    @Test fun `delete to trash may leave inbox`() {
        val p = sent { client.move(session, "acc1", "e1", "mbTrash", auth, sourceMailboxId = "mbInbox") }
        assertEquals(JsonNull, p["mailboxIds/mbInbox"])
    }

    @Test fun `spam report may leave inbox`() {
        val p = sent { client.move(session, "acc1", "e1", "mbJunk", auth, sourceMailboxId = "mbInbox") }
        assertEquals(JsonNull, p["mailboxIds/mbInbox"])
    }

    @Test fun `removing another label still works`() {
        val p = sent { client.removeFromMailbox(session, "acc1", "e1", "mbWork", auth) }
        assertEquals(JsonNull, p["mailboxIds/mbWork"])
    }

    @Test fun `pure decision table`() {
        val g = InboxGuard("in", setOf("tr"))
        assertNull(inboxSafeRemove(g, "arch", "in"))
        assertNull(inboxSafeRemove(g, null, "in"))
        assertEquals("in", inboxSafeRemove(g, "tr", "in"))
        assertEquals("work", inboxSafeRemove(g, "arch", "work"))
        assertEquals("in", inboxSafeRemove(null, "arch", "in"))
        assertNull(InboxGuard.of(listOf(Mailbox(id = "a", name = "A"))))
    }

    private fun sent(call: suspend () -> Unit): JsonObject {
        server.enqueue(MockResponse().setBody(SET_OK))
        runBlocking { call() }
        return args().getValue("update").jsonObject.getValue("e1").jsonObject
    }

    private fun args(): JsonObject {
        val c = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            .getValue("methodCalls").jsonArray[0].jsonArray
        assertEquals("Email/set", c[0].jsonPrimitive.content)
        return c[1].jsonObject
    }

    private companion object {
        const val MAILBOXES_JSON = """{"methodResponses":[["Mailbox/get",{"accountId":"acc1","state":"m1","list":[
            {"id":"mbInbox","name":"Inbox","role":"inbox"},{"id":"mbArchive","name":"Archive","role":"archive"},
            {"id":"mbTrash","name":"Trash","role":"trash"},{"id":"mbJunk","name":"Junk","role":"junk"},
            {"id":"mbWork","name":"Work"},{"id":"mbNoAlerts","name":"01 Inbox - noAlerts"}],"notFound":[]},"c0"]]}"""
        const val SET_OK = """{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"s1","newState":"s2","updated":{"e1":null}},"s0"]]}"""
        const val SET_OK_2 = """{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"s1","newState":"s2","updated":{"e1":null,"e2":null}},"s0"]]}"""
    }
}
