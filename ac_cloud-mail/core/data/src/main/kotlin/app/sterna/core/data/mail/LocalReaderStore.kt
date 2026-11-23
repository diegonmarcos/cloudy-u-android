package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1869 everything the reader's FIRST frame needs, read from local storage in ONE hop. [Hit] carries a body the page
 * can paint (Room's body cache, or this process's decrypted copy); [Miss] carries only what the list row knew. Neither
 * ever came from the network: [LocalReaderStore.snapshot] runs under [NoNetwork], so a server call reached from here
 * throws instead of waiting (and the read answers a [Miss]).
 *
 * The Rust core's document ([coreDoc]) rides along only when it is ALREADY stored at snapshot time; it is never fetched
 * here and never swapped in after the page is painted.
 */
sealed interface ReaderSnapshot {
    /** The cached list row: sender, subject, folder. Null when the row is gone too. */
    val header: Email?
    val coreDoc: CoreReaderDoc?
    val snoozedUntil: Long?

    data class Hit(
        override val header: Email?,
        val body: MessageBody,
        override val coreDoc: CoreReaderDoc?,
        override val snoozedUntil: Long?,
    ) : ReaderSnapshot

    data class Miss(
        override val header: Email?,
        override val coreDoc: CoreReaderDoc?,
        override val snoozedUntil: Long?,
    ) : ReaderSnapshot
}

/** The local reads a snapshot is made of. Implemented by [MailRepository] over Room, its memory caches and the core
 *  store; every method takes the ACCOUNT ID only, never credentials (no keystore decrypt on the open path). */
interface LocalReaderPort {
    /** This process's decrypted copy of an OpenPGP message (memory only, never persisted). */
    suspend fun decrypted(accountId: String, emailId: String): MessageBody?

    /** The stored body, read state reconciled with the row; ciphertext comes back marked [MessageCrypto.Locked]. */
    suspend fun storedBody(accountId: String, emailId: String): MessageBody?

    suspend fun header(accountId: String, emailId: String): Email?

    suspend fun storedCoreDoc(accountId: String, emailId: String): CoreReaderDoc?

    suspend fun snoozedUntil(accountId: String, emailId: String): Long?
}

class LocalReaderStore(
    private val port: LocalReaderPort,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** One IO hop, every read guarded: a read that fails is a missing piece, never a failed open. */
    suspend fun snapshot(accountId: String, emailId: String): ReaderSnapshot = withContext(io + NoNetwork) {
        val header = guarded { port.header(accountId, emailId) }
        val core = guarded { port.storedCoreDoc(accountId, emailId) }
        val snooze = guarded { port.snoozedUntil(accountId, emailId) }
        val body = guarded { port.decrypted(accountId, emailId) } ?: guarded { port.storedBody(accountId, emailId) }
        if (body != null) ReaderSnapshot.Hit(header, body, core, snooze) else ReaderSnapshot.Miss(header, core, snooze)
    }

    private suspend fun <T> guarded(read: suspend () -> T?): T? = try {
        read()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }
}
