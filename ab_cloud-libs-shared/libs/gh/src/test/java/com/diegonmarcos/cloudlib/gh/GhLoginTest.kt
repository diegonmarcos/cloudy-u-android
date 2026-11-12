package com.diegonmarcos.cloudlib.gh

import com.diegonmarcos.cloudlib.sysdns.ResolverProxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub sign-in as the phone runs it, with a FAKE gh: a shell that prints what the pinned gh
 * prints during `gh auth login` (the pin's measured transcript) and then blocks, as gh does while
 * it polls GitHub. The phone's failure was a sign-in that never showed a code; these hold that the
 * code reaches the job — what the engine's loginPoll hands the card — while gh is still running,
 * and that a failure is gh's own words.
 */
class GhLoginTest {

    private val transcript = listOf(
        "",
        "! First copy your one-time code: B1D3-EA43",
        "Open this URL to continue in your web browser: https://github.com/login/device",
    )

    /** A gh that prints [lines], then either blocks (exec sleep, so destroy() kills it) or exits [exit]. */
    private fun fakeGh(lines: List<String>, exit: Int? = null): Process {
        val script = lines.joinToString("; ") { "echo '$it'" } + "; " + (exit?.let { "exit $it" } ?: "exec sleep 120")
        return ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
    }

    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return done()
    }

    /** Did the code reach the job while gh was still alive, reading gh with [drain]? */
    private fun codeSeenWhileGhRuns(drain: (Process, (String) -> Unit) -> GhRunner.Result): Boolean {
        val gh = fakeGh(transcript)
        val job = GhLogin("github.com") { onLine -> drain(gh, onLine) }.start()
        try {
            return waitFor(5_000) { job.code.isNotEmpty() } && gh.isAlive && job.running
        } finally {
            gh.destroy()
        }
    }

    @Test
    fun theCodeAndThePageReachTheJobWhileGhIsStillWaiting() {
        val gh = fakeGh(transcript)
        val job = GhLogin("github.com") { onLine -> GhRunner.drain(gh, onLine) }.start()
        try {
            assertTrue("the one-time code never reached the job", waitFor(5_000) { job.code.isNotEmpty() && job.url.isNotEmpty() })
            assertEquals("B1D3-EA43", job.code)
            assertEquals("https://github.com/login/device", job.url)
            assertTrue("gh must still be polling when the code is shown", gh.isAlive)
            assertTrue(job.running)
            assertNull("a gh that is still waiting has no verdict", job.ended)
        } finally {
            gh.destroy()
        }
        assertTrue("the job never noticed gh ending", waitFor(5_000) { !job.running })
        assertNotNull(job.ended)
    }

    /**
     * The probe above can fail: reading gh to the END before handing out lines — the shape that
     * would keep the code off screen until gh gave up — is caught by the very same check. Without
     * this, a probe that always passed would read exactly like a working sign-in.
     */
    @Test
    fun readingGhToTheEndFirstIsCaught() {
        assertTrue(codeSeenWhileGhRuns(GhRunner::drain))
        val toTheEnd: (Process, (String) -> Unit) -> GhRunner.Result = { p, onLine ->
            p.outputStream.close()
            val all = p.inputStream.bufferedReader().readText()
            all.lines().forEach(onLine)
            GhRunner.Result(p.waitFor(), all)
        }
        assertFalse(codeSeenWhileGhRuns(toTheEnd))
    }

    /** What the phone printed: gh's DNS failure. The verdict is gh's exit and BOTH of its lines. */
    @Test
    fun aFailureIsGhsOwnWordsNotTheLastLine() {
        val gh = fakeGh(listOf("error connecting to github.com", "check your internet connection or https://githubstatus.com"), exit = 1)
        val job = GhLogin("github.com") { onLine -> GhRunner.drain(gh, onLine) }.start()
        assertTrue(waitFor(5_000) { !job.running })
        val ended = job.ended!!
        assertEquals(1, ended.exitCode)
        assertEquals("", job.code)
        assertEquals(
            "error connecting to github.com · check your internet connection or https://githubstatus.com",
            GhOutput.why(ended.output),
        )
        // The prompt and the clipboard notice are not reasons: a sign-in that showed a code and
        // then failed names only what went wrong.
        assertEquals(
            "boom",
            GhOutput.why((transcript + "! Failed to copy one-time code to clipboard" + "boom").joinToString("\n")),
        )
    }

    // ── ResolverProxy: how gh reaches GitHub from an app with no /etc/resolv.conf ──

    private fun connect(proxy: ResolverProxy, target: String, auth: String?): Pair<String, Socket> {
        val port = proxy.url.substringAfterLast(':').toInt()
        val s = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        val head = "CONNECT $target HTTP/1.1\r\nHost: $target\r\n" +
            (auth?.let { "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(it.toByteArray()) + "\r\n" } ?: "") + "\r\n"
        s.getOutputStream().write(head.toByteArray())
        // Byte-wise, up to the blank line, so a tunnel's first byte is still unread afterwards.
        return ResolverProxy.readHead(s.getInputStream())!!.first() to s
    }

    private fun credentialOf(proxy: ResolverProxy) = proxy.url.substringAfter("//").substringBefore('@')

    @Test
    fun theProxyTunnelsOnlyForItsOwnGhToItsOwnHosts() {
        val echo = ServerSocket(0)
        thread(isDaemon = true) { echo.accept().use { c -> c.getOutputStream().write(c.getInputStream().read()) } }
        val proxy = ResolverProxy("gh", setOf("localhost"), echo.localPort, idleMs = 60_000) {}

        val (open, s) = connect(proxy, "localhost:${echo.localPort}", credentialOf(proxy))
        s.use {
            assertEquals("HTTP/1.1 200 Connection established", open)
            s.getOutputStream().write(42)
            assertEquals("bytes cross the tunnel both ways", 42, s.getInputStream().read())
        }

        val (noAuth, a) = connect(proxy, "localhost:${echo.localPort}", null)
        a.close()
        assertTrue(noAuth, noAuth.startsWith("HTTP/1.1 407"))
        val (wrongAuth, b) = connect(proxy, "localhost:${echo.localPort}", "gh:guessed")
        b.close()
        assertTrue(wrongAuth, wrongAuth.startsWith("HTTP/1.1 407"))
        val (elsewhere, c) = connect(proxy, "example.com:${echo.localPort}", credentialOf(proxy))
        c.close()
        assertEquals("HTTP/1.1 403 the gh engine does not tunnel to example.com:${echo.localPort}", elsewhere)
        val (otherPort, d) = connect(proxy, "localhost:1", credentialOf(proxy))
        d.close()
        assertTrue(otherPort, otherPort.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun anUnreachableHostIsNamedWithAndroidsReason() {
        val proxy = ResolverProxy("gh", setOf("no-such-host.invalid"), 443, idleMs = 60_000) {}
        val (status, s) = connect(proxy, "no-such-host.invalid:443", credentialOf(proxy))
        s.close()
        assertTrue(status, status.startsWith("HTTP/1.1 502 cannot reach no-such-host.invalid: DNS: no address for no-such-host.invalid"))
    }

    // #726 the card has to say WHICH layer failed: gh's own line is the same for all of them.
    @Test
    fun aRefusedPortIsNamedAsTcpNotDns() {
        val closed = ServerSocket(0).run { localPort.also { close() } }
        val proxy = ResolverProxy("gh", setOf("127.0.0.1"), closed, idleMs = 60_000) {}
        val (status, s) = connect(proxy, "127.0.0.1:$closed", credentialOf(proxy))
        s.close()
        assertTrue(status, status.startsWith("HTTP/1.1 502 cannot reach 127.0.0.1: TCP: 127.0.0.1:$closed refused the connection"))
        assertTrue(ResolverProxy.probe("no-such-host.invalid").startsWith("DNS: "))
        assertTrue(ResolverProxy.probe("127.0.0.1", closed).startsWith("TCP: "))
        val open = ServerSocket(0)
        open.use { assertTrue(ResolverProxy.probe("127.0.0.1", it.localPort).contains("reachable")) }
    }

    /**
     * #729 THE PHONE'S "Post .../access_token: unexpected EOF", REPRODUCED: GitHub's side of the
     * tunnel dies after gh has written its poll (a fake GitHub that reads the POST, then resets).
     * Go reads a clean TCP end under TLS as exactly that "unexpected EOF", naming nothing; the tunnel
     * used to hand gh a clean end for every ending. A broken upstream must reach gh as a broken
     * connection, and the engine must keep where and how it broke.
     */
    @Test
    fun aTunnelTheNetworkBreaksMidPostReachesGhAsABreakNotAnEnd() {
        val gitHub = ServerSocket(0)
        thread(isDaemon = true) {
            gitHub.accept().let { c ->
                c.getInputStream().read(ByteArray(256)) // the POST arrives...
                c.setSoLinger(true, 0) // ...and the connection dies under it: RST, as a dead path gives
                c.close()
            }
        }
        val proxy = ResolverProxy("gh", setOf("localhost"), gitHub.localPort, idleMs = 60_000) {}
        val (open, s) = connect(proxy, "localhost:${gitHub.localPort}", credentialOf(proxy))
        s.use {
            assertEquals("HTTP/1.1 200 Connection established", open)
            s.getOutputStream().write("POST /login/oauth/access_token HTTP/1.1\r\nHost: github.com\r\n\r\n".toByteArray())
            val answer = runCatching { s.getInputStream().read() }
            assertTrue("a tunnel the network broke reached gh as a clean end (${answer.getOrNull()}), " +
                "which Go reports as \"unexpected EOF\"", answer.isFailure)
        }
        assertTrue("the engine kept no word of where the tunnel broke", waitFor(5_000) { proxy.lastDrop != null })
        assertTrue(proxy.lastDrop!!, proxy.lastDrop!!.contains(ResolverProxy.BROKEN) && proxy.lastDrop!!.contains("localhost:${gitHub.localPort}"))
    }

    /** A fake GitHub that answers each byte with itself, after [delayFor] ms for that byte's ordinal. */
    private fun answering(delayFor: (Int) -> Long = { 0L }): ServerSocket = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            server.accept().use { c ->
                var i = 0
                while (true) {
                    val b = c.getInputStream().read()
                    if (b < 0) break
                    Thread.sleep(delayFor(i++))
                    c.getOutputStream().write(b)
                }
            }
        }
    }

    /**
     * #729 gh polls every 6s on one kept-alive connection, and the phone's upstream died while that
     * connection sat idle; gh then wrote its next POST into it. Once GitHub has answered and the
     * tunnel is quiet, it is ended cleanly, so Go drops the idle connection and dials a fresh one.
     */
    @Test
    fun aTunnelGitHubHasAnsweredIsEndedOnceQuiet() {
        val gitHub = answering()
        val proxy = ResolverProxy("gh", setOf("localhost"), gitHub.localPort, idleMs = 300) {}
        val (_, s) = connect(proxy, "localhost:${gitHub.localPort}", credentialOf(proxy))
        s.use {
            s.getOutputStream().write(7)
            assertEquals(7, s.getInputStream().read())
            val after = runCatching { s.getInputStream().read() }
            assertEquals("a quiet tunnel was kept open for gh's next poll to write into", -1, after.getOrNull())
        }
    }

    /** ...but a request still waiting for its answer (gh spoke last) is never cut, however slow GitHub is. */
    @Test
    fun aRequestWaitingForItsAnswerIsNeverCutForQuiet() {
        val gitHub = answering { i -> if (i == 1) 1_200L else 0L }
        val proxy = ResolverProxy("gh", setOf("localhost"), gitHub.localPort, idleMs = 300) {}
        val (_, s) = connect(proxy, "localhost:${gitHub.localPort}", credentialOf(proxy))
        s.use {
            s.getOutputStream().write(1)
            assertEquals("the first answer", 1, s.getInputStream().read())
            s.getOutputStream().write(2) // gh's next request, answered 1.2s later: 4x the quiet limit
            assertEquals("a request still waiting for GitHub was cut as quiet", 2, runCatching { s.getInputStream().read() }.getOrNull())
        }
    }

    /**
     * #729 gh's token poll runs while the user is in the browser, so the engine's network has to be
     * held from BEFORE gh starts until AFTER gh exits (on the phone the hold is GhLoginKeeper, a
     * foreground service; Android 15 cut the poll's network as a DNS failure without it). Held late,
     * released early or not at all, and the order below is different.
     */
    @Test
    fun theEngineHoldsItsNetworkFromBeforeGhStartsUntilGhHasExited() {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val gh = fakeGh(transcript)
        val job = GhLogin("github.com", hold = { events += "hold"; { events += "release" } }) { onLine ->
            events += "gh"; GhRunner.drain(gh, onLine)
        }.start()
        try {
            assertTrue("the one-time code never reached the job", waitFor(5_000) { job.code.isNotEmpty() })
            assertEquals("the hold is taken before gh runs and kept while gh polls", listOf("hold", "gh"), events.toList())
        } finally {
            gh.destroy()
        }
        assertTrue("the job never noticed gh ending", waitFor(5_000) { !job.running })
        assertTrue("the hold was never released", waitFor(5_000) { "release" in events })
        assertEquals(listOf("hold", "gh", "release"), events.toList())
    }

    /** A gh run that throws still gives the hold back: a stuck foreground service outlives every sign-in. */
    @Test
    fun aSignInThatThrowsStillReleasesTheHold() {
        val released = java.util.concurrent.atomic.AtomicInteger()
        val job = GhLogin("github.com", hold = { { released.incrementAndGet(); Unit } }) { throw IllegalStateException("gh blew up") }.start()
        assertTrue("the job never ended", waitFor(5_000) { !job.running })
        assertTrue("a thrown sign-in kept the hold", waitFor(5_000) { released.get() == 1 })
    }

    /** #1692 a sign-in abandoned in the browser: at the code's expiry gh is ended and the hold given back. */
    @Test
    fun anAbandonedSignInReleasesAtExpiry() {
        val gh = fakeGh(listOf("! First copy your one-time code: B1D3-EA43"))
        val released = java.util.concurrent.atomic.AtomicInteger()
        val job = GhLogin("github.com", hold = { { released.incrementAndGet(); Unit } }, kill = { gh.destroyForcibly() }, expiresInMs = 400) { onLine ->
            GhRunner.drain(gh, onLine)
        }.start()
        assertTrue("the code never arrived", waitFor(5_000) { job.code == "B1D3-EA43" })
        assertTrue("the abandoned sign-in was never stopped", waitFor(5_000) { !job.running })
        assertEquals("the hold is given back exactly once", 1, released.get())
        assertFalse("gh still runs after expiry", gh.isAlive)
        assertTrue(job.ended!!.output.contains("expired"))
        assertTrue(job.ended!!.exitCode != 0)
    }

    /** #1692 Cancel on the notification ends gh and releases at once. */
    @Test
    fun cancelReleasesAndEndsGh() {
        val gh = fakeGh(listOf("! First copy your one-time code: B1D3-EA43"))
        val released = java.util.concurrent.atomic.AtomicInteger()
        val job = GhLogin("github.com", hold = { { released.incrementAndGet(); Unit } }, kill = { gh.destroyForcibly() }) { onLine ->
            GhRunner.drain(gh, onLine)
        }.start()
        assertTrue(waitFor(5_000) { job.code.isNotEmpty() })
        job.cancel()
        assertEquals("cancel releases at once", 1, released.get())
        assertTrue("gh survived the cancel", waitFor(5_000) { !job.running })
        job.cancel()
        assertEquals("a second cancel releases nothing twice", 1, released.get())
        assertTrue(job.ended!!.output.contains("cancelled"))
    }

    /** #1692 every exit path releases exactly once: success, gh's failure, a throw, expiry, cancel. */
    @Test
    fun everyExitPathReleasesExactlyOnce() {
        val paths = mapOf<String, (java.util.concurrent.atomic.AtomicInteger) -> GhLogin>(
            "success" to { r -> GhLogin("github.com", hold = { { r.incrementAndGet(); Unit } }) { GhRunner.Result(0, "ok") } },
            "failure" to { r -> GhLogin("github.com", hold = { { r.incrementAndGet(); Unit } }) { GhRunner.Result(1, "no") } },
            "throw" to { r -> GhLogin("github.com", hold = { { r.incrementAndGet(); Unit } }) { throw IllegalStateException("x") } },
            "expiry" to { r -> val gh = fakeGh(emptyList()); GhLogin("github.com", hold = { { r.incrementAndGet(); Unit } }, kill = { gh.destroyForcibly() }, expiresInMs = 200) { GhRunner.drain(gh, it) } },
        )
        for ((name, make) in paths) {
            val r = java.util.concurrent.atomic.AtomicInteger()
            val job = make(r).start()
            assertTrue("$name never ended", waitFor(5_000) { !job.running })
            assertTrue("$name kept the hold", waitFor(5_000) { r.get() == 1 })
            Thread.sleep(300)
            assertEquals("$name released more than once", 1, r.get())
            assertNotNull("$name has no verdict", job.ended)
        }
        val r = java.util.concurrent.atomic.AtomicInteger()
        val gh = fakeGh(emptyList())
        val job = GhLogin("github.com", hold = { { r.incrementAndGet(); Unit } }, kill = { gh.destroyForcibly() }) { GhRunner.drain(gh, it) }.start()
        job.cancel()
        assertTrue(waitFor(5_000) { !job.running })
        assertEquals("cancel released more than once", 1, r.get())
    }

    /** #1692 gh's own expiry, when it prints one, replaces the 15 min default. */
    @Test
    fun theCodesExpiryIsReadFromGhWhenPrinted() {
        assertEquals(15 * 60_000L, GhOutput.expiresInMs("This code expires in 15 minutes"))
        assertEquals(90_000L, GhOutput.expiresInMs("code expires in 90 seconds"))
        assertNull(GhOutput.expiresInMs("! First copy your one-time code: B1D3-EA43"))
        assertEquals(15 * 60_000L, GhLogin.DEFAULT_EXPIRY_MS)
    }
}
