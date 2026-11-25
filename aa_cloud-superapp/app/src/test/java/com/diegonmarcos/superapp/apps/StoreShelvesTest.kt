package com.diegonmarcos.superapp.apps

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #563 — the Store, asserted on what the shipped app RESOLVES, not on what a
 * source file says.
 *
 * Grouping: [StoreShelves.of] is the one answer both Store tabs draw their
 * headings from. It is called here exactly as AppStoreHost.classify calls it,
 * over the taxonomy baked into this build's BuildConfig, and the heading it
 * returns is read back. The two expectations are the brief's own acceptance
 * examples (cloud-drive → Data Apps; WhatsApp → Tools Inboxes & AI / Chat).
 *
 * Routing: the Store page and its two tabs are looked up in the resolved
 * section model and the fragment each tab's factory actually builds is
 * checked, so a tab id that drifts from its SectionPages route fails here
 * rather than as an empty strip on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreShelvesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val drive = "com.diegonmarcos.clouddrive"
    private val whatsapp = "com.whatsapp"
    private val shelves by lazy {
        StoreShelves.of(ctx, mapOf(drive to "cloud-drive", whatsapp to "WhatsApp"))
    }

    @Test
    fun `cloud-drive is shelved under Data`() {
        val shelf = shelves[drive]
        assertNotNull("cloud-drive got no shelf at all — the Store would draw it unheaded. Got: $shelves", shelf)
        assertTrue("cloud-drive heading is '${shelf!!.heading}', not a Data Apps shelf",
            shelf.heading.contains("Data"))
    }

    @Test
    fun `WhatsApp is shelved under Tools Communication, Messenger`() {
        val shelf = shelves[whatsapp]
        assertNotNull("WhatsApp got no shelf — got: $shelves", shelf)
        assertTrue("WhatsApp heading is '${shelf!!.heading}'",
            shelf.heading.startsWith("Tools") && shelf.heading.contains("Communication") &&
                shelf.heading.endsWith("/ Messenger"))
    }

    @Test
    fun `shelves sort in the taxonomy's declared order`() {
        // Communication precedes Data in ui.phone_sections (#1927, moved: Primary and Data
        // swapped places, so the order is Primary, Communication, Data), so a store sorted by
        // Shelf.order draws Communication first — the same order All Apps draws them in.
        val data = shelves.getValue(drive).order
        val comms = shelves.getValue(whatsapp).order
        assertTrue("Communication order '$comms' does not sort before Data order '$data'", comms < data)
    }

    @Test
    fun `the Store page resolves to its two tabs and each builds its own fragment`() {
        // #865 the Store entry launches Cloud Store (extapp:cloud-store); its two
        // pages stay, hidden, for page: links (the update notification, Update All).
        val config = Sections.all().firstNotNullOfOrNull { sec ->
            sec.allPages.firstOrNull { it.label == "Store" && it.action == "extapp:cloud-store" }?.let { sec to it }
        }
        assertNotNull("no Store entry launching extapp:cloud-store in the resolved sections", config)
        val (section, store) = config!!
        assertTrue("the Store entry moved to the Actions row", !store.isAction)
        val pages = SectionPages.pagesFor(section.id, includeHidden = true).associateBy { it.id }
        val cloudTab = pages["store-cloud"]
        val phoneTab = pages["store-phone"]
        assertNotNull("store-cloud is not a page of ${section.id}", cloudTab)
        assertNotNull("store-phone is not a page of ${section.id}", phoneTab)
        // #865 each tab builds the Cloud Store hand-off, which embeds the tab's own
        // fragment while Cloud Store is not installed.
        fun builds(f: androidx.fragment.app.Fragment): Class<*>? =
            CloudStoreHandoff.pageIdOf(f)?.let { CloudStoreHandoff.EMBEDDED[it] } ?: f::class.java
        assertEquals("the fleet tab builds the wrong fragment",
            StoreCloudFragment::class.java, builds(cloudTab!!.factory()))
        assertEquals("the phone tab builds the wrong fragment",
            StorePhoneFragment::class.java, builds(phoneTab!!.factory()))
        assertTrue("the fleet tab's title lost its display word: '${cloudTab.label}'",
            cloudTab.label.contains("Constellation"))
        assertEquals("the store's own id must not carry the old name", false,
            (store.id + cloudTab.id + phoneTab!!.id).contains("constellation", ignoreCase = true))
    }
}
