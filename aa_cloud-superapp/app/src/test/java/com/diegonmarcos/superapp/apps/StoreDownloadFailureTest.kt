package com.diegonmarcos.superapp.apps

import android.app.Application
import com.diegonmarcos.superapp.appstore.StoreRowError
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.source.Download
import com.diegonmarcos.superapp.updater.source.DownloadFailure
import com.diegonmarcos.superapp.network.FleetDns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * #831 The phone could not resolve github.com / ghcr.io and the Store said
 * "stalled at 0 of unknown bytes … over 5 attempts", cut mid-sentence beside a
 * ✓. Classification, fail-fast, leg order and the row's look are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreDownloadFailureTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun reset() { DownloadFailure.activeResolver = { null } }

    /** A one-status HTTP server counting the requests it answered. */
    private fun server(code: Int, hits: AtomicInteger): ServerSocket {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                hits.incrementAndGet()
                s.use {
                    val r = it.getInputStream().bufferedReader()
                    while (r.readLine()?.isNotEmpty() == true) {}
                    it.getOutputStream().write("HTTP/1.1 $code X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
        return ss
    }

    @Test fun `an unresolvable host fails ONCE, as DNS, naming the host and the active resolver`() {
        DownloadFailure.activeResolver = { "10.9.0.1 · via VPN" }
        val started = System.currentTimeMillis()
        val t = try {
            Download.toFile("https://no-such-host.invalid/a.apk", File(tmp.root, "a.apk")); null
        } catch (e: IOException) { e }
        assertNotNull("must throw", t)
        assertTrue("no retry backoff spent on a name that cannot resolve (${System.currentTimeMillis() - started} ms)",
            System.currentTimeMillis() - started < 2_000)
        assertFalse("not reported as a stall", t is Download.Stalled)
        assertEquals(DownloadFailure.Kind.DNS, DownloadFailure.kind(t!!))
        assertEquals("DNS: cannot resolve no-such-host.invalid (active resolver: 10.9.0.1 · via VPN)",
            DownloadFailure.describe(t))
        assertTrue(DownloadFailure.mentionsDns(DownloadFailure.describe(t)))
    }

    @Test fun `the raw Android wording is classified too, host read out of it`() {
        val e = IOException("wrapped", UnknownHostException("Unable to resolve host \"ghcr.io\": No address associated with hostname"))
        assertEquals("DNS: cannot resolve ghcr.io (active resolver: unknown)", DownloadFailure.describe(e, null))
    }

    @Test fun `a 404 asset is asked for once and reported as not published`() {
        val hits = AtomicInteger()
        server(404, hits).use { ss ->
            val t = try {
                Download.toFile("http://127.0.0.1:${ss.localPort}/Cloud-Lib-X.apk", File(tmp.root, "x.apk")); null
            } catch (e: IOException) { e }
            assertEquals("one request, no retries", 1, hits.get())
            assertEquals(DownloadFailure.Kind.NOT_PUBLISHED, DownloadFailure.kind(t!!))
            assertEquals("not published on the release yet (HTTP 404)", DownloadFailure.describe(t))
        }
    }

    @Test fun `a 503 is still retried - only facts fail fast`() {
        val hits = AtomicInteger()
        server(503, hits).use { ss ->
            try {
                Download.toFile("http://127.0.0.1:${ss.localPort}/y.apk", File(tmp.root, "y.apk"),
                    shouldCancel = { hits.get() >= 2 })
                fail("must not succeed")
            } catch (_: java.util.concurrent.CancellationException) {
            } catch (e: IOException) { fail("a transient failure must be retried, got: ${e.message}") }
            assertEquals(2, hits.get())
            assertEquals(DownloadFailure.Kind.OTHER,
                DownloadFailure.kind(DownloadFailure.HttpStatus(503, "u", null)))
        }
    }

    @Test fun `legs are the declared mesh leg first, then release and ghcr (#837, #1093) - nothing else`() {
        assertEquals(listOf("mesh", "release", "ghcr"), Fleet.sourceOrder)
    }

    @Test fun `resolver summary names servers, VPN and active Private DNS`() {
        val a = FleetDns.AndroidDns("hostname", "dns.example", true, "dns.example", listOf("10.9.0.1"), true)
        assertEquals("10.9.0.1 · via VPN · Private DNS dns.example", FleetDns.summary(a))
        val none = FleetDns.AndroidDns(null, null, null, null, emptyList(), false)
        assertEquals("no DNS servers on the active network", FleetDns.summary(none))
    }

    @Test fun `a failed row is marked with a warning, never a tick, and keeps the whole reason`() {
        val reason = "could not download wallet: release → DNS: cannot resolve github.com (active resolver: 10.9.0.1) | " +
            "ghcr → DNS: cannot resolve ghcr.io (active resolver: 10.9.0.1 · via VPN · Private DNS dns.example)"
        val look = StoreRowError.of(reason, null)!!
        assertEquals("⚠", look.glyph)
        assertFalse(look.glyph.contains("✓") || look.meta.contains("✓"))
        assertEquals("the error area carries the reason whole", reason, look.error)
        assertFalse("the one-line meta slot never carries the reason", look.meta.contains("resolve"))
        assertTrue("DNS failure offers the DNS page button", look.dnsButton)
        assertTrue(StoreRowError.foldable(reason))
        assertFalse(StoreRowError.banner("Cloud Lib Wallet").contains("resolve"))
    }

    @Test fun `stage failure wins, non-DNS has no DNS button, no failure no look`() {
        assertNull(StoreRowError.of(null, null))
        assertNull(StoreRowError.of(" ", ""))
        assertEquals("stage", StoreRowError.of("stage", "state")!!.error)
        assertEquals("state", StoreRowError.of(null, "state")!!.error)
        assertFalse(StoreRowError.of("not published on the release yet (HTTP 404)", null)!!.dnsButton)
    }

    @Test fun `guard - the Store row paints failures into its own error area`() {
        val dir = "../../ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
        val src = File(dir + "StoreCloudFragment.kt").takeIf { it.isFile } ?: return
        val s = src.readText()
        assertTrue(s.contains("ui.error?.let { ErrorArea(ctx, app, it) }"))
        assertTrue(s.contains("if (stg.failedAt != null) error = StoreRowError.of(stg.text, null)"))
    }
}
