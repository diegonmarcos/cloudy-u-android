package com.diegonmarcos.cloudaccount.autofill

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.autofill.AddressType
import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.AutofillSot
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.autofill.Fields
import com.diegonmarcos.superapp.autofill.SiteRule
import com.diegonmarcos.superapp.autofill.Snippet
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitChip
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSegmented
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Account ▸ Autofill / Sites / Snippets / Import: the editor of the non-secret autofill Source of
 * Truth (a0_docs/eng-specs/autofill-3-tier.md). Dense Store-look rows; tap a row to edit it in place,
 * Delete asks first. Every save goes through [AutofillStore] and then notifies the provider's URIs,
 * so a browser or keyboard holding a cursor sees the change.
 *
 * What is NOT edited here, on purpose: passwords, passkeys, one-time codes, cards and identity
 * documents (DNI/NIE, passport, Personalausweis, RG/CPF…). The headers say so, the store has no
 * column for any of them, and Import routes detected IDs to Cloud Vault instead.
 */
private fun notifyAll(ctx: Context) = AutofillSot.PATHS.forEach { ctx.contentResolver.notifyChange(AutofillSot.uri(it), null) }

/** Human label for a profile / address column. Pure. */
fun columnLabel(col: String): String = when (col) {
    "honorific_prefix" -> "Title"
    "given_name" -> "Given name"
    "additional_name" -> "Middle name(s)"
    "family_name" -> "Family name (1st surname)"
    "family_name2" -> "2nd family name (ES)"
    "display_name" -> "Display / full name"
    "bday" -> "Birth date (YYYY-MM-DD)"
    "nationality" -> "Nationality"
    "organization" -> "Company"
    "organization_title" -> "Job title"
    "co_line" -> "c/o"
    "street" -> "Street"
    "house_number" -> "House number"
    "floor_door" -> "Floor / door (ES P04 0001)"
    "complement" -> "Complement (BR apto)"
    "neighborhood" -> "Neighbourhood (BR bairro)"
    "postal_code" -> "Postal code (CP / PLZ / CEP)"
    "city" -> "City"
    "state" -> "State / province"
    "country" -> "Country (ES, DE, BR…)"
    else -> col
}

/** One dense line describing a profile, for its list row: counts and countries, never a street or a number. Pure. */
fun profileSummary(p: AutofillProfile): String = listOfNotNull(
    p.addresses.map { it["country"] }.filter { it.isNotBlank() }.distinct().joinToString("/").ifBlank { null },
    "${p.addresses.size} address${if (p.addresses.size == 1) "" else "es"}",
    "${p.contacts(ContactKind.TEL).size} phone(s)",
    "${p.contacts(ContactKind.EMAIL).size} email(s)",
).joinToString(" · ")

/** One dense line describing a rule: `form › field → key`, or the default-profile rule. Pure. */
fun ruleSummary(r: SiteRule): String = if (r.isProfileRule) "default profile → ${r.literalValue}" + if (r.enabled) "" else " (off)" else
    (if (r.formSelector.isBlank()) "any form" else r.formSelector) + " › " + r.fieldSelector + " → " +
        (if (r.literalValue.isNotBlank()) "\"${r.literalValue.take(24)}\"" else r.fieldKey) + if (r.enabled) "" else " (off)"

@Composable
private fun Page(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = KitDensity.large, vertical = KitDensity.medium)) {
        KitSectionHeader(title, subtitle)
        content()
    }
}

@Composable
private fun AutofillField(label: String, value: String, tag: String, keyboard: KeyboardType = KeyboardType.Text, single: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = KitDensity.caption) }, singleLine = single,
        minLines = if (single) 1 else 3, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(tag))
}

@Composable
private fun Small(text: String, tag: String, onClick: () -> Unit) =
    Text(text, color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption,
        modifier = Modifier.testTag(tag).clickable(onClick = onClick).padding(4.dp))

// ── Profiles ─────────────────────────────────────────────────────────────

@Composable
fun AutofillProfilesPage() {
    val ctx = LocalContext.current
    val store = remember { AutofillStore(ctx) }
    var rows by remember { mutableStateOf(store.profiles()) }
    var editing by remember { mutableStateOf<AutofillProfile?>(null) }
    var deleting by remember { mutableStateOf<AutofillProfile?>(null) }
    val refresh = { rows = store.profiles(); notifyAll(ctx) }

    Page("Autofill profiles", "One profile per way a country's forms want you (e.g. Spain, Germany, Brazil), each with its addresses, phones, emails and links. Cloud Browser fills contact and address forms from them; Cloud Keyboard offers them as candidates. Passwords, codes, cards and ID documents are never here: they stay in Cloud Vault.") {
        KitActionBar(listOf(KitAction("Add profile", "autofill:profile:add") {
            editing = AutofillProfile(label = "", isDefault = rows.isEmpty(), addresses = listOf(AutofillAddress(type = AddressType.HOME, isDefault = true)))
        }))
        editing?.let { e -> ProfileEditor(e, onSave = { p -> store.save(p); editing = null; refresh() }, onCancel = { editing = null }) }
        if (rows.isEmpty() && editing == null) Text("No profile yet. Add one, or paste your data under Import.", color = LocalKitPalette.current.textSecondary,
            fontSize = KitDensity.body, modifier = Modifier.padding(vertical = KitDensity.medium))
        rows.forEach { p ->
            KitListRow(p.title, secondary = profileSummary(p), leading = p.title.take(1).uppercase(),
                pill = if (p.isDefault) "default" to KitState.OK else null, tag = "autofill:profile:${p.id}",
                onClick = { editing = p }, trailing = { Small("Delete", "autofill:profile:${p.id}:delete") { deleting = p } })
            HorizontalDivider()
        }
    }
    deleting?.let { d ->
        KitConfirmDialog("Delete ${d.title}?", "Its ${d.addresses.size} address(es) and ${d.contacts.size} contact(s) go with it; the browser and the keyboard stop offering them.",
            "Delete", "Cancel", onConfirm = { store.delete(AutofillSot.PATH_PROFILES, d.id); deleting = null; refresh() }, onDismiss = { deleting = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(start: AutofillProfile, onSave: (AutofillProfile) -> Unit, onCancel: () -> Unit) {
    var p by remember(start) { mutableStateOf(start) }
    KitCard(Modifier.padding(vertical = KitDensity.small).testTag("autofill:profile:editor")) {
        AutofillField("Profile name (shown on the fill chip, e.g. Spain-A)", p.label, "autofill:profile:label") { p = p.copy(label = it) }
        KitSwitchRow("Default profile", "Offered first unless a site rule or the site's country picks another", p.isDefault, { p = p.copy(isDefault = it) })
        AutofillSot.PROFILE_FIELDS.forEach { c ->
            AutofillField(columnLabel(c), p[c], "autofill:profile:field:$c") { v -> p = p.copy(fields = p.fields + (c to v)) }
        }
        Text("Addresses", color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.title, modifier = Modifier.padding(top = KitDensity.small))
        p.addresses.forEachIndexed { i, a ->
            AddressEditor(a, i, onChange = { na -> p = p.copy(addresses = p.addresses.toMutableList().also { it[i] = na }) },
                onRemove = { p = p.copy(addresses = p.addresses.filterIndexed { j, _ -> j != i }) })
        }
        Small("+ Add address", "autofill:profile:address:add") { p = p.copy(addresses = p.addresses + AutofillAddress(type = AddressType.POSTAL)) }
        Text("Phones, emails, links", color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.title, modifier = Modifier.padding(top = KitDensity.small))
        p.contacts.forEachIndexed { i, c ->
            ContactEditor(c, i, onChange = { nc -> p = p.copy(contacts = p.contacts.toMutableList().also { it[i] = nc }) },
                onRemove = { p = p.copy(contacts = p.contacts.filterIndexed { j, _ -> j != i }) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("+ Phone", "autofill:profile:contact:add:tel") { p = p.copy(contacts = p.contacts + AutofillContact(kind = ContactKind.TEL, type = "mobile")) }
            Small("+ Email", "autofill:profile:contact:add:email") { p = p.copy(contacts = p.contacts + AutofillContact(kind = ContactKind.EMAIL, type = "personal")) }
            Small("+ Link", "autofill:profile:contact:add:url") { p = p.copy(contacts = p.contacts + AutofillContact(kind = ContactKind.URL)) }
        }
        KitActionBar(listOf(
            KitAction("Save", "autofill:profile:save") {
                onSave(p.copy(label = p.label.trim(), fields = p.fields.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }))
            },
            KitAction("Cancel", "autofill:profile:cancel", onClick = onCancel),
        ))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressEditor(a: AutofillAddress, i: Int, onChange: (AutofillAddress) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp).testTag("autofill:address:$i")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AddressType.ALL.forEach { t ->
                Row(Modifier.clickable { onChange(a.copy(type = t)) }) {
                    KitChip((if (a.type == t) "● " else "") + AddressType.label(t), null, tag = "autofill:address:$i:type:$t")
                }
            }
            Small(if (a.isDefault) "default" else "make default", "autofill:address:$i:default") { onChange(a.copy(isDefault = !a.isDefault)) }
            Small("remove", "autofill:address:$i:remove", onRemove)
        }
        AutofillField("Label (optional)", a.label, "autofill:address:$i:label") { onChange(a.copy(label = it)) }
        AutofillSot.ADDRESS_FIELDS.forEach { c ->
            AutofillField(columnLabel(c), a[c], "autofill:address:$i:$c") { v -> onChange(a.copy(fields = a.fields + (c to v))) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ContactEditor(c: AutofillContact, i: Int, onChange: (AutofillContact) -> Unit, onRemove: () -> Unit) {
    val kb = when (c.kind) { ContactKind.TEL -> KeyboardType.Phone; ContactKind.EMAIL -> KeyboardType.Email; else -> KeyboardType.Uri }
    Column(Modifier.padding(vertical = 2.dp).testTag("autofill:contact:$i")) {
        AutofillField(when (c.kind) { ContactKind.TEL -> "Phone"; ContactKind.EMAIL -> "Email"; else -> "Link" }, c.value, "autofill:contact:$i:value", kb) { onChange(c.copy(value = it)) }
        Row {
            if (c.kind == ContactKind.TEL) {
                KitSegmented(listOf("mobile" to "Mobile", "landline" to "Landline", "work" to "Work"), c.type, { onChange(c.copy(type = it)) },
                    Modifier.weight(1f), tag = "autofill:contact:$i:type")
            }
            Small(if (c.isDefault) "default" else "make default", "autofill:contact:$i:default") { onChange(c.copy(isDefault = !c.isDefault)) }
            Small("remove", "autofill:contact:$i:remove", onRemove)
        }
        if (c.kind == ContactKind.TEL) AutofillField("Country (ES, DE, BR…)", c.country, "autofill:contact:$i:country") { onChange(c.copy(country = it.trim().uppercase())) }
    }
}

// ── Site rules ───────────────────────────────────────────────────────────

@Composable
fun AutofillRulesPage() {
    val ctx = LocalContext.current
    val store = remember { AutofillStore(ctx) }
    var rows by remember { mutableStateOf(store.rules()) }
    var editing by remember { mutableStateOf<SiteRule?>(null) }
    var deleting by remember { mutableStateOf<SiteRule?>(null) }
    val refresh = { rows = store.rules(); notifyAll(ctx) }

    Page("Site rules", "Per-site mapping for forms the browser cannot read on its own: domain → form → field → profile field (or a fixed non-secret value, or ignore). A rule beats the browser's guess; it can never target a password, code, card or ID field. A default-profile rule picks the profile for a site or a whole country domain (de → your Germany profile).") {
        KitActionBar(listOf(
            KitAction("Add rule", "autofill:rule:add") { editing = SiteRule() },
            KitAction("Add default profile", "autofill:rule:add:profile") { editing = SiteRule(fieldKey = Fields.PROFILE, fieldSelector = "*") },
        ))
        editing?.let { e -> RuleEditor(e, onSave = { r -> if (store.save(r) != null) { editing = null; refresh() } }, onCancel = { editing = null }) }
        if (rows.isEmpty() && editing == null) Text("No rule yet. Most forms need none.", color = LocalKitPalette.current.textSecondary,
            fontSize = KitDensity.body, modifier = Modifier.padding(vertical = KitDensity.medium))
        rows.sortedBy { it.domain }.forEach { r ->
            KitListRow(r.domain, secondary = ruleSummary(r), tag = "autofill:rule:${r.id}", onClick = { editing = r },
                pill = if (r.enabled) null else "off" to KitState.IDLE,
                trailing = { Small("Delete", "autofill:rule:${r.id}:delete") { deleting = r } })
            HorizontalDivider()
        }
    }
    deleting?.let { d ->
        KitConfirmDialog("Delete the rule for ${d.domain}?", ruleSummary(d), "Delete", "Cancel",
            onConfirm = { store.delete(AutofillSot.PATH_RULES, d.id); deleting = null; refresh() }, onDismiss = { deleting = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(start: SiteRule, onSave: (SiteRule) -> Unit, onCancel: () -> Unit) {
    var r by remember(start) { mutableStateOf(start) }
    KitCard(Modifier.padding(vertical = KitDensity.small).testTag("autofill:rule:editor")) {
        AutofillField("Domain (example.com covers its subdomains; de covers every .de site)", r.domain, "autofill:rule:domain", KeyboardType.Uri) { r = r.copy(domain = it) }
        if (r.isProfileRule) {
            AutofillField("Profile name to offer first", r.literalValue, "autofill:rule:profile") { r = r.copy(literalValue = it) }
        } else {
            AutofillField("Form selector (blank = any form)", r.formSelector, "autofill:rule:form") { r = r.copy(formSelector = it) }
            AutofillField("Field selector (CSS, e.g. #zip or input[name=plz])", r.fieldSelector, "autofill:rule:field") { r = r.copy(fieldSelector = it) }
            AutofillField("Profile field (or ignore)", r.fieldKey, "autofill:rule:key") { r = r.copy(fieldKey = it.trim()) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (Fields.ALL + Fields.IGNORE).forEach { k ->
                    Row(Modifier.clickable { r = r.copy(fieldKey = k) }) { KitChip(k, null, tag = "autofill:rule:key:$k") }
                }
            }
            AutofillField("Fixed value instead (non-secret, optional)", r.literalValue, "autofill:rule:value") { r = r.copy(literalValue = it) }
        }
        KitSwitchRow("Enabled", "Off keeps the rule without applying it", r.enabled, { r = r.copy(enabled = it) })
        if (!r.valid) Text(if (r.isProfileRule) "Needs a domain and a profile name." else "Needs a domain, a field selector and a known profile field (or a fixed value).",
            color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
        KitActionBar(listOf(
            KitAction("Save", "autofill:rule:save", enabled = r.valid) { onSave(r) },
            KitAction("Cancel", "autofill:rule:cancel", onClick = onCancel),
        ))
    }
}

// ── Snippets ─────────────────────────────────────────────────────────────

@Composable
fun AutofillSnippetsPage() {
    val ctx = LocalContext.current
    val store = remember { AutofillStore(ctx) }
    var rows by remember { mutableStateOf(store.snippets()) }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    val refresh = { rows = store.snippets(); notifyAll(ctx) }

    Page("Snippets", "Labelled free-text blocks (About me, a bio, a standard message). Cloud Keyboard offers them on multi-line fields; Cloud Browser offers them as a chip on about / bio / message / comment boxes. Never filled on their own, never on password or code fields.") {
        KitActionBar(listOf(KitAction("Add snippet", "autofill:snippet:add") { editing = Snippet() }))
        editing?.let { e ->
            var label by remember(e) { mutableStateOf(e.label) }
            var text by remember(e) { mutableStateOf(e.text) }
            KitCard(Modifier.padding(vertical = KitDensity.small).testTag("autofill:snippet:editor")) {
                AutofillField("Label", label, "autofill:snippet:label") { label = it }
                AutofillField("Text", text, "autofill:snippet:text", single = false) { text = it }
                KitActionBar(listOf(
                    KitAction("Save", "autofill:snippet:save", enabled = text.isNotBlank()) { store.save(e.copy(label = label.trim(), text = text)); editing = null; refresh() },
                    KitAction("Cancel", "autofill:snippet:cancel") { editing = null },
                ))
            }
        }
        rows.forEach { s ->
            KitListRow(s.label.ifBlank { s.text.lineSequence().first().take(24) }, secondary = s.text.replace('\n', ' ').take(80), tag = "autofill:snippet:${s.id}",
                onClick = { editing = s },
                trailing = { Small("Delete", "autofill:snippet:${s.id}:delete") { store.delete(AutofillSot.PATH_SNIPPETS, s.id); refresh() } })
            HorizontalDivider()
        }
    }
}

// ── Import ───────────────────────────────────────────────────────────────

@Composable
fun AutofillImportPage(pickFile: (((String) -> Unit) -> Unit)? = null) {
    Page("Import", "Paste your data as text (labelled lines, one block per profile) or JSON. Nothing is saved until you review it. ID documents found in the paste are NOT stored here: they belong in Cloud Vault as Identity items.") {
        AutofillImportBody(pickFile)
    }
}

/**
 * The Import flow without its page frame, so Home ▸ Import configs draws the very same review (and the same
 * routing of ID documents to Cloud Vault) inside its own scroll. [pickFile] adds "Pick file": the file's text
 * lands in the paste box, then Review as usual.
 */
@Composable
fun AutofillImportBody(pickFile: (((String) -> Unit) -> Unit)? = null) {
    val ctx = LocalContext.current
    val store = remember { AutofillStore(ctx) }
    var text by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<AutofillImport.Result?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    // The IDs of the last review stay listed after Save until each is sent to Cloud Vault.
    var ids by remember { mutableStateOf<List<AutofillImport.ParsedId>>(emptyList()) }
    var sent by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val vaultTakesIds = remember(parsed, ids) { VaultIdentityHandoff.isAvailable(ctx) }

    Column(Modifier.fillMaxWidth()) {
        AutofillField("Paste text or JSON", text, "autofill:import:text", single = false) { text = it; parsed = null; saved = null }
        KitActionBar(listOfNotNull(
            KitAction("Review", "autofill:import:review", enabled = text.isNotBlank()) {
                parsed = AutofillImport.parse(text).also { ids = it.ids; sent = emptySet() }
            },
            pickFile?.let { pick -> KitAction("Pick file", "autofill:import:file") { pick { t -> text = t; parsed = null; saved = null } } },
            KitAction("Format help", "autofill:import:help") { text = AutofillImport.EXAMPLE },
        ))
        saved?.let { Text(it, color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.body, modifier = Modifier.testTag("autofill:import:saved")) }
        parsed?.let { r ->
            KitCard(Modifier.padding(vertical = KitDensity.small).testTag("autofill:import:review:card")) {
                Text("To save in Cloud Account", color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.title)
                r.profiles.forEach { p -> KitListRow(p.title, secondary = profileSummary(p), tag = "autofill:import:profile:${p.title}") }
                r.snippets.forEach { s -> KitListRow("Snippet: " + s.label.ifBlank { "untitled" }, secondary = s.text.replace('\n', ' ').take(60)) }
                if (r.profiles.isEmpty() && r.snippets.isEmpty()) Text("Nothing recognised to save.", color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.body)
                if (r.ids.isNotEmpty()) IdDocumentsForVault(ids, sent, vaultTakesIds) { i -> sent = sent + i }
                if (r.skipped.isNotEmpty()) Text("${r.skipped.size} line(s) not understood were left out.", color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption)
                KitActionBar(listOf(
                    KitAction("Save ${r.profiles.size} profile(s), ${r.snippets.size} snippet(s)", "autofill:import:save", enabled = r.profiles.isNotEmpty() || r.snippets.isNotEmpty()) {
                        r.profiles.forEach { store.save(it) }; r.snippets.forEach { store.save(it) }; notifyAll(ctx)
                        val pending = r.ids.indices.count { it !in sent }
                        saved = "Saved. " + when {
                            pending == 0 -> ""
                            vaultTakesIds -> "Send the $pending ID document(s) below to Cloud Vault."
                            else -> "Add the $pending ID document(s) in Cloud Vault."
                        }
                        parsed = null; text = ""
                    },
                    KitAction("Discard", "autofill:import:discard") { parsed = null; ids = emptyList(); sent = emptySet() },
                ))
            }
        }
        // After Save the review closes; its ID documents stay here until each is sent.
        if (parsed == null && vaultTakesIds && ids.indices.any { it !in sent }) {
            KitCard(Modifier.padding(vertical = KitDensity.small).testTag("autofill:import:ids:card")) {
                IdDocumentsForVault(ids, sent, vaultTakesIds) { i -> sent = sent + i }
            }
        }
    }
}

/**
 * The ID documents of a review, masked (last three characters only). With a Cloud Vault that has the
 * add-identity entry point, each row has "Add to Cloud Vault": the vault opens its new-Identity screen
 * prefilled, after its own unlock, and saves only on the user's tap. Otherwise (vault missing or too
 * old) the previous flow: open Cloud Vault and add the Identity item by hand.
 */
@Composable
private fun IdDocumentsForVault(ids: List<AutofillImport.ParsedId>, sent: Set<Int>, vaultTakesIds: Boolean, onSent: (Int) -> Unit) {
    val ctx = LocalContext.current
    if (ids.isEmpty()) return
    Text(
        if (vaultTakesIds) "ID documents — add each to Cloud Vault as an Identity item, not here (you review and save it there)"
        else "ID documents — save these in Cloud Vault (Identity item), not here",
        color = LocalKitPalette.current.textPrimary, fontSize = KitDensity.title, modifier = Modifier.padding(top = KitDensity.small),
    )
    ids.forEachIndexed { i, id ->
        val masked = AutofillImport.mask(id.number) + if (id.country.isNotBlank()) " · ${id.country}" else ""
        KitListRow(id.type, secondary = if (i in sent) "$masked · sent to Cloud Vault" else masked, tag = "autofill:import:id")
        if (vaultTakesIds && i !in sent) {
            KitActionBar(listOf(KitAction("Add to Cloud Vault", "autofill:import:vault:add:$i") {
                if (VaultIdentityHandoff.send(ctx, id)) onSent(i) else VaultIdentityHandoff.openVault(ctx)
            }), filledFirst = false)
        }
    }
    if (!vaultTakesIds) KitActionBar(listOf(KitAction("Open Cloud Vault", "autofill:import:vault") { VaultIdentityHandoff.openVault(ctx) }), filledFirst = false)
}
