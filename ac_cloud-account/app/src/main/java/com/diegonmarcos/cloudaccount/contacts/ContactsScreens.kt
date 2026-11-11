package com.diegonmarcos.cloudaccount.contacts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * #1625 The four contacts sections of the bottom nav. All of them read a [ContactsRepo] and draw [Contacts]'
 * pure filters; the People section builds its app-origin filter chips from the sources actually present, and the
 * Groups section lists the default groups with their members. No colour literal lives here: colours are the theme's.
 */

/** ALL: every contact. */
@Composable
fun AllContactsScreen(repo: ContactsRepo = EmptyContactsRepo) {
    val contacts = remember(repo) { repo.contacts() }
    ContactList(Contacts.all(contacts), empty = "No contacts yet.", tag = "contacts:all")
}

/** BUSINESS: only company/organization contacts. */
@Composable
fun BusinessContactsScreen(repo: ContactsRepo = EmptyContactsRepo) {
    val contacts = remember(repo) { repo.contacts() }
    ContactList(Contacts.business(contacts), empty = "No business contacts. A contact with a company name shows here.", tag = "contacts:business")
}

/** PEOPLE: non-company contacts, with filter chips by the app/account each came from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleContactsScreen(repo: ContactsRepo = EmptyContactsRepo) {
    val contacts = remember(repo) { repo.contacts() }
    var origin by remember { mutableStateOf<ContactOrigin?>(null) }
    val sources = remember(contacts) { Contacts.sourcesPresent(contacts) }
    Column(Modifier.fillMaxSize()) {
        if (sources.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OriginChip("All", origin == null, "contacts:people:chip:all") { origin = null }
                for (o in sources) OriginChip("${o.label} · ${o.category.label}", origin == o, "contacts:people:chip:${o.name}") {
                    origin = if (origin == o) null else o
                }
            }
            HorizontalDivider()
        }
        ContactList(Contacts.peopleByOrigin(contacts, origin), empty = "No people match this filter.", tag = "contacts:people")
    }
}

/** GROUPS: the default groups (Friends / Family / Job / Favorites) and their members. */
@Composable
fun GroupsScreen(repo: ContactsRepo = EmptyContactsRepo) {
    val contacts = remember(repo) { repo.contacts() }
    val groups = remember(contacts) { Contacts.groupNames(contacts) }
    LazyColumn(Modifier.fillMaxSize().testTag("contacts:groups")) {
        items(groups, key = { it }) { group ->
            val members = Contacts.inGroup(contacts, group)
            ListItem(
                headlineContent = { Text(group) },
                supportingContent = { Text(if (members.isEmpty()) "No contacts yet" else members.joinToString { it.name }) },
                trailingContent = { Text(members.size.toString()) },
                modifier = Modifier.fillMaxWidth().testTag("contacts:group:$group"),
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OriginChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@Composable
private fun ContactList(contacts: List<Contact>, empty: String, tag: String) {
    if (contacts.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp).testTag("$tag:empty")) { Text(empty, style = MaterialTheme.typography.bodyMedium) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag(tag)) {
        items(contacts, key = { it.id }) { c ->
            val secondary = buildList {
                if (c.isBusiness) add(c.company!!) else add(c.origin.label)
                if (c.groups.isNotEmpty()) add(c.groups.sorted().joinToString("/"))
            }.joinToString(" · ")
            ListItem(
                headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.fillMaxWidth().testTag("contacts:row:${c.id}"),
            )
            HorizontalDivider()
        }
    }
}
