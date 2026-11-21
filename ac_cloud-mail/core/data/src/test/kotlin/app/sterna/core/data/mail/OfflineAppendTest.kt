package app.sterna.core.data.mail

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAppendTest {
    @Test fun `offline with the folder held ends the append quietly`() {
        assertTrue(offlineAppendEnds(false, UnknownHostException("x"), true))
        assertTrue(offlineAppendEnds(false, RuntimeException("wrap", ConnectException("refused")), true))
    }

    @Test fun `an online failure stays an error`() = assertFalse(offlineAppendEnds(true, UnknownHostException("x"), true))

    @Test fun `a folder the phone holds nothing of stays an error`() = assertFalse(offlineAppendEnds(false, UnknownHostException("x"), false))

    @Test fun `a non transport failure stays an error`() = assertFalse(offlineAppendEnds(false, IOException("bad"), true))
}
