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
        val load = vm.substringAfter("fun load(emailId: String, accountId: String?, settled: Boolean) {").substringBefore("private suspend fun scanBodyForUnsubscribe(")
        assertFalse("no core fetch is started on the open path", "coreReaderFetched(" in load)
        assertTrue("a missing core document is queued for the background instead", "if (storedDoc == null) repo.queueCoreFetch(credentials, emailId)" in load)
        assertFalse("no await, no bounded wait", ".await()" in load || "withTimeoutOrNull" in vm || "CORE_FETCH_WAIT_MS" in vm)
        assertFalse("completedOrNull stays non-suspending", "suspend fun <T> completedOrNull" in src("ui/message/OpenPath.kt"))
    }

    @Test fun `the first frame comes from ONE local snapshot, by account id, before any credentials`() {
        val vm = src("ui/message/MessageViewModel.kt")
        val load = vm.substringAfter("fun load(emailId: String, accountId: String?, settled: Boolean) {").substringBefore("private suspend fun scanBodyForUnsubscribe(")
        val launch = load.substringAfter("viewModelScope.launch {")
        val snapshot = launch.indexOf("repo.readerSnapshot(id, emailId)")
        val firstCredentials = launch.indexOf("credentials()")
        val open = launch.indexOf("repo.openMessage(")
        assertTrue("the snapshot is the first read", snapshot >= 0 && snapshot < firstCredentials && snapshot < open)
        assertTrue("its body is used before any fetch", "val opened = localBody\n                    ?: repo.openMessage(" in launch)
        assertTrue("the local account is an id, not credentials", "val localAccount = accountId ?: store.currentId()" in launch)
        val beforeTry = launch.substringBefore("try {")
        for (call in listOf("credentials()", "openMessage(", "coreReaderFetched(", "mailboxMembership(", "prefetchNeighbours(")) {
            assertFalse("nothing before the paint names $call", call in beforeTry)
        }
        val warm = vm.substringAfter("fun warmFromCache(emailId: String, accountId: String?) {").substringBefore("private fun loadInlineImagesLater(")
        assertTrue("the pager warms pages from the cache, by account id", "repo.cachedMessage(localAccount, emailId)" in warm)
        assertFalse("and from nothing else", "repo.openMessage(" in warm || "coreReaderFetched(" in warm || "credentials()" in warm)
    }
}
