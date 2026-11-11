package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.contacts.ContactGroupStore
import com.diegonmarcos.cloudaccount.contacts.DeviceContactsRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1626 the device source's safety contract and the group-assignment persistence. The ContactsContract read itself is
 * covered on-device; here the repo must never crash and must return nothing while READ_CONTACTS is not granted
 * (Robolectric grants nothing by default), and group assignment must round-trip through the store.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsDeviceTest {
    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test fun repoReadsNothingAndDoesNotCrashWithoutPermission() {
        assertFalse("Robolectric has not granted READ_CONTACTS", DeviceContactsRepo.hasPermission(ctx()))
        val store = ContactGroupStore(ctx().getSharedPreferences("t1", Context.MODE_PRIVATE))
        assertEquals(emptyList<Any>(), DeviceContactsRepo(ctx(), store).contacts())
    }

    @Test fun groupAssignmentPersists() {
        val prefs = ctx().getSharedPreferences("contacts_groups_test", Context.MODE_PRIVATE)
        val store = ContactGroupStore(prefs)
        assertTrue(store.groupsOf("c1").isEmpty())
        store.toggle("c1", "Friends", on = true)
        store.toggle("c1", "Job", on = true)
        store.toggle("c1", "Friends", on = false)
        // a fresh store over the same prefs sees the persisted assignment
        val reloaded = ContactGroupStore(prefs)
        assertEquals(setOf("Job"), reloaded.groupsOf("c1"))
        assertEquals(mapOf("c1" to setOf("Job")), reloaded.all())
    }
}
