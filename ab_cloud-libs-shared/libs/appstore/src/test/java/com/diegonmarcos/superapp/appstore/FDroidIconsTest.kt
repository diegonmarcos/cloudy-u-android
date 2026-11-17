package com.diegonmarcos.superapp.appstore

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1772 the F-Droid / Izzy index listing is where a Phone tile's real icon URL comes from (android.util.JsonReader needs the framework). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FDroidIconsTest {
    @Test fun fdroidIconsComeFromTheRepoListing() {
        val json = """{"repo":{},"apps":[{"packageName":"a.b","name":"A","icon":"a.b.12.png"},{"packageName":"c.d","name":"C"},{"packageName":"e.f","icon":"e.f.3.png"}],"packages":{}}"""
        assertEquals(mapOf("a.b" to "https://f-droid.org/repo/icons-640/a.b.12.png"), FDroidIndex.icons(json.byteInputStream(), setOf("a.b", "c.d"), "https://f-droid.org/repo/"))
        assertEquals("https://f-droid.org/repo/icons-640/a.b.12.png", FDroidIndex.search(json.byteInputStream(), "a.b", 5, "https://f-droid.org/repo/").first().icon)
        assertEquals("", FDroidIndex.iconUrl("https://f-droid.org/repo/", ""))
    }
}
