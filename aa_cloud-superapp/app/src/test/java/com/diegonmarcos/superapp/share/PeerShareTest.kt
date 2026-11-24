package com.diegonmarcos.superapp.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1888 share sheet -> (peer, items) routing. */
class PeerShareTest {
    @Test fun everyStreamIsAFileInOrder() {
        assertEquals(listOf(PeerShare.Item.File("content://a"), PeerShare.Item.File("content://b")), PeerShare.items(PeerShare.SEND_MULTIPLE, listOf("content://a", "content://b"), "caption"))
    }

    @Test fun textIsALinkOnlyWhenItIsOneUrl() {
        assertEquals(listOf(PeerShare.Item.Link("https://x.org/a?b=1")), PeerShare.items(PeerShare.SEND, emptyList(), " https://x.org/a?b=1 "))
        assertEquals(listOf(PeerShare.Item.Text("see https://x.org")), PeerShare.items(PeerShare.SEND, emptyList(), "see https://x.org"))
        assertEquals(listOf(PeerShare.Item.Text("hello")), PeerShare.items(PeerShare.SEND, emptyList(), "hello"))
        assertTrue(PeerShare.isLink("http://a"))
        assertFalse(PeerShare.isLink("ftp://a"))
    }

    @Test fun nothingOrForeignActionIsEmpty() {
        assertTrue(PeerShare.items(PeerShare.SEND, emptyList(), "  ").isEmpty())
        assertTrue(PeerShare.items("android.intent.action.VIEW", listOf("x"), "y").isEmpty())
        assertTrue(PeerShare.items(null, emptyList(), null).isEmpty())
    }

    @Test fun peerIsAutoPickedWhenExactlyOneIsReachable() {
        assertEquals(PeerShare.Pick.None, PeerShare.pick(emptyList()))
        assertEquals(PeerShare.Pick.Auto("pc"), PeerShare.pick(listOf("pc")))
        assertEquals(PeerShare.Pick.Ask(listOf("pc", "vm")), PeerShare.pick(listOf("pc", "vm")))
    }

    @Test fun resultLines() {
        assertEquals("sent a.md", PeerShare.line("a.md", true))
        assertEquals("failed a.md", PeerShare.line("a.md", false))
    }
}
