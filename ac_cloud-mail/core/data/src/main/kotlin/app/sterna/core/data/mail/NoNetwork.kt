package app.sterna.core.data.mail

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * #1869 the open-path tripwire. A coroutine running under [NoNetwork] (the reader's local snapshot,
 * [LocalReaderStore.snapshot]) must not reach a server: every network entry point of the repository calls [check] first,
 * so a regression that puts a fetch on the open path throws at once, in tests and on the device, instead of quietly
 * making the reader wait.
 */
object NoNetwork : AbstractCoroutineContextElement(NoNetworkKey) {
    /** Throws [NetworkOnOpenPathException] when the calling coroutine runs under [NoNetwork]. */
    suspend fun check(what: String) {
        if (coroutineContext[NoNetworkKey] != null) throw NetworkOnOpenPathException(what)
    }
}

/** The element's key, its OWN object: an object passing itself as its key reads null during its own construction. */
object NoNetworkKey : CoroutineContext.Key<NoNetwork>

class NetworkOnOpenPathException(what: String) :
    IllegalStateException("network call '$what' on the reader's open path (#1869: the open path reads local data only)")
