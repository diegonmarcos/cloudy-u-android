package com.diegonmarcos.cloudlib.mailcore

/**
 * The byte pipe of an already-connected (and, where needed, TLS-wrapped) IMAP socket, called by libmail_core.so on the calling
 * thread (#1837 phase 3). The core speaks the protocol; Kotlin keeps the socket, the certificates and the routing.
 */
interface MailCoreImapIo {
    /** Writes and flushes [bytes]. A failure is thrown (the core turns it into an error answer). */
    fun write(bytes: ByteArray)

    /** The bytes that arrived, waiting up to [timeoutMs]: an EMPTY array when none did, null when the server closed the connection. */
    fun read(timeoutMs: Int): ByteArray?
}
