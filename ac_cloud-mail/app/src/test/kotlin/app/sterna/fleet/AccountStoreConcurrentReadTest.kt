package app.sterna.fleet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sterna.core.data.account.AccountStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1952: the application opens the account store on an IO thread at startup while the main thread reads it. Two threads (here eight) read
 * the accounts, the current id and the unreadable flag at once and every answer is the stored one. The legacy single-account record
 * (no accounts blob yet) is migrated ONCE under whatever number of threads, never again under a new id.
 * The constructor already migrates, so no mutation can remove the migration lock under this test; it guards the reads (a shared cache or a
 * lazy field added to accounts() later would show as a wrong answer here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AccountStoreConcurrentReadTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx.getSharedPreferences("sterna_account", Context.MODE_PRIVATE)

    private fun hammer(store: AccountStore): Throwable? {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        repeat(threads) { n ->
            pool.execute {
                try {
                    start.await()
                    repeat(300) {
                        val ids = store.accounts().map { it.id }
                        if (ids != listOf("a1", "a2")) failure.compareAndSet(null, AssertionError("thread $n read $ids"))
                        if (store.currentId() != "a2") failure.compareAndSet(null, AssertionError("thread $n read current ${store.currentId()}"))
                        if (store.accountsUnreadable()) failure.compareAndSet(null, AssertionError("thread $n saw an unreadable list"))
                    }
                } catch (t: Throwable) { failure.compareAndSet(null, t) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("the readers finished", pool.awaitTermination(60, TimeUnit.SECONDS))
        return failure.get()
    }

    @Test fun `eight threads read the accounts, the current id and the unreadable flag at once`() {
        prefs().edit().clear()
            .putString("accounts", """[{"id":"a1","server":"s1","username":"u1"},{"id":"a2","server":"s2","username":"u2"}]""")
            .putString("current", "a2").commit()
        assertNull(hammer(AccountStore(ctx)))
    }

    @Test fun `the legacy account is migrated once however many threads read first`() {
        prefs().edit().clear()
            .putString("server", "s1").putString("username", "u1").putString("password_enc", "enc").commit()
        val store = AccountStore(ctx) // the constructor migrates; later reads must not migrate again
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        repeat(8) { pool.execute { start.await(); repeat(200) { store.accounts().forEach { a -> seen.add(a.id) } } } }
        start.countDown(); pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals("one account, one id, from every thread: $seen", 1, seen.size)
    }
}
