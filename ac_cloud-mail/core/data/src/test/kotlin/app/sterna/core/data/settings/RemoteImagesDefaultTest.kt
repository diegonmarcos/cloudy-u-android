package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1837: "Load remote images" is ON by default (Gmail loads them), the setting is kept, and a stored "off" of the old default
 * flips to on exactly once. The key names are spelled out here: they name persisted user data.
 */
class RemoteImagesDefaultTest {
    private val key = booleanPreferencesKey("remote_images")
    private val done = booleanPreferencesKey("remote_images_default_on_1837")

    @Test fun `image download defaults to on`() {
        assertTrue("a reader who never touched the switch downloads images", remoteImagesFrom(emptyPreferences()))
        assertTrue("and the named default says so too", REMOTE_IMAGES_DEFAULT)
        assertEquals("remote_images", REMOTE_IMAGES_KEY)
    }

    @Test fun `an explicit choice is honoured in both directions`() {
        assertTrue(remoteImagesFrom(mutablePreferencesOf(key to true)))
        assertFalse(remoteImagesFrom(mutablePreferencesOf(key to false)))
    }

    @Test fun `a stored off flips to on once`() {
        val prefs = mutablePreferencesOf(key to false)
        assertTrue("the migration changed the stored value", migrateRemoteImagesToDefaultOn(prefs))
        assertTrue(remoteImagesFrom(prefs))
        assertEquals("the marker is set", true, prefs[done])
        // The reader turns it off again afterwards: the migration must not flip it a second time.
        prefs[key] = false
        assertFalse("the migration is one-shot", migrateRemoteImagesToDefaultOn(prefs))
        assertFalse(remoteImagesFrom(prefs))
    }

    @Test fun `a reader who never stored anything is migrated without a stored value`() {
        val prefs = mutablePreferencesOf()
        assertFalse("nothing to flip", migrateRemoteImagesToDefaultOn(prefs))
        assertTrue("it reads on", remoteImagesFrom(prefs))
        assertEquals(true, prefs[done])
        assertEquals("no value was written, the default speaks", null, prefs[key])
    }

    @Test fun `an explicit off written after the migration survives it`() {
        val prefs = mutablePreferencesOf(done to true, key to false)
        assertFalse(migrateRemoteImagesToDefaultOn(prefs))
        assertFalse(remoteImagesFrom(prefs))
    }
}
