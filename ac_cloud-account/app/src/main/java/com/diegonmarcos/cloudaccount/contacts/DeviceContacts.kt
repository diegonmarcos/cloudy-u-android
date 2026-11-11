package com.diegonmarcos.cloudaccount.contacts

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * #1626 The real device-backed contacts source: reads the phone's contacts through ContactsContract (behind the
 * READ_CONTACTS runtime grant) and maps each to #1625's [Contact] — the company/organization name decides Business,
 * and `RawContacts.ACCOUNT_TYPE` decides the People app-origin through [Contacts.classifyOrigin]. Group assignment is
 * kept locally in [ContactGroupStore] (the device contacts are read-only here) and merged in. No contact data is baked
 * into the APK; it is read at runtime, and nothing leaves the device.
 */
class DeviceContactsRepo(
    private val context: Context,
    private val groups: ContactGroupStore,
) : ContactsRepo {
    override fun contacts(): List<Contact> {
        if (!hasPermission(context)) return emptyList()
        val assigned = groups.all()
        val byId = LinkedHashMap<String, Builder>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Organization.COMPANY,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
        )
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimeCol = c.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val nameCol = c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME_PRIMARY)
            val companyCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
            val typeCol = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (c.moveToNext()) {
                val id = if (idCol >= 0) c.getString(idCol) else null
                if (id.isNullOrBlank()) continue
                val b = byId.getOrPut(id) { Builder(id) }
                if (b.name.isBlank() && nameCol >= 0) c.getString(nameCol)?.let { if (it.isNotBlank()) b.name = it }
                if (b.origin == null && typeCol >= 0) b.origin = Contacts.classifyOrigin(c.getString(typeCol))
                if (mimeCol >= 0 && c.getString(mimeCol) == ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE && companyCol >= 0) {
                    c.getString(companyCol)?.let { if (it.isNotBlank() && b.company.isNullOrBlank()) b.company = it }
                }
            }
        }
        return byId.values
            .filter { it.name.isNotBlank() }
            .map { Contact(it.id, it.name, it.company, it.origin ?: ContactOrigin.OTHER, assigned[it.id] ?: emptySet()) }
            .sortedBy { it.name.lowercase() }
    }

    private class Builder(val id: String) {
        var name: String = ""
        var company: String? = null
        var origin: ContactOrigin? = null
    }

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * #1626 Persists which groups a contact is assigned to, keyed by the device contact id. The device address book is read
 * only, so the Friends/Family/Job/Favorites assignment lives here (EncryptedSharedPreferences is not needed: a group
 * name is not a secret). Values are stored as a `|`-joined string per id.
 */
class ContactGroupStore(private val prefs: SharedPreferences) {
    fun groupsOf(id: String): Set<String> =
        prefs.getString(KEY_PREFIX + id, "").orEmpty().split('|').filter { it.isNotBlank() }.toSet()

    fun all(): Map<String, Set<String>> {
        val out = HashMap<String, Set<String>>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith(KEY_PREFIX) || v !is String) continue
            val set = v.split('|').filter { it.isNotBlank() }.toSet()
            if (set.isNotEmpty()) out[k.removePrefix(KEY_PREFIX)] = set
        }
        return out
    }

    fun toggle(id: String, group: String, on: Boolean) {
        val next = if (on) groupsOf(id) + group else groupsOf(id) - group
        prefs.edit().putString(KEY_PREFIX + id, next.joinToString("|")).apply()
    }

    private companion object {
        const val KEY_PREFIX = "g:"
    }
}

/**
 * #1626 The permission-gated contacts section the bottom nav opens. Until READ_CONTACTS is granted it shows a
 * permission-needed state whose button routes to the app's own Configs ▸ Perms page (the standard grant path); once
 * granted it reads the device and draws #1625's screen for the section, with group assignment persisted.
 */
@Composable
fun ContactsSection(section: String, go: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val granted = remember(tick) { DeviceContactsRepo.hasPermission(ctx) }
    if (!granted) {
        ContactsPermissionNeeded(onOpenPerms = { go("configs", "perms") })
        return
    }
    val store = remember { ContactGroupStore(ctx.getSharedPreferences("contacts_groups", Context.MODE_PRIVATE)) }
    val repo = remember(tick) { DeviceContactsRepo(ctx, store) }
    val onToggle: (Contact, String) -> Unit = { c, g -> store.toggle(c.id, g, g !in c.groups); tick++ }
    when (section) {
        "all" -> AllContactsScreen(repo, onToggle)
        "business" -> BusinessContactsScreen(repo, onToggle)
        "people" -> PeopleContactsScreen(repo, onToggle)
        "groups" -> GroupsScreen(repo)
    }
}

@Composable
private fun ContactsPermissionNeeded(onOpenPerms: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("contacts:perm-needed"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Contacts permission needed", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cloud Account reads your phone's contacts to list them here — split into Business and People, filtered by " +
                "the app each came from, and grouped. The contacts are read on the device and nothing leaves it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenPerms, modifier = Modifier.testTag("contacts:perm-grant")) { Text("Open Permissions") }
    }
}
