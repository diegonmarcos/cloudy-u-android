package app.sterna.send.delivery

import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import com.diegonmarcos.superapp.fleetendpoints.FleetHeaders
import com.diegonmarcos.superapp.fleetendpoints.MeshProbe
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1802: the client side of the fleet mail API contract (cloudy-apps-communication-mcp
 * `GET /v1/sent/{messageId}/status`): the wire JSON -> stages mapping, and the transport (mesh
 * first with no bearer, public hub path with the fleet bearer, 404 = no record, errors throw).
 */
class FleetDeliveryStatusSourceTest {

    private val mesh = MockWebServer().apply { start() }
    private val pub = MockWebServer().apply { start() }

    @After fun stop() { mesh.shutdown(); pub.shutdown() }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private val fullJson = """
      {"id":"<m1@fleet>","found":true,"messageId":"<m1@fleet>","queueId":"Q1",
       "stages":{
         "submitted":{"state":"ok","at":"${iso(1_000)}","reason":null},
         "received":{"state":"ok","at":"${iso(2_000)}","reason":null,"queueId":"Q1"},
         "sender_valid":{"state":"ok","at":"${iso(3_000)}","reason":null,
            "checks":{"from_authorized":true,"dkim":{"result":"pass","selector":"s1"},"spf":"fail","dmarc":"pass"}},
         "delivered":{"state":"pending","at":null,"reason":null,"recipients":[
            {"rcpt":"a@x.org","state":"ok","at":"${iso(4_000)}","reason":"250 2.0.0 OK","hop":"mx","host":"mx.x.org"},
            {"rcpt":"b@y.org","state":"pending","at":"${iso(5_000)}","reason":"250 accepted by relay","hop":"relay","host":"relay.oci"}
         ]}}}
    """.trimIndent()

    // ── the wire mapping ──────────────────────────────────────────────────────────────────────

    @Test fun `every server stage maps, the relay 250 stays pending and SPF does not gate`() {
        val r = DeliveryStatusJson.parse(JSONObject(fullJson))!!
        assertEquals(ServerDeliveryReport.Submission(true, 2_000, 250, "<m1@fleet>", "queue Q1"), r.submission)
        val auth = r.sender!!
        assertEquals(true, auth.dkimSigned); assertEquals("s1", auth.dkimSelector)
        assertEquals(false, auth.spfAligned); assertEquals(true, auth.dmarcAligned)
        assertEquals(RecipientState.DELIVERED, r.recipients[0].state)
        assertEquals(250, r.recipients[0].smtpCode); assertEquals("mx.x.org", r.recipients[0].remoteMx)
        assertTrue("relay 250 is accepted-by-relay, not delivered", r.recipients[1].acceptedByRelay)

        val t = DeliveryStateMachine.timeline(PhoneDelivery(queuedInOutbox = false, handedOffAtMillis = 500), r)
        assertEquals(StageMark.Confirmed(2_000), t[DeliveryStage.SERVER_RECEIVED].mark)
        assertEquals("a reported SPF fail does not fail the stage", StageMark.Confirmed(3_000), t[DeliveryStage.VALID_SENDER].mark)
        assertEquals("one recipient only at the relay: the 4th mark is pending", StageMark.Pending, t[DeliveryStage.SENT].mark)
    }

    @Test fun `a refused submission, a failed sender and a bounce each map to their X`() {
        fun parse(stages: String) = DeliveryStatusJson.parse(JSONObject("""{"found":true,"messageId":"<m>","stages":$stages}"""))!!
        val refused = parse("""{"submitted":{"state":"failed","at":10,"reason":"535 5.7.8 auth failed"}}""")
        assertEquals(ServerDeliveryReport.Submission(false, 10_000, 535, "<m>", "535 5.7.8 auth failed"), refused.submission)

        val badSender = parse("""{"received":{"state":"ok","at":1},"sender_valid":{"state":"failed","at":2,"reason":"From not owned",
            "checks":{"from_authorized":false,"dkim":true,"spf":true,"dmarc":true}}}""")
        val t1 = DeliveryStateMachine.timeline(PhoneDelivery(false), badSender)
        assertEquals(
            StageMark.Failed(2_000, DeliveryReason.SenderRejected(listOf(SenderCheck.ACCOUNT_MAY_USE_FROM), "From not owned")),
            t1[DeliveryStage.VALID_SENDER].mark,
        )

        val bounce = parse("""{"received":{"state":"ok"},"sender_valid":{"state":"ok"},"delivered":{"state":"failed","recipients":[
            {"rcpt":"c@z.org","state":"failed","at":9,"reason":"550 5.1.1 no such user","hop":"dsn","host":"mx.z.org"}]}}""")
        val t2 = DeliveryStateMachine.timeline(PhoneDelivery(false), bounce)
        val mark = t2[DeliveryStage.SENT].mark as StageMark.Failed
        assertEquals(550, (mark.reason as DeliveryReason.Bounced).recipients.single().smtpCode)
    }

    @Test fun `a queued recipient with a remote reason is a deferral`() {
        val r = DeliveryStatusJson.parse(JSONObject("""{"found":true,"stages":{"received":{"state":"ok"},"sender_valid":{"state":"ok"},
            "delivered":{"state":"pending","recipients":[{"rcpt":"d@w.org","state":"pending","reason":"451 4.7.1 greylisted","hop":"queue"}]}}}"""))!!
        val t = DeliveryStateMachine.timeline(PhoneDelivery(false), r)
        assertTrue(t[DeliveryStage.SENT].mark is StageMark.Deferred)
    }

    @Test fun `found false and missing stages are no record, never a guess`() {
        assertNull(DeliveryStatusJson.parse(JSONObject("""{"id":"x","found":false}""")))
        val empty = DeliveryStatusJson.parse(JSONObject("""{"found":true,"stages":{}}"""))!!
        assertNull(empty.submission); assertNull(empty.sender); assertTrue(empty.recipients.isEmpty())
    }

    // ── the transport ─────────────────────────────────────────────────────────────────────────

    private fun source(meshUp: Boolean): FleetDeliveryStatusSource {
        val bearers = FleetBearers().apply { set("localhost", "tok-test") }
        val router = EndpointRouter(probe = MeshProbe { _, _, _ -> meshUp }, requireHttpsPublic = false)
        val publicBase = "http://localhost:${pub.port}/cloudy-apps-communication-mcp"
        router.set(publicBase, "http://127.0.0.1:${mesh.port}", EndpointPolicy.MESH_FIRST, listOf("/v1"))
        return FleetDeliveryStatusSource(bearers = bearers, router = router, baseUrl = { publicBase })
    }

    private val key = DeliveryKey("acct", "<m1@fleet>")

    @Test fun `mesh first, with no bearer, at the service root`() = runTest {
        mesh.enqueue(MockResponse().setBody(fullJson))
        val r = source(meshUp = true).fetch(key)
        val req = mesh.takeRequest()
        assertEquals("/v1/sent/%3Cm1%40fleet%3E/status", req.path)
        assertNull("the bearer never reaches the mesh", req.getHeader(FleetHeaders.FLEET))
        assertEquals("<m1@fleet>", r!!.submission!!.messageId)
        assertEquals(0, pub.requestCount)
    }

    @Test fun `public fallback under the hub path, with the fleet bearer`() = runTest {
        pub.enqueue(MockResponse().setBody(fullJson))
        source(meshUp = false).fetch(key)
        val req = pub.takeRequest()
        assertEquals("/cloudy-apps-communication-mcp/v1/sent/%3Cm1%40fleet%3E/status", req.path)
        assertEquals("Bearer tok-test", req.getHeader(FleetHeaders.FLEET))
    }

    @Test fun `404 is no record and an error status throws with its code`() = runTest {
        mesh.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found","code":"not_found"}"""))
        assertNull(source(meshUp = true).fetch(key))
        mesh.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"slow down","code":"rate_limited"}"""))
        val e = runCatching { source(meshUp = true).fetch(key) }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("rate_limited"))
    }

    // ── the server's client-shaped fields (cloudy-u-containers 07f51da) ─────────────────────────

    private val flatJson = """
      {"found":true,"account":"me@example.org","messageId":"<f1@fleet>","queueId":"Q9",
       "submission":{"accepted":true,"at":"${iso(2_000)}","smtp_code":250,"message_id":"<f1@fleet>","text":"queued as Q9"},
       "sender":{"may_use_from":true,"dkim_signed":true,"dkim_selector":"s2","spf_aligned":false,"dmarc_aligned":true,"at":"${iso(3_000)}","text":null},
       "recipients":[
         {"address":"a@x.org","state":"delivered","at":"${iso(4_000)}","smtp_code":250,"remote_mx":"mx.x.org","detail":"250 OK"},
         {"address":"b@y.org","state":"pending","at":"${iso(5_000)}","smtp_code":250,"remote_mx":"relay.oci","detail":"accepted by relay"},
         {"address":"c@z.org","state":"deferred","at":"${iso(6_000)}","smtp_code":451,"remote_mx":"mx.z.org","detail":"greylisted"}],
       "stages":{"received":{"state":"failed"}}}
    """.trimIndent()

    @Test fun `the top-level fields win over stages and map one to one`() {
        val r = DeliveryStatusJson.parse(JSONObject(flatJson))!!
        assertEquals(ServerDeliveryReport.Submission(true, 2_000, 250, "<f1@fleet>", "queued as Q9"), r.submission)
        assertEquals("s2", r.sender!!.dkimSelector); assertEquals(false, r.sender!!.spfAligned)
        assertEquals(listOf(RecipientState.DELIVERED, RecipientState.PENDING, RecipientState.DEFERRED), r.recipients.map { it.state })
        assertTrue(r.recipients[1].acceptedByRelay)
        assertEquals(451, r.recipients[2].smtpCode)
        val t = DeliveryStateMachine.timeline(PhoneDelivery(false, 1_000), r)
        assertEquals(StageMark.Confirmed(2_000), t[DeliveryStage.SERVER_RECEIVED].mark)
        assertEquals("SPF reported, not gating", StageMark.Confirmed(3_000), t[DeliveryStage.VALID_SENDER].mark)
        assertTrue(t[DeliveryStage.SENT].mark is StageMark.Deferred)
    }

    @Test fun `unrecorded top-level fields stay pending`() {
        val r = DeliveryStatusJson.parse(JSONObject("""{"found":true,"submission":{"accepted":null},
            "sender":{"may_use_from":null,"dkim_signed":null,"spf_aligned":null,"dmarc_aligned":null},"recipients":[]}"""))!!
        assertNull(r.submission)
        assertEquals(StageMark.Pending, DeliveryStateMachine.validSender(r.sender))
    }

    @Test fun `the account scope rides along as a query parameter`() = runTest {
        mesh.enqueue(MockResponse().setBody(flatJson))
        source(meshUp = true).fetch(DeliveryKey("acct", "<f1@fleet>", accountAddress = "me+x@example.org"))
        assertEquals("/v1/sent/%3Cf1%40fleet%3E/status?account=me%2Bx%40example.org", mesh.takeRequest().path)
    }

    @Test fun `an endpoint that cannot be had is a failure to ask, never a guess`() = runTest {
        val e = runCatching {
            FleetDeliveryStatusSource(FleetBearers(), EndpointRouter(), baseUrl = { throw IOException("Cloud Account: not granted") }).fetch(key)
        }.exceptionOrNull()
        assertTrue(e is IOException)
    }
}
