package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BreadcrumbFolderTest {
    @Test fun `a label names itself in the path`() =
        assertEquals("Receipts", breadcrumbFolder(null, false, false, true, "Receipts"))

    @Test fun `Archive names itself`() =
        assertEquals("Archive", breadcrumbFolder("archive", false, false, true, "Archive"))

    @Test fun `the Inbox adds no second crumb`() = assertNull(breadcrumbFolder("inbox", false, false, true, "Inbox"))

    @Test fun `unified and unread views add none`() {
        assertNull(breadcrumbFolder(null, true, false, true, "Receipts"))
        assertNull(breadcrumbFolder(null, false, true, true, "Receipts"))
    }

    @Test fun `no folder selected adds none`() = assertNull(breadcrumbFolder(null, false, false, false, "Receipts"))
}
