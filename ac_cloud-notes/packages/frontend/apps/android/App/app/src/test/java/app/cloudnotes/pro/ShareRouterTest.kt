package app.cloudnotes.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1888 the share sheet: how a SEND / SEND_MULTIPLE is routed. */
class ShareRouterTest {
    private val send = ShareRouter.SEND
    private val multi = ShareRouter.SEND_MULTIPLE

    @Test fun actionsAreTheAndroidOnes() {
        assertEquals("android.intent.action.SEND", send)
        assertEquals("android.intent.action.SEND_MULTIPLE", multi)
    }

    @Test fun plainTextWithoutAStreamBecomesANewNote() {
        assertEquals(ShareRoute.NOTE_FROM_TEXT, ShareRouter.route(send, 0, "hello"))
    }

    @Test fun streamsWinOverTheCaption() {
        assertEquals(ShareRoute.STREAMS, ShareRouter.route(send, 1, "caption"))
        assertEquals(ShareRoute.STREAMS, ShareRouter.route(multi, 3, null))
    }

    @Test fun emptyOrForeignSharesAreNothing() {
        assertEquals(ShareRoute.NONE, ShareRouter.route(send, 0, "  "))
        assertEquals(ShareRoute.NONE, ShareRouter.route(multi, 0, null))
        assertEquals(ShareRoute.NONE, ShareRouter.route("android.intent.action.VIEW", 1, "x"))
        assertEquals(ShareRoute.NONE, ShareRouter.route(null, 1, "x"))
    }

    @Test fun eachStreamFollowsTheViewTable() {
        assertEquals(OpenRoute.TEXT, ShareRouter.streamRoute("text/markdown", "a.md"))
        assertEquals(OpenRoute.TEXT, ShareRouter.streamRoute("application/octet-stream", "DOC.md"))
        assertEquals(OpenRoute.IMAGE, ShareRouter.streamRoute("image/jpeg", "p.jpg"))
        assertEquals(OpenRoute.BINARY, ShareRouter.streamRoute("application/zip", "a.zip"))
    }

    @Test fun noteNameIsSafeAndBounded() {
        assertEquals("Trip plan.md", ShareRouter.noteName("Trip plan", "body"))
        assertEquals("first line.md", ShareRouter.noteName(null, "\n\nfirst line\nsecond"))
        assertEquals("a b.md", ShareRouter.noteName("a/b", "x"))
        assertEquals("Shared note.md", ShareRouter.noteName(" ", "   "))
        assertTrue(ShareRouter.noteName(null, "x".repeat(300)).length <= 63)
    }
}
