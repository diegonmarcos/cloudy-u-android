package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.FleetInstall
import com.diegonmarcos.superapp.appstore.StoreDebugApi
import com.diegonmarcos.superapp.appstore.StoreStages
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.cache.ApkCache
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * The smallest HTTP/1.1 responder that HttpURLConnection will talk to:
 * one request per connection (`Connection: close`), request line + headers in,
 * status + headers + body out, no body on HEAD. com.sun.net.httpserver is not
 * on the Android unit-test classpath; java.net.ServerSocket is.
 */
internal class TinyHttp(
    private val handle: (method: String, path: String, headers: Map<String, String>) -> Triple<Int, Map<String, String>, ByteArray>,
) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "tiny-http") {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { c.use { serve(it) } } }
            }
        }
    }

    private fun serve(c: Socket) {
        val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val parts = (inp.readLine() ?: return).split(" ")
        val headers = HashMap<String, String>()
        while (true) {
            val l = inp.readLine() ?: break
            if (l.isEmpty()) break
            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
        }
        val (code, extra, body) = handle(parts[0], parts[1], headers)
        val head = StringBuilder("HTTP/1.1 $code X\r\n")
        extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        val out = c.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (parts[0] != "HEAD") out.write(body)
        out.flush()
    }

    fun stop() = socket.close()
}

/**
 * #774 — A FINISHED DOWNLOAD IS NEVER FETCHED TWICE.
 *
 * The owner's report, reproduced exactly: a ~400 MB lib APK (the proot rootfs)
 * finished downloading, the system install sheet was cancelled, and the Store
 * then downloaded all of it again. The receiver had KEPT the file — the bug was
 * that the next Install never looked: Fleet.download went straight to
 * ReleaseSource, and Download.toFile only short-circuits on a complete `.part`,
 * which a finished download no longer is.
 *
 * Driven through the REAL path — Fleet.download → ReleaseSource → Download over
 * HttpURLConnection against a local server that counts asset GETs and bytes
 * served, and the REAL PackageInstallerReceiver handed STATUS_FAILURE_ABORTED.
 * No mocks of the thing under test.
 *
 * Every assertion has its NEGATIVE CONTROL beside it: the same scenario with the
 * guarded behaviour removed by hand (the cached file deleted; the `.part`
 * dropped) must move the counter. That is what makes the green meaningful — a
 * counter that cannot see a re-download would pass the main assertion forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreCacheStagesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "org.example.rootfs"
    private lateinit var server: TinyHttp
    private val assetGets = AtomicInteger()
    private val servedBytes = AtomicLong()
    private val rangesSeen = mutableListOf<String>()
    private lateinit var apk: ByteArray
    private lateinit var room0: (Context) -> Long

    /** A real zip (VerifiedApk checks the structure) big enough that a resume
     *  is distinguishable from a restart by the byte count alone. */
    private fun fakeApk(): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.setLevel(0)
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(pkg.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("assets/rootfs.bin")); z.write(ByteArray(256 * 1024) { (it % 251).toByte() }); z.closeEntry()
        }
    }.toByteArray()

    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before
    fun up() {
        UpdateProgress.reset()
        com.diegonmarcos.superapp.appstore.StoreEngine.resetForTest()   // #1517 one engine per process: bind this test's dirs
        ApkCache.clear(ctx)
        // #812 room is real free storage (StatFs); Robolectric's reads 0 — a 40 GB phone.
        ApkCache.freeBytes = { 40_000_000_000L }
        room0 = StoreStages.room
        apk = fakeApk()
        server = TinyHttp { method, path, headers ->
            when {
                path == "/r/Rootfs.apk.sha256" ->
                    Triple(200, emptyMap(), "${hex(apk)}  Rootfs.apk\n".toByteArray())
                path != "/r/Rootfs.apk" -> Triple(404, emptyMap(), ByteArray(0))
                method == "HEAD" -> Triple(200, emptyMap(), apk)   // length only; TinyHttp sends no body
                else -> {
                    assetGets.incrementAndGet()
                    val range = headers["range"]
                    if (range != null) synchronized(rangesSeen) { rangesSeen += range }
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val body = apk.copyOfRange(from, apk.size)
                    servedBytes.addAndGet(body.size.toLong())
                    if (from > 0) Triple(206, mapOf("Content-Range" to "bytes $from-${apk.size - 1}/${apk.size}"), body)
                    else Triple(200, emptyMap(), body)
                }
            }
        }
    }

    @After
    fun down() {
        server.stop()
        StoreStages.installer = FleetInstall::install
        StoreStages.room = room0
        ApkCache.clear(ctx)
        ApkCache.freeBytes = { c -> runCatching { android.os.StatFs(ApkCache.dir(c).path).availableBytes }.getOrElse { ApkCache.dir(c).usableSpace } }
        ApkCache.clearNote(ctx, pkg)
        ApkCache.clearInstallerStatus(ctx, pkg)
        UpdateProgress.removeObserver(watch)
        UpdateProgress.reset()
    }

    private fun app(releaseUrl: String = "http://127.0.0.1:${server.port}/r/Rootfs.apk") = Fleet.App(
        id = "lib-rootfs-test", label = "Rootfs", pkg = pkg, altId = null,
        // GHCR is the fallback source; pointing it at a closed port makes any
        // fall-through fail loudly instead of quietly succeeding elsewhere.
        registry = "127.0.0.1:1", namespace = "x", image = "x", tag = "latest",
        asset = "Rootfs.apk", assets = emptyMap(),
        releaseUrl = releaseUrl,
        repoUrl = "", ghcrPage = "", blocked = false, kind = "lib",
    )

    /** What Android delivers when the user taps Cancel on the install sheet. */
    private fun cancelInstallSheet(path: String) {
        PackageInstallerReceiver().onReceive(ctx, Intent()
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "User rejected permissions")
            .putExtra(PackageInstallerReceiver.EXTRA_APK_PATH, path)
            .putExtra(PackageInstallerReceiver.EXTRA_TARGET_PKG, pkg))
    }

    @Test
    fun `completed download + install cancelled by the user = NO re-download`() {
        val first = Fleet.download(ctx, app())
        assertEquals("the first download fetches the asset once", 1, assetGets.get())

        cancelInstallSheet(first.file.absolutePath)
        assertTrue("a cancelled install must keep the cached APK", first.file.exists())
        assertEquals("the row must learn WHY it is still cached",
            ApkCache.STAGE_INSTALL, ApkCache.noteOf(ctx, pkg)?.stage)

        val again = Fleet.download(ctx, app())
        assertEquals("Install after a cancelled sheet must reuse the cache, not fetch " +
            "${apk.size} bytes again", 1, assetGets.get())
        assertEquals(first.file.canonicalPath, again.file.canonicalPath)
        assertArrayEquals(apk, again.file.readBytes())
        assertEquals("a successful (cached) download clears the stage note", null, ApkCache.noteOf(ctx, pkg))
    }

    @Test
    fun `control - with the cached file gone the same path DOES re-download`() {
        val first = Fleet.download(ctx, app())
        cancelInstallSheet(first.file.absolutePath)
        ApkCache.drop(first.file)   // the cache discarded — exactly the old behaviour's effect
        Fleet.download(ctx, app())
        assertEquals("the counter must see a re-download, or the test above proves nothing",
            2, assetGets.get())
    }

    @Test
    fun `a cached file that no longer hashes to the published sha is NOT reused`() {
        val first = Fleet.download(ctx, app())
        first.file.writeBytes(apk.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        val again = Fleet.download(ctx, app())
        assertEquals("corrupted cache must be re-fetched, never installed", 2, assetGets.get())
        assertArrayEquals(apk, again.file.readBytes())
    }

    @Test
    fun `a download killed half way resumes with Range and fetches only the rest`() {
        val half = apk.size / 2
        // The process died here: a `.part` holding the first half, nothing else.
        ApkCache.file(ctx, "fleet-lib-rootfs-test-release.apk.part").writeBytes(apk.copyOfRange(0, half))
        val done = Fleet.download(ctx, app())
        assertArrayEquals("the resumed file is the artifact, byte for byte", apk, done.file.readBytes())
        assertEquals("only the missing half crosses the network", (apk.size - half).toLong(), servedBytes.get())
        assertEquals(listOf("bytes=$half-"), synchronized(rangesSeen) { rangesSeen.toList() })
    }

    @Test
    fun `control - without the part the whole artifact is served`() {
        Fleet.download(ctx, app())
        assertEquals(apk.size.toLong(), servedBytes.get())
        assertTrue(synchronized(rangesSeen) { rangesSeen.isEmpty() })
    }

    @Test
    fun `stale builds of the same package are pruned when a newer one is cached`() {
        fun cached(name: String, code: Long) = ApkCache.file(ctx, name).apply {
            writeBytes(apk)
            java.io.File(parentFile, "$name.record").writeText("$pkg\n$code\n${hex(apk)}\n")
        }
        val old = cached("fleet-lib-rootfs-test-old.apk", 5L)
        val other = cached("fleet-other.apk", 5L).also {
            java.io.File(it.parentFile, "${it.name}.record").writeText("org.example.other\n5\n${hex(apk)}\n")
        }
        val pruned = ApkCache.pruneStale(ctx, pkg, 6L)
        assertEquals(listOf(old.name), pruned)
        assertTrue("an older build of the same package is gone", !old.exists())
        assertTrue("another package's cache is untouched", other.exists())
        assertNotNull(ApkCache.record(other))
    }

    // ── #774 the three stages and the auto chain ─────────────────────────

    private val sheets = AtomicInteger()

    /** Stand-in for Android's install sheet behind [StoreStages.installer].
     *  Accept installs the handed-over bytes as the package (so the device
     *  really runs them — [ApkCache.landed] hashes sourceDir); Cancel delivers
     *  STATUS_FAILURE_ABORTED to the REAL receiver, exactly as the platform does. */
    private fun sheet(accept: Boolean, code: Long = 7L) {
        StoreStages.installer = { c, _, v ->
            sheets.incrementAndGet()
            if (accept) {
                // Named by content: ApkCache memoizes the installed digest by path +
                // mtime + size, and on a phone every install gets a fresh path.
                val bytes = v.file.readBytes()
                val src = java.io.File(c.filesDir, "installed-$pkg-${hex(bytes).take(12)}.apk").apply { writeBytes(bytes) }
                shadowOf(c.packageManager).installPackage(PackageInfo().apply {
                    packageName = pkg; versionName = "$code"; longVersionCode = code
                    lastUpdateTime = sheets.get().toLong()
                    applicationInfo = ApplicationInfo().apply { packageName = pkg; sourceDir = src.absolutePath }
                })
            } else cancelInstallSheet(v.file.absolutePath)
            null
        }
    }

    @Test
    fun `Install with nothing cached stops at Download when the download fails, and never opens the installer`() {
        sheet(accept = true)
        // No release asset, GHCR on a closed port: every source fails, fast.
        val s = StoreStages.install(ctx, app(releaseUrl = ""))
        assertEquals("download", s.failedAt)
        assertEquals("the row offers Install (the chain, retrying the download) and Download",
            listOf("install", "download"), s.actions)
        assertEquals("Install must not run without a cached APK", 0, sheets.get())
        assertEquals(ApkCache.STAGE_DOWNLOAD, ApkCache.noteOf(ctx, pkg)?.stage)
    }

    @Test
    fun `auto chain stops at Install when the sheet is cancelled, cache kept, Install takes over without a download`() {
        sheet(accept = false)
        val stopped = StoreStages.install(ctx, app())
        assertEquals(1, assetGets.get())
        assertEquals("install", stopped.failedAt)
        assertEquals(listOf("install", "download", "clear"), stopped.actions)
        val cached = stopped.cached
        assertNotNull("the stage names the cached APK it stopped with", cached)
        assertTrue(cached!!.file.exists())
        assertTrue("the reason is the installer's own: ${stopped.text}", stopped.text.contains("User rejected"))

        // A fresh read (what the row shows after an app restart) says the same.
        val reread = StoreStages.stage(ctx, app())
        assertEquals("error", reread.id); assertEquals("install", reread.failedAt)

        // The user takes over with Install: from the cache, no network fetch,
        // and #784 the chain Clears once the install is proven.
        sheet(accept = true)
        val installed = StoreStages.install(ctx, app())
        assertEquals("Install after a cancel reuses the cache", 1, assetGets.get())
        assertEquals("installed", installed.id)
        assertEquals("nothing left to Clear: the chain cleared it", emptyList<String>(), installed.actions)
        assertTrue("the proven install auto-cleared the cached APK", !cached.file.exists())
        assertEquals(null, installed.cached)
    }

    @Test
    fun `Install runs all three stages when nothing fails`() {
        sheet(accept = true)
        val s = StoreStages.install(ctx, app())
        assertEquals(1, sheets.get())
        assertEquals("installed", s.id)
        assertEquals("the chain clears after a proven install", null, StoreStages.cachedFor(ctx, app()))
        assertEquals(null, s.failedAt)
    }

    @Test
    fun `control - a cancelled sheet does NOT read as installed`() {
        sheet(accept = false)
        StoreStages.install(ctx, app())
        assertTrue("landed must be false when nothing was installed",
            StoreStages.cachedFor(ctx, app())!!.let { !ApkCache.landed(ctx, it, pkg) })
    }

    @Test
    fun `a killed download shows as resumable and Download fetches only the rest`() {
        val half = apk.size / 2
        ApkCache.file(ctx, "fleet-lib-rootfs-test-dl.apk.part").writeBytes(apk.copyOfRange(0, half))
        val before = StoreStages.stage(ctx, app())
        assertEquals(listOf("install", "download"), before.actions)
        assertTrue("the row says the bytes are kept: ${before.text}", before.text.contains("kept"))
        val after = StoreStages.download(ctx, app())
        assertEquals((apk.size - half).toLong(), servedBytes.get())
        assertEquals("cached", after.id)
        assertEquals(listOf("install", "download", "clear"), after.actions)
    }

    @Test
    fun `the unattended pass holds an app at Install after a cancelled sheet, and only then`() {
        assertEquals("control: nothing noted, nothing held", null, Fleet.heldAtInstall(ctx, app()))
        val first = Fleet.download(ctx, app())
        cancelInstallSheet(first.file.absolutePath)
        assertEquals("held at install after the user cancelled", ApkCache.STAGE_INSTALL,
            Fleet.heldAtInstall(ctx, app())?.stage)
        // The user takes over: a successful Install clears the hold.
        sheet(accept = true)
        StoreStages.install(ctx, app())
        assertEquals("a finished install releases the hold", null, Fleet.heldAtInstall(ctx, app()))
    }

    // ── #780 a cached APK never hides an update ──────────────────────────
    //
    // The phone's report: apps with an update showed no usable Download — the
    // row offered only Clear. stage() returned "installed · Clear" for any
    // landed cache BEFORE reading the remote, so the last update's leftover APK
    // hid the next one. Each case below fails on that code; each pair is the
    // other's control (same setup, one fact flipped, the verdict must flip).

    /** A distinct real zip per build: Install re-verifies it as an APK. */
    private fun bytesOf(tag: String): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("$pkg $tag".toByteArray()); z.closeEntry() }
    }.toByteArray()

    private fun installedAt(code: Long, bytes: ByteArray) {
        val src = java.io.File(ctx.filesDir, "installed-$pkg-$code-${hex(bytes).take(12)}.apk").apply { writeBytes(bytes) }
        shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg; versionName = "$code"; longVersionCode = code
            applicationInfo = ApplicationInfo().apply { packageName = pkg; sourceDir = src.absolutePath }
        })
    }

    /** A cached build WITH its download record, as Fleet.download leaves a real APK. */
    private fun cachedAt(code: Long, bytes: ByteArray) =
        ApkCache.file(ctx, "fleet-lib-rootfs-test-v$code-${hex(bytes).take(6)}.apk").apply {
            writeBytes(bytes)
            java.io.File(parentFile, "$name.record").writeText("$pkg\n$code\n${hex(bytes)}\n")
        }

    private fun remoteIs(bytes: ByteArray) =
        Fleet.State.UpdateAvailable("2", hex(bytes).take(12), bytes.size.toLong(), source = "release")

    @Test
    fun `780 installed v1, cached v1 landed (same bytes), remote v2 = update_available + Download, cache auto-cleared`() {
        val v1 = bytesOf("v1"); installedAt(1, v1)
        val f = cachedAt(1, v1)
        val s = StoreStages.stage(ctx, app(), remoteIs(bytesOf("v2")))
        assertEquals("update_available", s.id)
        assertEquals("Install + Download, and nothing to Clear: the landed cache cleared itself",
            listOf("install", "download"), s.actions)
        assertFalse("a cache byte-identical to the installed APK is reaped without a tap", f.exists())
    }

    @Test
    fun `780 installed v1, cached v1 landed (other bytes), remote v2 = update_available, Clear only as an extra`() {
        installedAt(1, bytesOf("v1"))
        val f = cachedAt(1, bytesOf("v1-rebuild"))
        val s = StoreStages.stage(ctx, app(), remoteIs(bytesOf("v2")))
        assertEquals("update_available", s.id)
        assertEquals(listOf("install", "download", "clear"), s.actions)
        assertTrue("unproven bytes are never auto-deleted", f.exists())
    }

    @Test
    fun `780 installed v1, cached v2 = cached + Install first, Download still live`() {
        installedAt(1, bytesOf("v1"))
        val v2 = bytesOf("v2")
        val f = cachedAt(2, v2)
        for (remote in listOf(remoteIs(v2), null)) {
            val s = StoreStages.stage(ctx, app(), remote)
            assertEquals("cached", s.id)
            assertEquals(listOf("install", "download", "clear"), s.actions)
            assertEquals(f.canonicalPath, s.cached!!.file.canonicalPath)
        }
    }

    @Test
    fun `780 control - cached v2 superseded by remote v3 = update_available, the stale cache is not offered`() {
        installedAt(1, bytesOf("v1"))
        val f = cachedAt(2, bytesOf("v2"))
        ApkCache.note(ctx, pkg, ApkCache.STAGE_INSTALL, "User rejected permissions")
        val s = StoreStages.stage(ctx, app(), remoteIs(bytesOf("v3")))
        assertEquals("update_available", s.id)
        assertEquals(listOf("install", "download", "clear"), s.actions)
        assertEquals(null, s.cached)
        assertTrue(f.exists())
        assertEquals("a note about a superseded build must not hold the unattended pass",
            null, Fleet.heldAtInstall(ctx, app()))
    }

    @Test
    fun `780 installed v2, cached v2 landed, remote v2 = installed + Clear (other bytes) or nothing (same bytes)`() {
        val v2 = bytesOf("v2"); installedAt(2, v2)
        val other = cachedAt(2, bytesOf("v2-rebuild"))
        val up = Fleet.State.Installed("2", 2L, hex(v2).take(12), v2.size.toLong())
        val s = StoreStages.stage(ctx, app(), up)
        assertEquals("installed", s.id); assertEquals(listOf("clear"), s.actions)
        ApkCache.drop(other)
        val same = cachedAt(2, v2)
        val t = StoreStages.stage(ctx, app(), up)
        assertEquals("installed", t.id); assertEquals(emptyList<String>(), t.actions)
        assertFalse(same.exists())
    }

    @Test
    fun `780 Install chain downloads past a landed cache instead of reinstalling it`() {
        sheet(accept = true)
        installedAt(1, bytesOf("v1"))
        cachedAt(1, bytesOf("v1-rebuild"))
        StoreStages.install(ctx, app())
        assertEquals("a landed cache must not stand in for the download", 1, assetGets.get())
    }

    @Test
    fun `780 control - Install chain installs an actionable cache without the network`() {
        sheet(accept = false)
        installedAt(1, bytesOf("v1"))
        cachedAt(2, bytesOf("v2"))
        StoreStages.install(ctx, app())
        assertEquals(0, assetGets.get())
        assertEquals(1, sheets.get())
    }

    // ── #784 Install is the whole chain; Download all; Update all offline ──
    //
    // Each case has a control beside it that flips one fact and must flip the
    // verdict, so a counter or flag that cannot see the behaviour cannot pass.

    /** A second lib, so a batch has something to skip or leave uncached. */
    private fun other() = app().copy(id = "lib-other-test", pkg = "org.example.other", releaseUrl = "")

    /** What [ApkCache.keep] writes on a device for a real APK. Robolectric cannot
     *  parse the test zip's manifest, so the record is written as keep would. */
    private fun recordAsKeepWould(code: Long) {
        val f = StoreStages.cachedFor(ctx, app())!!.file
        java.io.File(f.parentFile, "${f.name}.record").writeText("$pkg\n$code\n${hex(f.readBytes())}\n")
    }

    @Test
    fun `784 Install is live with nothing cached, and one tap runs Download, Install, Clear`() {
        val missing = Fleet.State.Missing(apk.size.toLong())
        val row = StoreStages.stage(ctx, app(), missing)
        assertEquals("not_installed", row.id)
        assertEquals("Install is never disabled for want of a cache", listOf("install", "download"), row.actions)
        assertEquals(null, row.cached)
        sheet(accept = true)
        val s = StoreStages.install(ctx, app(), missing)
        assertEquals("the chain downloaded exactly once", 1, assetGets.get())
        assertEquals("then opened the installer exactly once", 1, sheets.get())
        assertEquals("installed", s.id)
        assertEquals("then cleared the proven install's APK", null, StoreStages.cachedFor(ctx, app()))
        // Control: an up-to-date row has no Install to offer.
        val current = StoreStages.stage(ctx, app(), Fleet.State.Installed("7", 7L, hex(apk).take(12)))
        assertFalse("install" in current.actions)
    }

    @Test
    fun `784 the chain stops at Download keeping the part, and the next Install resumes it and finishes`() {
        sheet(accept = true)
        val half = apk.size / 2
        ApkCache.file(ctx, "fleet-lib-rootfs-test-dl.apk.part").writeBytes(apk.copyOfRange(0, half))
        // The link is down (no source can serve it): stopped at Download.
        val stopped = StoreStages.install(ctx, app(releaseUrl = ""))
        assertEquals("download", stopped.failedAt)
        assertEquals(listOf("install", "download"), stopped.actions)
        assertEquals("nothing reached the installer", 0, sheets.get())
        assertEquals("the half already fetched is kept", half.toLong(), StoreStages.partialBytes(ctx, app()))
        // The link is back: the SAME Install continues from the part.
        val done = StoreStages.install(ctx, app())
        assertEquals("only the missing half crosses the network", (apk.size - half).toLong(), servedBytes.get())
        assertEquals(1, sheets.get())
        assertEquals("installed", done.id)
        assertEquals(null, StoreStages.cachedFor(ctx, app()))
    }

    @Test
    fun `784 Download all then Update all with the network OFF installs from the cache`() {
        sheet(accept = true)
        val fleet = listOf(app(), other())
        val plan = StoreStages.downloadAll(ctx, fleet, online = true, dryRun = true)
        assertEquals(StoreStages.DOWNLOAD, plan.outcomes.first { it.app.id == app().id }.result)
        assertEquals("a dry run downloads nothing", 0, assetGets.get())
        assertEquals(apk.size.toLong(), plan.needBytes)

        val got = StoreStages.downloadAll(ctx, fleet, online = true)
        assertEquals(StoreStages.DOWNLOADED, got.outcomes.first { it.app.id == app().id }.result)
        assertEquals(1, assetGets.get())
        assertEquals("Download all installs nothing", 0, sheets.get())
        assertNotNull(StoreStages.cachedFor(ctx, app()))
        recordAsKeepWould(2L)

        server.stop()   // the network is gone
        val dry = StoreStages.updateAll(ctx, fleet, online = false, dryRun = true)
        assertEquals(StoreStages.INSTALL, dry.outcomes.first { it.app.id == app().id }.result)
        assertEquals("a dry run installs nothing", 0, sheets.get())

        val up = StoreStages.updateAll(ctx, fleet, online = false)
        val mine = up.outcomes.first { it.app.id == app().id }
        assertEquals(mine.text, StoreStages.INSTALLED, mine.result)
        assertEquals(1, sheets.get())
        assertEquals("no network was needed", 1, assetGets.get())
        assertEquals("the proven install cleared its cache", null, StoreStages.cachedFor(ctx, app()))
        // Control: the lib with nothing cached cannot be installed offline, and says so.
        assertEquals(StoreStages.NEEDS_DOWNLOAD, up.outcomes.first { it.app.id == other().id }.result)
    }

    @Test
    fun `784 control - Update all offline with nothing cached installs nothing`() {
        sheet(accept = true)
        val up = StoreStages.updateAll(ctx, listOf(app()), online = false)
        assertEquals(StoreStages.NEEDS_DOWNLOAD, up.outcomes.single().result)
        assertEquals(0, sheets.get())
        assertEquals(0, assetGets.get())
    }

    @Test
    fun `784 Update all stops each app at the failing stage and reports it`() {
        sheet(accept = false)
        installedAt(1, bytesOf("v1"))
        cachedAt(2, bytesOf("v2"))
        val up = StoreStages.updateAll(ctx, listOf(app()), online = false)
        val o = up.outcomes.single()
        assertEquals(StoreStages.FAILED, o.result)
        assertEquals("install", o.failedAt)
        assertTrue("the cache is kept for the retry", StoreStages.cachedFor(ctx, app()) != null)
        // The retry takes over from Install: no download.
        sheet(accept = true)
        assertEquals(StoreStages.INSTALLED, StoreStages.updateAll(ctx, listOf(app()), online = false).outcomes.single().result)
        assertEquals(0, assetGets.get())
    }

    @Test
    fun `784 Download all never starts what does not fit, so it cannot evict what it fetched`() {
        StoreStages.room = { 10L }
        val plan = StoreStages.downloadAll(ctx, listOf(app()), online = true, dryRun = true)
        assertTrue("the dry run warns before filling the disk", plan.needBytes > plan.roomBytes)
        val got = StoreStages.downloadAll(ctx, listOf(app()), online = true)
        assertEquals(StoreStages.NO_ROOM, got.outcomes.single().result)
        assertEquals(0, assetGets.get())
        assertEquals(null, StoreStages.cachedFor(ctx, app()))
        // Control: with room, the same call downloads it.
        StoreStages.room = room0
        assertEquals(StoreStages.DOWNLOADED, StoreStages.downloadAll(ctx, listOf(app()), online = true).outcomes.single().result)
    }

    @Test
    fun `784 a cache byte-identical to the installed APK is cleared by both batch verbs, a different one is kept`() {
        val v1 = bytesOf("v1"); installedAt(1, v1)
        for (op in listOf("downloadAll", "updateAll")) {
            val same = cachedAt(1, v1)
            if (op == "downloadAll") StoreStages.downloadAll(ctx, listOf(app()), online = false)
            else StoreStages.updateAll(ctx, listOf(app()), online = false)
            assertFalse("$op must reap a cache that IS the installed APK", same.exists())
        }
        // Control: same versionCode, other bytes — unproven, so kept.
        val other = cachedAt(1, bytesOf("v1-rebuild"))
        StoreStages.updateAll(ctx, listOf(app()), online = false)
        assertTrue(other.exists())
    }

    // ── #789 a constant versionCode never stands in for identity ─────────
    //
    // The phone's report: the forks (camera 93, vault 1, termux 1011, the
    // rootfs lib) keep ONE versionCode across builds. A freshly cached build at
    // the installed code read as "landed" (installed code >= cached), so it was
    // never actionable, never installed, and Update all said "would download …
    // and install" for 24 rounds. The fixture: installed at 93 from OLD bytes,
    // the published build at 93 from NEW bytes. Each case carries the control
    // that flips one fact; [oldRule] is the versionCode-only rule the fixture
    // must be able to tell apart from the sha rule, or it proves nothing.

    private val fork = 93L

    /** The rule #789 removed, kept here only to prove the fixture discriminates. */
    private fun oldRule(e: ApkCache.Entry): Boolean =
        (ctx.packageManager.getPackageInfo(pkg, 0).longVersionCode) >= e.record!!.versionCode

    private fun installedSha() = ApkCache.installedSha256(ctx, pkg)

    @Test
    fun `789 same versionCode, new bytes cached = NOT landed, and the row offers Install from the cache`() {
        installedAt(fork, bytesOf("old"))
        val nu = bytesOf("new")
        val f = cachedAt(fork, nu)
        val e = ApkCache.entry(f)
        assertTrue("fixture control: the versionCode-only rule calls it landed", oldRule(e))
        assertFalse("the sha rule does not: the device runs other bytes", ApkCache.landed(ctx, e, pkg))
        val s = StoreStages.stage(ctx, app(), remoteIs(nu))
        assertEquals(s.text, "cached", s.id)
        assertEquals(listOf("install", "download", "clear"), s.actions)
        assertTrue("never auto-cleared: it is the update", f.exists())
        // Control: the SAME bytes at the same code ARE landed, and reap.
        installedAt(fork, nu)
        assertTrue(ApkCache.landed(ctx, ApkCache.entry(f), pkg))
        StoreStages.stage(ctx, app(), Fleet.State.Installed("$fork", fork, hex(nu).take(12)))
        assertFalse("the build the device runs clears itself", f.exists())
    }

    @Test
    fun `789 Install with the remote known installs the same-versionCode build from the cache, then clears it`() {
        sheet(accept = true, code = fork)
        installedAt(fork, bytesOf("old"))
        val nu = bytesOf("new")
        val f = cachedAt(fork, nu)
        val s = StoreStages.install(ctx, app(), remoteIs(nu))
        assertEquals("the installer was opened once", 1, sheets.get())
        assertEquals("from the cache: no network", 0, assetGets.get())
        assertEquals("the device now runs the published bytes", hex(nu), installedSha())
        assertEquals(s.text, "installed", s.id)
        assertFalse("proven by sha, so cleared", f.exists())
    }

    @Test
    fun `789 Install with NO remote (debug API, new-phone migration) asks the download, which is a cache hit`() {
        sheet(accept = true, code = fork)
        installedAt(fork, bytesOf("old"))
        // What the phone had: the published build cached at the installed code.
        val f = cachedAt(fork, apk)
        val row = StoreStages.stage(ctx, app())
        assertFalse("without the remote a same-code cache cannot be ordered: ${row.text}", row.id == "cached")
        StoreStages.install(ctx, app())
        assertEquals("the sidecar names the cached bytes: a cache hit, no GET", 0, assetGets.get())
        assertEquals(1, sheets.get())
        assertEquals("installed the published build", hex(apk), installedSha())
        assertFalse(f.exists())
        // Control: the device already runs the published build — nothing to do.
        StoreStages.install(ctx, app())
        assertEquals("a landed build is never reinstalled", 1, sheets.get())
    }

    @Test
    fun `789 Update all online moves a constant-versionCode app, and the next plan says already current`() {
        sheet(accept = true, code = fork)
        installedAt(fork, bytesOf("old"))
        cachedAt(fork, apk)
        val dry = StoreStages.updateAll(ctx, listOf(app()), online = true, dryRun = true).outcomes.single()
        assertEquals(dry.text, StoreStages.INSTALL, dry.result)
        assertTrue("from the cache, not a download: ${dry.text}", dry.text.contains("from the cache"))
        val up = StoreStages.updateAll(ctx, listOf(app()), online = true).outcomes.single()
        assertEquals(up.text, StoreStages.INSTALLED, up.result)
        assertEquals(0, assetGets.get())
        // The 24-round loop ends: the remote now matches the device.
        val again = StoreStages.updateAll(ctx, listOf(app()), online = true, dryRun = true).outcomes.single()
        assertEquals(again.text, StoreStages.SKIPPED, again.result)
        assertEquals(1, sheets.get())
    }

    @Test
    fun `789 a fresh download prunes the same-versionCode build it replaces, never another package or code`() {
        val old = cachedAt(fork, bytesOf("old"))
        val nu = cachedAt(fork, bytesOf("new"))
        assertEquals("control: the receiver's rule keeps a same-code build", emptyList<String>(),
            ApkCache.pruneStale(ctx, pkg, fork, except = nu))
        assertEquals(listOf(old.name), ApkCache.pruneStale(ctx, pkg, fork, except = nu, sameCodeToo = true))
        assertTrue(nu.exists())
    }

    // ── #785 the Store bar says WHICH app, WHICH stage, and where in the batch ──
    //
    // The bar under the Store buttons drew "Downloading 42%" with no app, no
    // stage, no position, and lost a failure the moment the next app started.
    // Every line below is StoreStages.progress — what the bar draws and what
    // /api/store/progress returns — recorded on the thread that published it,
    // exactly as the fragment's observer reads it.

    private val lines = java.util.Collections.synchronizedList(ArrayList<String>())
    private val watch: (UpdateProgress.State) -> Unit = { st -> StoreStages.progress(st)?.let { lines += it.text } }
    private fun watching() { UpdateProgress.addObserver(watch); lines.clear() }

    /** A second lib served by the same release, so a batch has a "next". */
    private fun two() = app().copy(id = "lib-two-test", label = "Two", pkg = "org.example.two")

    /** Each app's own row, recorded on the thread that published it (the board is keyed by package). */
    private val rowLog = java.util.Collections.synchronizedList(ArrayList<com.diegonmarcos.superapp.appstore.JobBoard.Row>())
    private val rowWatch: () -> Unit = {
        com.diegonmarcos.superapp.appstore.StoreJobs.board.rows().forEach { rowLog += it }
    }
    private fun watchingRows() {
        com.diegonmarcos.superapp.appstore.StoreJobs.board.reset(); rowLog.clear(); com.diegonmarcos.superapp.appstore.StoreJobs.addObserver(rowWatch)
    }

    @Test
    fun `785 Download all gives each app its own row with its own stage, bytes and percent`() {
        watchingRows()
        val got = StoreStages.downloadAll(ctx, listOf(app(), two()), online = true)
        com.diegonmarcos.superapp.appstore.StoreJobs.removeObserver(rowWatch)
        assertEquals(got.summary, 2, got.count(StoreStages.DOWNLOADED))
        val seen = rowLog.toList()
        for (pkg in listOf(app().pkg, two().pkg)) {
            val mine = seen.filter { it.key == pkg }
            assertTrue("no downloading row with bytes for $pkg: $seen", mine.any {
                it.phase == com.diegonmarcos.superapp.appstore.JobBoard.Phase.DOWNLOADING && it.bytes > 0 && it.percent >= 0 })
            // Monotonic per row, whatever the other app is doing at the same time.
            val pcts = mine.filter { it.percent >= 0 }.map { it.percent }
            assertEquals("a row's percent went backwards: $pcts", pcts.sorted(), pcts)
        }
        assertEquals("a clean batch leaves nothing on the bar", null, StoreStages.progress())
    }

    @Test
    fun `785 Update all walks each app through verifying and installing, and ends on its failure`() {
        sheet(accept = false)
        installedAt(1, bytesOf("v1")); cachedAt(2, bytesOf("v2"))
        val b2 = bytesOf("two-v2")
        ApkCache.file(ctx, "fleet-lib-two-test-v2-${hex(b2).take(6)}.apk").apply {
            writeBytes(b2); java.io.File(parentFile, "$name.record").writeText("org.example.two\n2\n${hex(b2)}\n")
        }
        watchingRows()
        val up = StoreStages.updateAll(ctx, listOf(app(), two()), online = false)
        com.diegonmarcos.superapp.appstore.StoreJobs.removeObserver(rowWatch)
        assertEquals(up.summary, StoreStages.FAILED, up.outcomes.first { it.app.id == app().id }.result)
        val mine = rowLog.filter { it.key == app().pkg }.map { it.phase }
        for (ph in listOf(com.diegonmarcos.superapp.appstore.JobBoard.Phase.VERIFYING, com.diegonmarcos.superapp.appstore.JobBoard.Phase.INSTALLING))
            assertTrue("Rootfs never went through $ph: $mine", ph in mine)
        // The batch is over, its job is gone - and the bar still says what failed, where, and why.
        assertEquals(null, UpdateProgress.job)
        val p = StoreStages.progress()!!
        assertTrue(p.failed)
        assertEquals(app().id, p.appId)
        assertEquals("Rootfs", p.app)
        assertEquals("installing", p.stage)
        assertTrue(p.text, p.text.startsWith("✗ Rootfs") && "failed at installing" in p.text && "User rejected" in p.text)
        // Control: the same batch with nothing failing ends with an empty bar.
        sheet(accept = true)
        StoreStages.updateAll(ctx, listOf(app()), online = false)
        assertEquals(null, StoreStages.progress())
    }

    @Test
    fun `785 a lone Install that stops names the stage it stopped at, and the API answers the same line`() {
        sheet(accept = true)
        // Nothing cached and no source: it stops while DOWNLOADING.
        StoreStages.install(ctx, app(releaseUrl = ""))
        val dl = StoreStages.progress()!!
        assertEquals(dl.text, "downloading", dl.stage)
        assertTrue(dl.failed); assertEquals("Rootfs", dl.app)
        // Control: a cancelled sheet stops it while INSTALLING — the stage is read, not assumed.
        UpdateProgress.reset()
        sheet(accept = false)
        StoreStages.install(ctx, app())
        assertEquals(null, UpdateProgress.job)
        val p = StoreStages.progress()!!
        assertEquals(p.text, "installing", p.stage)
        val api = StoreDebugApi.progress()
        assertEquals(p.text, api.getString("text"))
        assertEquals(app().id, api.getString("id"))
        assertEquals("installing", api.getString("stage"))
        assertTrue(api.getBoolean("failed"))
        assertTrue(api.getString("error"), api.getString("error").contains("User rejected"))
        // Control: a clean Install leaves the bar (and the API) empty.
        sheet(accept = true)
        StoreStages.install(ctx, app())
        assertFalse(StoreDebugApi.progress().getBoolean("active"))
    }

    // ── #906 a handover that goes quiet says why, never "cached" ─────────

    @Test
    fun `906 - an installer that hands over and never answers reads as waiting, then as a Retry that names the missing status`() {
        StoreStages.installer = { _, _, _ -> null }   // committed; the platform says nothing
        val waiting = StoreStages.install(ctx, app())
        assertEquals("a handover still open is installing, not cached", "installing", waiting.id)
        assertTrue("it says what it waits on: ${waiting.text}", waiting.text.contains("waiting on Android's installer"))
        assertEquals("the cache is kept for the retry", true, waiting.cached?.file?.exists())

        // The session is gone and nothing arrived: the row becomes a Retry that names the silence.
        com.diegonmarcos.superapp.appstore.StoreInstallWatch.sweep(ctx, app(),
            System.currentTimeMillis() + com.diegonmarcos.superapp.appstore.StoreInstallWatch.SETTLE_MS + 1_000)
        val after = StoreStages.stage(ctx, app())
        assertEquals("error", after.id); assertEquals("install", after.failedAt)
        assertTrue("no status delivered is stated: ${after.text}", after.text.contains("no status was delivered"))
    }

    @Test
    fun `906 - the last PackageInstaller status is quoted while waiting and on the Retry`() {
        StoreStages.installer = { c, _, v ->
            PackageInstallerReceiver().onReceive(c, Intent()
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "")
                .putExtra(Intent.EXTRA_INTENT, Intent("android.intent.action.VIEW"))
                .putExtra(PackageInstallerReceiver.EXTRA_APK_PATH, v.file.absolutePath)
                .putExtra(PackageInstallerReceiver.EXTRA_TARGET_PKG, pkg))
            null
        }
        val waiting = StoreStages.install(ctx, app())
        assertEquals("installing", waiting.id)
        assertTrue("the pending prompt is named: ${waiting.text}", waiting.text.contains("PENDING_USER_ACTION"))
        com.diegonmarcos.superapp.appstore.StoreInstallWatch.sweep(ctx, app(),
            System.currentTimeMillis() + 400_000)
        val after = StoreStages.stage(ctx, app())
        assertEquals("error", after.id)
        assertTrue("the Retry quotes the last status: ${after.text}", after.text.contains("PENDING_USER_ACTION"))
    }

    @Test
    fun `906 - control - a landed install clears the watch, so the row never reads waiting`() {
        sheet(accept = true)
        val s = StoreStages.install(ctx, app())
        assertEquals("installed", s.id)
        assertEquals(null, ApkCache.installerStatusOf(ctx, pkg))
    }
}
