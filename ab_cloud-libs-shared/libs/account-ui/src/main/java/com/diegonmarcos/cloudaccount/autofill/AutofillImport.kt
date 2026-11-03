package com.diegonmarcos.cloudaccount.autofill

import com.diegonmarcos.superapp.autofill.AddressType
import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.AutofillSot
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.autofill.Snippet
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account ▸ Import, pure: a pasted text or JSON → profiles (with addresses, phones, emails, links)
 * and snippets for the user to review, plus the ID documents found in it, which are NEVER stored in
 * the non-secret SOT: the review routes them to Cloud Vault (a0_docs/eng-specs/autofill-3-tier.md).
 *
 * TEXT: one `Key: value` per line, EN/ES/DE/PT keys. `Profile: <name>` starts a profile,
 * `Address (home|postal|c/o|post office): [label]` an address of it, `Snippet: <label>` a free-text
 * block that runs (blank lines included) until `---` or the next `Profile:`/`Snippet:`. A bare email,
 * phone or link line is recognised as one. ID lines (DNI, NIE, passport, Personalausweis, CPF, RG…)
 * and their details (issued, valid until, support/CAN) are collected apart. JSON: see [EXAMPLE_JSON].
 */
object AutofillImport {
    /**
     * One ID document found in the paste, never stored here. [givenName], [middleName] and [familyName]
     * are the holder's, from the profile the ID was listed under (both Spanish family names in
     * [familyName]), so Cloud Vault's new-Identity screen comes prefilled with them.
     */
    data class ParsedId(
        val type: String, val number: String, val country: String = "", val details: Map<String, String> = emptyMap(),
        val givenName: String = "", val middleName: String = "", val familyName: String = "",
    ) {
        /** A number never reaches a log line or a crash report through this class. */
        override fun toString(): String = "ParsedId(type=$type, number=${AutofillImport.mask(number)}, country=$country)"
    }
    data class Result(val profiles: List<AutofillProfile>, val snippets: List<Snippet>, val ids: List<ParsedId>, val skipped: List<String>)

    /** Only the last three characters show; the review never displays a whole ID number. */
    fun mask(n: String): String = n.trim().let { if (it.length <= 3) "•••" else "•".repeat((it.length - 3).coerceAtMost(8)) + it.takeLast(3) }

    val EXAMPLE = """
        Profile: Testy - Spain-A
        Given name: Testy
        Family name: Fakeson
        Second family name: Example
        Birth date: 1990-01-01
        Nationality: ES
        Phone (mobile, ES): +00 600 000 000
        Email: testy@example.invalid
        Address (home):
        Street: Calle Falsa
        Number: 1
        Floor/door: P04 0001
        Postal code: 00000
        City: Sampletown
        Country: ES

        Snippet: About me
        A short text about me, more lines allowed.
        ---
    """.trimIndent()

    private val PROFILE_KEYS = mapOf(
        "honorific_prefix" to listOf("title", "anrede", "tratamiento"),
        "given_name" to listOf("given name", "first name", "nombre", "vorname", "prenom", "primeiro nome", "nome"),
        "additional_name" to listOf("middle name", "middle names", "segundo nombre", "zweitname", "nome do meio"),
        "family_name" to listOf("family name", "last name", "surname", "primer apellido", "apellido", "apellidos", "nachname", "familienname", "sobrenome", "nom"),
        "family_name2" to listOf("second family name", "2nd family name", "segundo apellido", "second surname"),
        "display_name" to listOf("full name", "display name", "nombre completo", "vollstandiger name", "nome completo"),
        "bday" to listOf("birth date", "birthday", "date of birth", "dob", "fecha de nacimiento", "geburtsdatum", "data de nascimento"),
        "nationality" to listOf("nationality", "nacionalidad", "staatsangehorigkeit", "nacionalidade"),
        "organization" to listOf("company", "organization", "organisation", "empresa", "firma"),
        "organization_title" to listOf("job title", "position", "cargo", "beruf"),
    )
    private val ADDRESS_KEYS = mapOf(
        "co_line" to listOf("c/o", "co", "care of", "zu handen"),
        "street" to listOf("street", "calle", "via", "strasse", "rua", "logradouro"),
        "house_number" to listOf("number", "house number", "no", "nr", "numero", "hausnummer"),
        "floor_door" to listOf("floor/door", "floor", "door", "piso", "puerta", "piso/puerta", "etage", "stockwerk"),
        "complement" to listOf("complement", "complemento", "apto", "apartment", "adresszusatz"),
        "neighborhood" to listOf("neighborhood", "neighbourhood", "bairro", "barrio", "ortsteil"),
        "postal_code" to listOf("postal code", "zip", "postcode", "cp", "codigo postal", "plz", "postleitzahl", "cep"),
        "city" to listOf("city", "town", "ciudad", "localidad", "stadt", "ort", "cidade"),
        "state" to listOf("state", "province", "provincia", "region", "bundesland", "estado"),
        "country" to listOf("country", "pais", "land", "pays"),
    )
    private val ID_TYPES = listOf(
        "DNI" to Regex("^(dni|documento nacional)"), "NIE" to Regex("^nie\\b"), "NIF" to Regex("^nif\\b"), "TIE" to Regex("^tie\\b"),
        "Passport" to Regex("^(passport|pasaporte|passaporte|reisepass|pass)\\b"),
        "Personalausweis" to Regex("^(personalausweis|ausweis|id card|identity card|carte d.identite)"),
        "Residence permit" to Regex("^(residence permit|aufenthaltstitel|permiso de residencia|tarjeta de residencia)"),
        "CPF" to Regex("^cpf\\b"), "RG" to Regex("^rg\\b"), "SSN" to Regex("^(ssn|social security)"),
    )
    private val ID_DETAILS = mapOf(
        "issued" to listOf("issued", "issue date", "expedicion", "fecha de expedicion", "ausgestellt", "ausstellungsdatum", "emissao"),
        "valid_until" to listOf("valid until", "expires", "expiry", "caducidad", "valido hasta", "gultig bis", "validade"),
        "support" to listOf("support", "soporte", "numero de soporte", "serial", "can", "zugangsnummer"),
        "issuing_country" to listOf("issuing country", "pais de expedicion", "ausstellungsland"),
    )

    private fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase().trim().replace("ß", "ss"), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trimEnd(':', '.')
    private fun lookup(map: Map<String, List<String>>, key: String): String? = map.entries.firstOrNull { (_, ks) -> key in ks }?.key

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val PHONE = Regex("^\\+?[0-9][0-9 ()./-]{6,}$")
    private val URL = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)

    fun parse(input: String): Result {
        val t = input.trim()
        if (t.startsWith("{")) return runCatching { parseJson(JSONObject(t)) }.getOrElse { Result(emptyList(), emptyList(), emptyList(), listOf("JSON: ${it.message}")) }
        return parseText(t)
    }

    // ── text ─────────────────────────────────────────────────────────────

    private class Draft(var label: String) {
        val fields = LinkedHashMap<String, String>()
        val addresses = ArrayList<AutofillAddress>()
        val contacts = ArrayList<AutofillContact>()
        fun build(first: Boolean) = AutofillProfile(label = label.trim(), isDefault = first, fields = fields.toMap(),
            addresses = addresses.mapIndexed { i, a -> a.copy(isDefault = i == 0) },
            contacts = contacts.groupBy { it.kind }.values.flatMap { cs -> cs.mapIndexed { i, c -> c.copy(isDefault = i == 0) } })
    }

    private fun addressType(header: String): String {
        val h = norm(header)
        return when {
            Regex("post ?office|postfiliale|packstation|correos|agencia").containsMatchIn(h) -> AddressType.POST_OFFICE
            Regex("c/o|care of").containsMatchIn(h) -> AddressType.CO
            Regex("postal|postanschrift|mailing|correspondencia|postadresse").containsMatchIn(h) -> AddressType.POSTAL
            Regex("home|domicilio|residen|wohn|casa").containsMatchIn(h) || h == "address" || h == "direccion" || h == "adresse" || h == "endereco" -> AddressType.HOME
            else -> AddressType.OTHER
        }
    }

    private fun parseText(t: String): Result {
        val profiles = ArrayList<Draft>(); val snippets = ArrayList<Snippet>(); val ids = ArrayList<ParsedId>(); val skipped = ArrayList<String>()
        var cur: Draft? = null; var addr: AutofillAddress? = null; var snippet: Pair<String, StringBuilder>? = null; var lastId = -1
        val idOwners = ArrayList<Draft?>()
        fun flushAddr() { val a = addr ?: return; if (!a.isEmpty) cur?.addresses?.add(a); addr = null }
        fun flushSnippet() { val s = snippet ?: return; if (s.second.isNotBlank()) snippets += Snippet(label = s.first, text = s.second.toString().trimEnd()); snippet = null }
        fun draft(): Draft = cur ?: Draft("").also { cur = it; profiles += it }

        for (raw in t.lines()) {
            val line = raw.trimEnd()
            val colon = line.indexOf(':')
            val key = if (colon > 0) norm(line.substring(0, colon)) else ""
            val value = if (colon > 0) line.substring(colon + 1).trim() else line.trim()
            val baseKey = key.substringBefore('(').trim()
            if (snippet != null) {
                if (line.trim() == "---" || baseKey in listOf("profile", "perfil", "profil", "snippet", "texto", "textbaustein")) flushSnippet()
                else { snippet!!.second.append(raw).append('\n'); continue }
                if (line.trim() == "---") continue
            }
            if (line.isBlank()) continue
            when {
                baseKey in listOf("profile", "perfil", "profil") -> { flushAddr(); cur = Draft(value).also { profiles += it } }
                baseKey in listOf("snippet", "texto", "textbaustein") -> { flushAddr(); snippet = value to StringBuilder() }
                baseKey in listOf("address", "direccion", "domicilio", "adresse", "anschrift", "postanschrift", "endereco", "postfiliale", "packstation", "c/o address") -> {
                    flushAddr(); draft(); addr = AutofillAddress(type = addressType(key + " " + value.substringBefore(' ')), label = value)
                }
                ID_TYPES.any { it.second.containsMatchIn(key) } && colon > 0 -> {
                    val type = ID_TYPES.first { it.second.containsMatchIn(key) }.first
                    val country = Regex("\\(([^)]*)\\)").find(key)?.groupValues?.get(1)?.trim()?.uppercase().orEmpty()
                    ids += ParsedId(type, value, country); idOwners += cur; lastId = ids.size - 1
                }
                lookup(ID_DETAILS, baseKey) != null && lastId >= 0 -> {
                    val d = lookup(ID_DETAILS, baseKey)!!; ids[lastId] = ids[lastId].copy(details = ids[lastId].details + (d to value))
                }
                colon > 0 && Regex("^(phone|tel|telefono|telefon|handy|movil|mobile|celular|telefone)").containsMatchIn(baseKey) -> {
                    val opts = Regex("\\(([^)]*)\\)").find(key)?.groupValues?.get(1).orEmpty().split(',').map { it.trim() }
                    val type = opts.firstOrNull { it in listOf("mobile", "landline", "work", "movil", "fijo", "festnetz", "handy") }?.let {
                        when (it) { "movil", "handy" -> "mobile"; "fijo", "festnetz" -> "landline"; else -> it } } ?: if (Regex("handy|movil|mobile|celular").containsMatchIn(baseKey)) "mobile" else ""
                    val country = opts.firstOrNull { it.length == 2 }?.uppercase().orEmpty()
                    draft().contacts += AutofillContact(kind = ContactKind.TEL, value = value, type = type, country = country)
                }
                colon > 0 && Regex("^(e-?mail|correo|courriel)").containsMatchIn(baseKey) -> draft().contacts += AutofillContact(kind = ContactKind.EMAIL, value = value, type = "personal")
                colon > 0 && Regex("^(link|web|website|url|homepage|pagina|sitio)").containsMatchIn(baseKey) -> draft().contacts += AutofillContact(kind = ContactKind.URL, value = value)
                colon > 0 && lookup(PROFILE_KEYS, baseKey) != null && addr == null -> draft().fields[lookup(PROFILE_KEYS, baseKey)!!] = value
                colon > 0 && lookup(ADDRESS_KEYS, baseKey) != null -> {
                    if (addr == null) { draft(); addr = AutofillAddress(type = AddressType.HOME) }
                    val c = lookup(ADDRESS_KEYS, baseKey)!!
                    addr = addr!!.copy(fields = addr!!.fields + (c to if (c == "country") value.uppercase() else value))
                }
                colon > 0 && lookup(PROFILE_KEYS, baseKey) != null -> draft().fields[lookup(PROFILE_KEYS, baseKey)!!] = value
                EMAIL.matches(value) -> draft().contacts += AutofillContact(kind = ContactKind.EMAIL, value = value, type = "personal")
                URL.matches(value) -> draft().contacts += AutofillContact(kind = ContactKind.URL, value = value)
                PHONE.matches(value) -> draft().contacts += AutofillContact(kind = ContactKind.TEL, value = value)
                else -> skipped += line
            }
        }
        flushAddr(); flushSnippet()
        val built = profiles.mapIndexed { i, d -> d.build(i == 0) }.filterNot { it.isEmpty }.map { p ->
            if (p.label.isNotBlank()) p else p.copy(label = p.fullName.ifBlank { "Imported" })
        }
        return Result(built, snippets, ids.mapIndexed { i, id -> idOwners[i]?.fields?.let { id.withHolder(it) } ?: id }, skipped)
    }

    /** The holder's names from a profile's [fields] (AutofillSot profile keys). */
    private fun ParsedId.withHolder(fields: Map<String, String>): ParsedId = copy(
        givenName = fields["given_name"].orEmpty().trim(),
        middleName = fields["additional_name"].orEmpty().trim(),
        familyName = listOf(fields["family_name"], fields["family_name2"]).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" "),
    )

    // ── JSON ─────────────────────────────────────────────────────────────

    /** The JSON shape [parse] reads. */
    val EXAMPLE_JSON = """{"profiles":[{"label":"Testy - Germany-A","given_name":"Testy","family_name":"Fakeson",
        "addresses":[{"type":"postal","street":"Musterstraße","house_number":"1","postal_code":"00000","city":"Musterstadt","country":"DE"}],
        "phones":[{"value":"+00 100 000000","type":"mobile","country":"DE"}],"emails":["testy@example.invalid"],"links":["https://example.invalid"]}],
        "snippets":[{"label":"About me","text":"Line one\nLine two"}],"ids":[{"type":"Personalausweis","number":"X0000000","country":"DE"}]}"""

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optJSONObject(it)?.optString("value") ?: optString(it) }

    private fun parseJson(o: JSONObject): Result {
        val ids = ArrayList<ParsedId>()
        fun idsOf(a: JSONArray?, holder: JSONObject? = null) = a.objects().forEach {
            val id = ParsedId(it.optString("type"), it.optString("number"), it.optString("country").uppercase(),
                listOf("issued", "valid_until", "support", "issuing_country").filter { k -> it.has(k) }.associateWith { k -> it.optString(k) })
            ids += holder?.let { h -> id.withHolder(AutofillSot.PROFILE_FIELDS.associateWith { k -> h.optString(k) }) } ?: id
        }
        val profiles = o.optJSONArray("profiles").objects().mapIndexed { i, p ->
            idsOf(p.optJSONArray("ids"), holder = p)
            AutofillProfile(label = p.optString("label"), isDefault = p.optBoolean("is_default", i == 0),
                fields = AutofillSot.PROFILE_FIELDS.filter { p.optString(it).isNotBlank() }.associateWith { p.optString(it).trim() },
                addresses = p.optJSONArray("addresses").objects().mapIndexed { j, a ->
                    AutofillAddress(type = a.optString("type").ifBlank { AddressType.HOME }.let { if (it in AddressType.ALL) it else AddressType.OTHER },
                        label = a.optString("label"), isDefault = a.optBoolean("is_default", j == 0),
                        fields = AutofillSot.ADDRESS_FIELDS.filter { a.optString(it).isNotBlank() }.associateWith { a.optString(it).trim() })
                },
                contacts = p.optJSONArray("phones").let { arr -> arr.objects().ifEmpty { arr.strings().map { s -> JSONObject().put("value", s) } } }.mapIndexed { j, c ->
                    AutofillContact(kind = ContactKind.TEL, value = c.optString("value"), type = c.optString("type"), country = c.optString("country").uppercase(), isDefault = j == 0)
                } + p.optJSONArray("emails").strings().mapIndexed { j, e -> AutofillContact(kind = ContactKind.EMAIL, value = e, isDefault = j == 0) } +
                    p.optJSONArray("links").strings().mapIndexed { j, u -> AutofillContact(kind = ContactKind.URL, value = u, isDefault = j == 0) },
            )
        }.filterNot { it.isEmpty }
        idsOf(o.optJSONArray("ids"))
        val snippets = o.optJSONArray("snippets").objects().map { Snippet(label = it.optString("label"), text = it.optString("text")) }.filter { it.text.isNotBlank() }
        return Result(profiles, snippets, ids, emptyList())
    }
}
