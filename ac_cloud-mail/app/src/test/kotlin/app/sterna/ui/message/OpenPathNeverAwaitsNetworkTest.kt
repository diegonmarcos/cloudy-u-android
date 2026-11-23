package app.sterna.ui.message

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1869 opening a message never waits on the network. The reader used to wait up to 2 s for the core's raw-message
 * fetch after the body was already on disk; now a fetch in flight is read with [completedOrNull], which answers at once.
 */
class OpenPathNeverAwaitsNetworkTest {

    @Test fun `a fetch still in flight answers null at once, without any virtual time passing`() = runTest {
        val network = async { awaitCancellation() }
        assertNull(completedOrNull(network))
        assertEquals(0L, testScheduler.currentTime)
        network.cancel()
    }

    @Test fun `a fetch that already landed is used, a failed or cancelled one is not`() = runTest {
        assertEquals("doc", completedOrNull(CompletableDeferred("doc")))
        assertNull(completedOrNull(CompletableDeferred<String>().apply { completeExceptionally(IllegalStateException("offline")) }))
        assertNull(completedOrNull(CompletableDeferred<String>().apply { cancel() }))
        assertNull(completedOrNull(CompletableDeferred<String>()))
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "build.json").isFile && File(it, "app").isDirectory }

    private fun src(relative: String) = File(root, "app/src/main/kotlin/app/sterna/$relative").readText()

    @Test fun `the reader's load never awaits the core fetch, nor any timeout on it`() {
        val vm = src("ui/message/MessageViewModel.kt")
        assertTrue("the core fetch is read without waiting", "coreFetch?.let { completedOrNull(it) }?.let { _coreDoc.value = it }" in vm)
        assertFalse("no await on the core fetch", Regex("""coreFetch\??\.(let \{ job -> [^}]*)?await""").containsMatchIn(vm) || "job.await()" in vm)
        assertFalse("no bounded wait either", "withTimeoutOrNull" in vm || "CORE_FETCH_WAIT_MS" in vm)
        assertFalse("completedOrNull stays non-suspending", "suspend fun <T> completedOrNull" in src("ui/message/OpenPath.kt"))
    }

    @Test fun `the open paints local data before anything leaves the device`() {
        val vm = src("ui/message/MessageViewModel.kt")
        val load = vm.substringAfter("fun load(emailId: String, accountId: String?, settled: Boolean) {")
        val stored = load.indexOf("repo.coreReaderStored(")
        val header = load.indexOf("repo.cachedEmail(")
        val open = load.indexOf("repo.openMessage(")
        assertTrue("the core store is read first", stored in 0 until open)
        assertTrue("the cached header row is painted before the body read", header in 0 until open)
        assertTrue("the fetch of a missing core document is started in the background", "async { runCatching { repo.coreReaderFetched(c, emailId) }.getOrNull() }" in load)
        val warm = vm.substringAfter("fun warmFromCache(emailId: String, accountId: String?) {").substringBefore("private fun loadInlineImagesLater(")
        assertTrue("the pager warms pages from the cache", "repo.cachedMessage(credentials.id, emailId)" in warm)
        assertFalse("and from nothing else", "repo.openMessage(" in warm || "coreReaderFetched(" in warm)
    }
}
