package app.sterna.core.data.mail

import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException

/**
 * #1837: the list's fetch-on-scroll (RemoteMediator append) must not turn "the phone is offline" into "Couldn't load more" + Retry.
 * When the device has no network, the transport fails, and the store already holds rows of the folder, the append ends quietly
 * (end of what this phone has saved). A failure while ONLINE, a non-transport failure, or a folder the phone holds nothing of
 * stays an error with Retry.
 */
internal fun offlineAppendEnds(online: Boolean, t: Throwable, folderHeld: Boolean): Boolean =
    !online && folderHeld && isTransportFailure(t)

internal fun isTransportFailure(t: Throwable): Boolean {
    var e: Throwable? = t
    var hops = 0
    while (e != null && hops++ < 8) {
        if (e is UnknownHostException || e is SocketException || e is InterruptedIOException) return true
        e = e.cause
    }
    return false
}
