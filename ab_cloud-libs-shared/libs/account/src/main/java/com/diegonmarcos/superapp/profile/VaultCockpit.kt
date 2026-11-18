package com.diegonmarcos.superapp.profile

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.account.BuildConfig
import com.diegonmarcos.superapp.appstore.AppInventory
import com.diegonmarcos.superapp.mail.JmapPrefs
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.texttools.TextToolsClient
import com.diegonmarcos.superapp.ui.StatusLight
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader

/**
 * Configs ▸ Profile ▸ Fleet (#570): the vault bundle as the fleet configurator.
 *
 * Every section here reads ONE thing — the bundle [VaultConnect.fetch] returned
 * — and compares it with what this device holds. The section list, its labels
 * and which vault sections feed each one are `build.json::ui.vault_connect.cockpit`
 * (baked to [BuildConfig.UI_VAULT_CONNECT_COCKPIT_B64]); nothing in here restates
 * the vault's schema, and a vault section this build has no cockpit for still
 * shows up raw in the Fleet tab.
 *
 * THE DEVICE IS CHOSEN, NEVER TYPED. The owner picks WHICH of the vault's
 * declared devices (the `electronics` section: every entry carrying a
 * `wg_peer`) this phone is. Its mesh identity — address, key, profiles —
 * derives from that declaration: a mesh profile belongs to the device whose
 * WireGuard address appears on the profile's own `Address` line. No IP, key or
 * hostname is ever entered by hand on this screen.
 *
 * NOTHING IS APPLIED BY RENDERING. Every `apply*` runs on a tap on its own
 * section's button, and the render path only reads. The testers pin that.
 */
object VaultCockpit {

    // ── layout: build.json::ui.vault_connect.cockpit ─────────────────────

    /**
     * [icon]: the drawable name the card's round badge shows. [observed] false
     * marks a section whose device side this app cannot read at all, so its
     * light is [StatusLight.State.UNVERIFIABLE] rather than a colour it could
     * not justify — see the `_doc_cards` note beside the declaration.
     */
    data class Section(
        val id: String, val label: String, val vault: List<String>,
        val icon: String = "", val observed: Boolean = true,
        /** #695 `fields`: device field → vault key, for a section compared field by field (about). */
        val fields: Map<String, String> = emptyMap(),
        /** #713 `apply`: which applier draws this app's section — the ONLY thing Setup dispatches on.
         *  Blank or unknown = the section's vault data shown raw, never an invented applier. */
        val apply: String = "",
        /** #778 `runtime`: who serves this app's live config and what Account ▸ Runtime may do with it. */
        val runtime: Runtime = Runtime(),
    )

    /**
     * #778 An app's runtime as Account ▸ Runtime reads it (build.json cockpit `runtime`):
     * [servedBy] `self` (this app's own stores), `text_tools` (the ITextTools serving app) or the
     * package that holds it; [reports] false = that app exposes nothing to read (the keyboard's
     * lists); [writable] false = its value cannot be written back as a declaration (a running
     * tunnel is not a wg-quick text); [fields] false = it reports a summary, not fields (apps).
     * #781 [why]: the reason a `reports` false app says nothing; [lists]: the keyboard's vault
     * autocomplete key → the tab file of its clipboard export.
     * #789 [store]: the secret store file an app reports through its own #783 FleetConfig export
     * (cloud-drive's git-sync-credentials) — shown as presence + fingerprint.
     */
    data class Runtime(val servedBy: String = SELF, val reports: Boolean = true,
                       val writable: Boolean = true, val fields: Boolean = true,
                       val why: String = "", val lists: Map<String, String> = emptyMap(),
                       val store: String = "",
                       /** #573 `apply_all`: the Runtime card offers ONE button that applies the whole declared
                        *  section as a unit (every mesh profile, every mail account) — [AccountRuntime.applyAll]. */
                       val applyAll: Boolean = false)

    /** #781 One Profiles field's runtime mapping (cockpit `vault_fields`): the app ids that use it,
     *  whether they HOLD it live ([held]: Runtime reads it), and [why] when no app does or none holds it. */
    data class VaultField(val apps: List<String>, val held: Boolean, val why: String = "",
        /** #810 why a held field may go unread on a given phone (e.g. another peer's profiles). */
        val unreadWhy: String = "")

    /** [aiTokens]: vault `ai.tokens.<item>` → the device provider id the token feeds.
     *  [deviceIcons]: electronics `type` → the hero orb's drawable; `_default` for the rest.
     *  [journeyIcons]: the Connect journey's step (#573, `sign_in`/`who`/`device`/`get`) → its badge's drawable. */
    data class Layout(
        val sections: List<Section>, val aiTokens: Map<String, String>,
        val deviceIcons: Map<String, String> = emptyMap(),
        val journeyIcons: Map<String, String> = emptyMap(),
        /** #781 `vault_fields`: every Profiles field (`section › field`) → its [VaultField], in schema order. */
        val vaultFields: Map<String, VaultField> = emptyMap(),
        /** #790 `agent_auth`: the terminals' credentials store, derived from the declared vault values. */
        val agentAuth: AgentAuth? = null,
        /** #802 `derived_settings`: more app stores derived the same way (the browser's autofill profile). */
        val derivedSettings: List<AgentAuth> = emptyList(),
        /** #573 `mesh_default_profile`: the profile name Apply all makes the active tunnel (config-v4-split). */
        val meshDefault: String = "",
    ) {
        /** Every derivation, agent_auth first. */
        val derivations: List<AgentAuth> get() = listOfNotNull(agentAuth) + derivedSettings
    }

    /**
     * #790 cockpit `agent_auth`: each fleet app in [apps] gets `settings › app › [store] › NAME` =
     * the declared value at [env]'s vault path for NAME (an environment variable an agent CLI reads),
     * so the terminals' credentials are applied over FleetConfig like any other app setting
     * ([AccountFleet.derive]).
     */
    data class AgentAuth(val store: String, val apps: List<String>, val env: Map<String, List<String>>)

    /** One `{store, apps, env|keys: {KEY: [vault path]}}` block (agent_auth, or a derived_settings entry). */
    private fun parseDerivation(a: JSONObject): AgentAuth {
        val apps = a.optJSONArray("apps") ?: JSONArray()
        val env = a.optJSONObject("keys") ?: a.optJSONObject("env") ?: JSONObject()
        return AgentAuth(a.optString("store"), (0 until apps.length()).map { apps.getString(it) },
            env.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { k ->
                env.getJSONArray(k).let { p -> (0 until p.length()).map { p.getString(it) } }
            })
    }

    fun parseLayout(o: JSONObject): Layout {
        val arr = o.optJSONArray("sections") ?: JSONArray()
        val sections = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val v = s.optJSONArray("vault") ?: JSONArray()
            val f = s.optJSONObject("fields") ?: JSONObject()
            Section(s.getString("id"), s.getString("label"), (0 until v.length()).map { v.getString(it) },
                s.optString("icon"), s.optBoolean("observed", true),
                f.keys().asSequence().associateWith { f.getString(it) }, s.optString("apply"),
                s.optJSONObject("runtime").let { r ->
                    val lists = r?.optJSONObject("lists") ?: JSONObject()
                    Runtime(r?.optString("served_by")?.ifBlank { null } ?: SELF, r?.optBoolean("reports", true) ?: true,
                        r?.optBoolean("writable", true) ?: true, r?.optBoolean("fields", true) ?: true,
                        r?.optString("why").orEmpty(), lists.keys().asSequence().associateWith { lists.getString(it) },
                        r?.optString("store").orEmpty(), r?.optBoolean("apply_all", false) ?: false)
                })
        }
        val tokens = o.optJSONObject("ai_tokens") ?: JSONObject()
        val icons = o.optJSONObject("device_icons") ?: JSONObject()
        val journey = o.optJSONObject("journey_icons") ?: JSONObject()
        val vf = o.optJSONObject("vault_fields") ?: JSONObject()
        return Layout(
            sections,
            tokens.keys().asSequence().associateWith { tokens.getString(it) },
            icons.keys().asSequence().associateWith { icons.getString(it) },
            journey.keys().asSequence().associateWith { journey.getString(it) },
            vf.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { k ->
                val e = vf.getJSONObject(k)
                val apps = e.optJSONArray("apps") ?: JSONArray()
                VaultField((0 until apps.length()).map { apps.getString(it) }, e.optBoolean("held", true), e.optString("why"), e.optString("unread_why"))
            },
            o.optJSONObject("agent_auth")?.let { parseDerivation(it) },
            o.optJSONArray("derived_settings").let { a ->
                if (a == null) emptyList() else (0 until a.length()).map { parseDerivation(a.getJSONObject(it)) }
            },
            o.optString("mesh_default_profile"),
        )
    }

    /** The hero orb's drawable name for [device] (null = nothing chosen yet):
     *  its declared type's entry, else `_default`, else blank (the icon lookup's own fallback). */
    fun deviceIcon(layout: Layout, device: Device?): String =
        layout.deviceIcons[device?.type.orEmpty()] ?: layout.deviceIcons[DEVICE_ICON_DEFAULT] ?: ""

    val layout: Layout by lazy {
        runCatching {
            parseLayout(JSONObject(String(Base64.decode(BuildConfig.UI_VAULT_CONNECT_COCKPIT_B64, Base64.DEFAULT))))
        }.getOrDefault(Layout(emptyList(), emptyMap()))
    }

    // ── the device this phone is ─────────────────────────────────────────

    /** [type]: the entry's declared kind (notebook, phone, …), as the vault spells it. */
    /** [publicKey]: the entry's declared `wg_public_key` (the device's OWN identity, "" when the vault
     *  does not carry it) — what every key an apply would use is checked against ([meshKey]). */
    data class Device(val id: String, val label: String, val wgIp: String, val wgIpv6: String, val type: String = "",
                      val publicKey: String = "")

    /**
     * Every device the vault's `electronics` section declares with a `wg_peer`
     * (a fleet WireGuard client: name, wg_ip, wg_ipv6). Groups (computers,
     * phones, …) are walked, not named. A pending group or entry is simply not
     * a selectable device.
     */
    fun devices(bundle: JSONObject): List<Device> {
        val out = mutableListOf<Device>()
        val electronics = bundle.optJSONObject("electronics")
        electronics?.keys()?.forEach { group ->
            val g = electronics.optJSONObject(group) ?: return@forEach
            g.keys().forEach { id ->
                val entry = g.optJSONObject(id) ?: return@forEach
                val peer = entry.optJSONObject("wg_peer") ?: return@forEach
                if (peer.optBoolean("pending")) return@forEach
                val ip = peer.optString("wg_ip")
                if (ip.isBlank()) return@forEach
                // #766 the device's own label (the vault carries it since 11950b4), else the tunnel's client name.
                out += Device(id, entry.optString("label").ifBlank { peer.optString("name") }.ifBlank { id }, ip, peer.optString("wg_ipv6"),
                    (entry.opt("type") as? String).orEmpty(), (entry.opt("wg_public_key") as? String).orEmpty().trim())
            }
        }
        // #573: the vault's peers section (`peers.<id>.wg0`, one entry per
        // device of the ONE user declaration) declares devices the same way;
        // an id already known from electronics is not listed twice.
        val peers = bundle.optJSONObject("peers")
        peers?.keys()?.forEach { id ->
            val entry = peers.optJSONObject(id) ?: return@forEach
            val wg0 = entry.optJSONObject("wg0") ?: return@forEach
            if (wg0.optBoolean("pending")) return@forEach
            val ip = wg0.optString("wg_ip")
            if (ip.isBlank() || out.any { it.id == id }) return@forEach
            out += Device(id, wg0.optString("name").ifBlank { id }, ip, wg0.optString("wg_ipv6"), "")
        }
        return out
    }

    fun selectedDevice(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_DEVICE, "") ?: ""

    fun selectDevice(ctx: Context, id: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_DEVICE, id).apply()

    // ── rows ─────────────────────────────────────────────────────────────

    enum class State { MATCH, DIFFERS, ABSENT, PENDING }

    /** One comparison: what the vault declares against what the device holds. */
    /** [observed] false (#713): this app cannot read that item's device side, so it reads "not
     *  verifiable" whatever its section — never a guessed tick. */
    data class Row(val label: String, val declared: String, val device: String, val state: State, val observed: Boolean = true)

    /**
     * One card's light, from its rows, through the SHARED [StatusLight] states.
     *
     * A section this app cannot observe is UNVERIFIABLE whatever the rows say.
     * Otherwise a single row that differs or is absent turns the card OFF — a
     * card is configured only when everything on it is — a match with nothing
     * against it is ON, and rows that are all pending in the vault are UNKNOWN,
     * because nobody can currently say. No rows at all is UNKNOWN too.
     */
    fun sectionLight(rows: List<Row>, observed: Boolean = true): StatusLight.State = when {
        !observed -> StatusLight.State.UNVERIFIABLE
        rows.isEmpty() -> StatusLight.State.UNKNOWN
        rows.any { it.state == State.DIFFERS || it.state == State.ABSENT } -> StatusLight.State.OFF
        rows.any { it.state == State.MATCH } -> StatusLight.State.ON
        else -> StatusLight.State.UNKNOWN
    }

    /** The hero's light over every card's: any OFF is OFF, all ON is ON, else nobody can say. */
    fun overallLight(lights: Collection<StatusLight.State>): StatusLight.State = when {
        lights.isEmpty() -> StatusLight.State.UNKNOWN
        lights.any { it == StatusLight.State.OFF } -> StatusLight.State.OFF
        lights.all { it == StatusLight.State.ON } -> StatusLight.State.ON
        else -> StatusLight.State.UNKNOWN
    }

    /** Row counts for a card's summary line: matching, differing (absent counts as differing), pending. */
    data class Tally(val match: Int, val differ: Int, val pending: Int)

    fun tally(rows: List<Row>) = Tally(
        rows.count { it.state == State.MATCH },
        rows.count { it.state == State.DIFFERS || it.state == State.ABSENT },
        rows.count { it.state == State.PENDING },
    )

    private fun pending(v: Any?) = v is JSONObject && v.optBoolean("pending")
    private fun pendingText(v: JSONObject) = "pending · ${v.optString("source")} · ${v.optString("reason")}"

    // ── mesh ─────────────────────────────────────────────────────────────

    /** `Address = a, b, c` of a wg-quick text, as its bare addresses (no prefix length). */
    private fun addressesOf(conf: String): List<String> =
        conf.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Address", ignoreCase = true) && it.contains('=') }
            ?.substringAfter('=')?.split(',')?.map { it.trim().substringBefore('/') }
            .orEmpty()

    /**
     * The mesh profiles that are [device]'s: every entry of `mesh.profiles`
     * whose Address line carries the device's declared wg_ip or wg_ipv6.
     * Ownership is derived from the two declarations, never from a file name.
     */
    fun meshProfiles(bundle: JSONObject, device: Device): Map<String, String> {
        val mine = setOf(device.wgIp, device.wgIpv6).filter { it.isNotBlank() }
        val candidates = mutableMapOf<String, String>()
        bundle.optJSONObject("mesh")?.optJSONObject("profiles")?.let { profiles ->
            profiles.keys().forEach { name -> (profiles.opt(name) as? String)?.let { candidates[name] = it } }
        }
        // #573: `mesh.devices.<id>.profiles` files a second phone's four profiles under its own
        // id (mesh/sources.json `devices`); the same rule applies — a profile is the device's by
        // its Address line. Keyed `devices/<id>/<name>` so a name shared with mesh.profiles survives.
        bundle.optJSONObject("mesh")?.optJSONObject("devices")?.let { devs ->
            devs.keys().forEach { id ->
                val profiles = devs.optJSONObject(id)?.optJSONObject("profiles") ?: return@forEach
                profiles.keys().forEach { name -> (profiles.opt(name) as? String)?.let { candidates.putIfAbsent("devices/$id/$name", it) } }
            }
        }
        // #573: the peers section files each phone's profiles under its own id;
        // the same rule applies — a profile is the device's by its Address line.
        bundle.optJSONObject("peers")?.let { peers ->
            peers.keys().forEach { id ->
                val profiles = peers.optJSONObject(id)?.optJSONObject("profiles") ?: return@forEach
                profiles.keys().forEach { name -> (profiles.opt(name) as? String)?.let { candidates.putIfAbsent("$id/$name", it) } }
            }
        }
        // One profile per TEXT: the vault may declare a device's profiles under two trees (the mesh
        // section's devices/<id>/… and the peers section's <id>/…); the same conf twice would be
        // two selectable rows of the same name, and "8 profiles stored" for four.
        val seen = HashSet<String>()
        return candidates.filter { (_, conf) -> addressesOf(conf).any { it in mine } && seen.add(conf.trim()) }.toSortedMap()
    }

    /** What this device's tunnel is right now, for the comparison column. */
    data class TunnelState(val name: String, val address: String, val peerKeys: Set<String>)

    fun meshRows(bundle: JSONObject, device: Device, current: TunnelState): List<Row> =
        meshProfiles(bundle, device).map { (name, conf) ->
            val cfg = runCatching { Config.parse(BufferedReader(StringReader(conf))) }.getOrNull()
            val declaredAddress = cfg?.getInterface()?.addresses?.joinToString(", ") ?: addressesOf(conf).joinToString(", ")
            val declaredKeys = cfg?.peers?.map { it.publicKey.toBase64() }?.toSet() ?: emptySet()
            val same = declaredAddress == current.address && declaredKeys == current.peerKeys
            Row(name, "$declaredAddress · ${declaredKeys.size} peers",
                "${current.name.ifBlank { "—" }} · ${current.address.ifBlank { "no address" }} · ${current.peerKeys.size} peers",
                if (same) State.MATCH else State.DIFFERS)
        }

    /** The PrivateKey value a profile text carries, or null for none / a `<PROVIDED_BY_DEVICE>` marker. */
    fun privateKeyOf(conf: String): String? =
        Regex("(?m)^\\s*PrivateKey\\s*=\\s*(\\S+)").find(conf)?.groupValues?.get(1)?.takeIf { !it.startsWith("<") }

    /** Which private key Apply all may use for [device] — and when it may use none. */
    sealed class MeshKey {
        /** The vault's profiles carry the device's own key: store it as the phone's. */
        data class FromVault(val privateKey: String) : MeshKey()
        /** The profiles carry no key; the phone's own key IS the device's: keep it. */
        object FromDevice : MeshKey()
        /** No key may be used; [why] says exactly what is missing and the path that exists. */
        data class Refused(val why: String) : MeshKey()
    }

    /**
     * THE KEY RULE (#573, the A37 addendum): a WireGuard private key names ONE device, so the
     * key an apply stores must be the chosen device's and never another peer's. [device].publicKey
     * is the identity the vault declares for it; [profiles] are its declared profiles (their
     * PrivateKey is either the real key or `<PROVIDED_BY_DEVICE>`); [phonePublicKey] is the public
     * half of what the phone holds ("" for none); [derive] gives a private key's public half (null
     * when the text is not a key). Nothing here invents a key source: when neither the bundle nor
     * the phone holds the device's key, the answer names the Generate / import path that exists.
     */
    fun meshKey(device: Device, profiles: Map<String, String>, phonePublicKey: String, derive: (String) -> String?): MeshKey {
        val carried = profiles.values.mapNotNull { privateKeyOf(it) }.distinct()
        val declared = device.publicKey
        if (carried.size > 1) return MeshKey.Refused("✗ ${device.label}: its profiles disagree on the private key — fix the vault; nothing written")
        carried.singleOrNull()?.let { key ->
            val pub = derive(key) ?: return MeshKey.Refused("✗ ${device.label}: the profiles' PrivateKey is not a valid key; nothing written")
            if (declared.isNotBlank() && pub != declared)
                return MeshKey.Refused("✗ ${device.label}: the key in the vault profiles is NOT this device's (declared public key ${declared.take(8)}…, profiles' ${pub.take(8)}…) — another peer's key is never reused; nothing written")
            return MeshKey.FromVault(key)
        }
        if (phonePublicKey.isBlank())
            return MeshKey.Refused("✗ ${device.label}: no private key — the bundle's profiles carry <PROVIDED_BY_DEVICE> and this phone holds none. Configs ▸ Mesh ▸ Generate keypair (or Import .conf / paste), then register the public key as ${device.label}'s peer; nothing written")
        if (declared.isNotBlank() && phonePublicKey != declared)
            return MeshKey.Refused("✗ ${device.label}: this phone's key is not ${device.label}'s (phone ${phonePublicKey.take(8)}…, declared ${declared.take(8)}…) — import ${device.label}'s key on Configs ▸ Mesh, or register the phone's public key as its peer; nothing written")
        return MeshKey.FromDevice
    }

    /** The name in [profiles] Apply all activates: the layout's `mesh_default_profile` (config-v4-split),
     *  matched on the profile's own name whatever prefix files it; else the first. */
    fun meshActive(profiles: Map<String, String>, default: String): String? =
        profiles.keys.firstOrNull { default.isNotBlank() && it.substringAfterLast('/') == default } ?: profiles.keys.firstOrNull()

    // The mesh tunnel's own state and apply live with the host (AccountHost.mesh): the tunnel's prefs are the host's.

    // ── mail ─────────────────────────────────────────────────────────────

    data class MailDeclared(val account: String, val email: String, val password: String?, val domain: String)

    /**
     * The vault mail account for [email]: `mail.accounts` is the Stalwart
     * users map (key → {name, pass_env}); the one whose `name` is the local
     * part of the address is this device's, and its password is
     * `mail.passwords[pass_env]`. No match → null, and the section says so.
     */
    fun mailDeclared(bundle: JSONObject, email: String): MailDeclared? {
        val mail = bundle.optJSONObject("mail") ?: return null
        val local = email.substringBefore('@').trim()
        if (local.isBlank()) return null
        val accounts = mail.optJSONObject("accounts") ?: return null
        val key = accounts.keys().asSequence()
            .firstOrNull { accounts.optJSONObject(it)?.optString("name") == local } ?: return null
        val passEnv = accounts.getJSONObject(key).optString("pass_env")
        val pw = mail.optJSONObject("passwords")?.opt(passEnv)
        return MailDeclared(
            account = key,
            email = email,
            password = (pw as? String)?.takeIf { it.isNotBlank() },
            domain = mail.optJSONObject("endpoints")?.optString("domain").orEmpty(),
        )
    }

    /**
     * #695 EVERY account `mail.accounts` declares, addressed at [domain] (the
     * vault names local parts; the domain is the signed-in address's). Empty when
     * no domain is known yet, so no address is ever composed from a guess.
     */
    fun mailAccounts(bundle: JSONObject, domain: String): List<MailDeclared> {
        val mail = bundle.optJSONObject("mail") ?: return emptyList()
        if (domain.isBlank()) return emptyList()
        val accounts = mail.optJSONObject("accounts") ?: return emptyList()
        val passwords = mail.optJSONObject("passwords")
        val host = mail.optJSONObject("endpoints")?.optString("domain").orEmpty()
        return accounts.keys().asSequence().mapNotNull { key ->
            val a = accounts.optJSONObject(key) ?: return@mapNotNull null
            val local = a.optString("name").trim()
            if (local.isBlank()) return@mapNotNull null
            val pw = passwords?.opt(a.optString("pass_env"))
            MailDeclared(key, "$local@$domain", (pw as? String)?.takeIf { it.isNotBlank() }, host)
        }.toList()
    }

    /** `mail.endpoints` as hosts: JMAP is `domain`; IMAP and SMTP are the `l4_ports` SNI names
     *  (the entries whose sni starts with imap / smtp), "" when the vault names none. */
    data class MailEndpoints(val jmap: String, val imap: String, val smtp: String)

    fun mailEndpoints(bundle: JSONObject): MailEndpoints {
        val e = bundle.optJSONObject("mail")?.optJSONObject("endpoints") ?: return MailEndpoints("", "", "")
        val ports = e.optJSONArray("l4_ports") ?: JSONArray()
        fun sni(prefix: String) = (0 until ports.length()).map { ports.optJSONObject(it)?.optString("sni").orEmpty() }
            .firstOrNull { it.startsWith(prefix) }.orEmpty()
        return MailEndpoints(e.optString("domain"), sni("imap"), sni("smtp"))
    }

    /**
     * #573 EVERY declared account into the mail store, with its password and the endpoints:
     * [prefs] keeps the list ([JmapPrefs.saveAccounts]) and its active login stays the owner's
     * ([owner]'s address when declared, else the first). The JMAP server URL is still not
     * written (see [applyMail]). Returns the report, one line per account.
     */
    fun applyMailAll(prefs: JmapPrefs, accounts: List<MailDeclared>, endpoints: MailEndpoints, owner: String): String {
        if (accounts.isEmpty()) return "✗ no declared mail account at a known domain"
        val stored = accounts.map { JmapPrefs.Account(it.email, it.password.orEmpty(), endpoints.jmap, endpoints.imap, endpoints.smtp) }
        val active = accounts.firstOrNull { it.email == owner } ?: accounts.first()
        prefs.saveAccounts(stored, active.email)
        return accounts.joinToString("\n") { d ->
            "✓ ${d.email}" + (if (d.password == null) " (no password in the vault)" else "") + (if (d.email == active.email) " · active" else "")
        } + "\n✓ endpoints jmap ${endpoints.jmap.ifBlank { "—" }} · imap ${endpoints.imap.ifBlank { "—" }} · smtp ${endpoints.smtp.ifBlank { "—" }}"
    }

    /** One item per declared account against [deviceEmail], the address the
     *  device's mail holds: applied when it is that one, absent when it holds
     *  none, differs otherwise. Never a password. */
    fun mailAccountRows(accounts: List<MailDeclared>, deviceEmail: String): List<Row> = accounts.map { d ->
        Row(d.email, if (d.password == null) "password not in the vault" else "password in the vault",
            deviceEmail.ifBlank { "—" },
            when {
                deviceEmail == d.email -> State.MATCH
                deviceEmail.isBlank() -> State.ABSENT
                else -> State.DIFFERS
            })
    }

    fun mailRows(d: MailDeclared?, prefs: JmapPrefs): List<Row> {
        if (d == null) return emptyList()
        val emailState = if (prefs.email == d.email) State.MATCH else if (prefs.email.isBlank()) State.ABSENT else State.DIFFERS
        val pwState = when {
            d.password == null -> State.PENDING
            prefs.password.isBlank() -> State.ABSENT
            prefs.password == d.password -> State.MATCH
            else -> State.DIFFERS
        }
        return listOf(
            Row("account", "${d.account} → ${d.email}", prefs.email.ifBlank { "—" }, emailState),
            Row("password", if (d.password == null) "not in the vault" else "•••• (${d.password.length} chars)",
                if (prefs.password.isBlank()) "—" else "•••• stored", pwState),
            Row("jmap host", d.domain.ifBlank { "—" }, prefs.server.ifBlank { "—" },
                if (d.domain.isNotBlank() && prefs.server.contains(d.domain)) State.MATCH else State.DIFFERS),
        )
    }

    /** Writes the address and, when the vault holds one, the password. The
     *  JMAP server URL is left alone: the vault declares a host, not a
     *  session path, and inventing one here is how a wrong URL gets stored. */
    fun applyMail(prefs: JmapPrefs, d: MailDeclared): String {
        prefs.email = d.email
        if (d.password != null) prefs.password = d.password
        return "✓ ${d.email} applied" + if (d.password == null) " (no password in the vault)" else ""
    }

    // ── about: the contact card (#695) ───────────────────────────────────

    /**
     * `about.profile` against the device's contact card, one row per declared
     * field ([fields]: device field → vault key). [device] answers a field's
     * current value, or null for a field the device does not have — which is a
     * PENDING row that says so, never a silent skip.
     */
    fun aboutRows(bundle: JSONObject, fields: Map<String, String>, device: (String) -> String?): List<Row> {
        val profile = bundle.optJSONObject("about")?.optJSONObject("profile") ?: return emptyList()
        return fields.map { (field, key) ->
            val v = profile.optString(key).trim()
            val d = device(field)?.trim()
            val state = when {
                d == null -> State.PENDING
                v.isBlank() -> State.PENDING
                d.isBlank() -> State.ABSENT
                d == v -> State.MATCH
                else -> State.DIFFERS
            }
            Row(field, v.ifBlank { "not in the vault" }, d?.ifBlank { "—" } ?: "no such field on this device", state)
        }
    }

    /** Writes every declared field the vault carries through [set]; returns the fields written. */
    fun applyAbout(bundle: JSONObject, fields: Map<String, String>, set: (String, String) -> Boolean): List<String> {
        val profile = bundle.optJSONObject("about")?.optJSONObject("profile") ?: return emptyList()
        return fields.mapNotNull { (field, key) ->
            val v = profile.optString(key).trim()
            if (v.isNotBlank() && set(field, v)) field else null
        }
    }

    // ── keyboard & clipboards ────────────────────────────────────────────

    /** `autocomplete.<list>`: a list is a JSON array of strings once exported;
     *  until then it is a pending marker. The keyboard owns its lists and this
     *  app cannot read them, so the device column states exactly that. */
    fun keyboardRows(bundle: JSONObject, deviceText: String): List<Row> {
        val auto = bundle.optJSONObject("autocomplete") ?: return emptyList()
        return auto.keys().asSequence().map { k ->
            val v = auto.opt(k)
            when {
                pending(v) -> Row(k, pendingText(v as JSONObject), deviceText, State.PENDING)
                v is JSONArray -> Row(k, "${v.length()} entries", deviceText, State.ABSENT)
                else -> Row(k, v.toString().take(80), deviceText, State.ABSENT)
            }
        }.toList()
    }

    // ── drive (private repos) ────────────────────────────────────────────

    fun driveRows(bundle: JSONObject, prefs: ConfigsPrefs, deviceRepos: String): List<Row> {
        val git = bundle.optJSONObject("git") ?: return emptyList()
        fun secretRow(label: String, item: String, section: String, key: String): Row {
            val v = git.opt(item)
            val stored = prefs.secret(section, key)
            return when {
                pending(v) -> Row(label, pendingText(v as JSONObject), if (stored.isBlank()) "—" else "•••• stored", State.PENDING)
                v !is String || v.isBlank() -> Row(label, "not in the vault", if (stored.isBlank()) "—" else "•••• stored", State.PENDING)
                stored.isBlank() -> Row(label, "•••• (${v.length} chars)", "—", State.ABSENT)
                stored.trim() == v.trim() -> Row(label, "•••• (${v.length} chars)", "•••• stored", State.MATCH)
                else -> Row(label, "•••• (${v.length} chars)", "•••• stored, different", State.DIFFERS)
            }
        }
        // #713 one item per declared repo. Cloud Drive clones them itself (Sync ▸ Git) and
        // this app cannot see its folders, so each is "not verifiable" — never the green
        // tick the whole list used to get for merely being declared.
        val repos = git.optJSONArray("repos")
        val repoRows = when {
            repos != null -> (0 until repos.length()).map { i ->
                val r = repos.opt(i)
                Row("repo · " + ((r as? JSONObject)?.optString("repo")?.ifBlank { null } ?: r.toString()),
                    "declared", deviceRepos, State.PENDING, observed = false)
            }
            pending(git.opt("repos")) -> listOf(Row("repos", pendingText(git.getJSONObject("repos")), deviceRepos, State.PENDING))
            else -> listOf(Row("repos", "not in the vault", deviceRepos, State.PENDING))
        }
        return listOf(
            secretRow("github token", "github_token", SECTION_GIT, K_GITHUB_TOKEN),
            secretRow("ssh key", "ssh_private_key", SECTION_SSH, K_VAULT_REPO_KEY),
        ) + repoRows
    }

    /**
     * #713 Cloud Drive's git sign-ins that carry NO credential of the vault's: the
     * declared git-chain rungs that ride the fleet session (gitea, the fleet proxy).
     * Cloud Drive signs them in itself through the shared libs:auth, which this app
     * cannot read, so each is an item that says so rather than a silent gap.
     */
    fun driveSessionRows(chain: List<com.diegonmarcos.cloudlib.auth.AuthDeclaration.GitRung>, device: String): List<Row> =
        chain.filter { !it.holdsGithubCredential }.map { r ->
            Row("${r.label} · sign-in", "the fleet session (no credential in the vault)", device, State.PENDING, observed = false)
        }

    /** #713 The owner's address as the vault's own contact card carries it (the about
     *  section's declared `email` field) — what mail accounts are addressed at when nobody
     *  signed in, i.e. after an Import File. Blank when the vault has none. */
    fun ownerEmail(bundle: JSONObject, layout: Layout): String {
        val key = layout.sections.firstOrNull { it.apply == "about" }?.fields?.get("email") ?: return ""
        return bundle.optJSONObject("about")?.optJSONObject("profile")?.optString(key).orEmpty().trim()
    }

    /** Both credentials into the one encrypted blob, at the paths
     *  build.json::ui.import_schema declares (ssh.vault_repo_key, git.github_token). */
    fun applyDrive(bundle: JSONObject, prefs: ConfigsPrefs): String {
        val git = bundle.optJSONObject("git") ?: return "✗ no git section in the vault"
        val written = DRIVE_SECRETS.mapNotNull { (item, at) ->
            (git.opt(item) as? String)?.takeIf { it.isNotBlank() }?.let { v ->
                prefs.putSecret(at.first, at.second, if (item == "github_token") v.trim() else v); item.replace('_', ' ')
            }
        }
        return if (written.isEmpty()) "✗ the vault holds neither a token nor a key" else "✓ ${written.joinToString(" + ")} stored"
    }

    /** #778 vault `git.<item>` → where cloud-sa keeps it ([ConfigsPrefs] section, key) — what drive applies
     *  and what Account ▸ Runtime reads back. The import_schema paths (ssh.vault_repo_key, git.github_token). */
    val DRIVE_SECRETS: Map<String, Pair<String, String>> = linkedMapOf(
        "github_token" to (SECTION_GIT to K_GITHUB_TOKEN),
        "ssh_private_key" to (SECTION_SSH to K_VAULT_REPO_KEY),
    )

    // ── AI ───────────────────────────────────────────────────────────────

    /** `providerId → (keyPresent, keyHint)` as the serving app answered. */
    data class AiState(val providers: Map<String, Pair<Boolean, String>>)

    fun aiState(snapshotJson: String?): AiState? {
        val root = runCatching { JSONObject(snapshotJson ?: return null) }.getOrNull() ?: return null
        val arr = root.optJSONArray("providers") ?: return AiState(emptyMap())
        return AiState((0 until arr.length()).associate { i ->
            val p = arr.getJSONObject(i)
            p.getString("id") to (p.optBoolean("key_present") to p.optString("key_hint"))
        })
    }

    fun aiRows(bundle: JSONObject, layout: Layout, state: AiState?, peerDownText: String, unmappedText: String): List<Row> {
        val tokens = bundle.optJSONObject("ai")?.optJSONObject("tokens") ?: return emptyList()
        return tokens.keys().asSequence().map { item ->
            val v = tokens.opt(item)
            val provider = layout.aiTokens[item]
            val declared = when {
                pending(v) -> pendingText(v as JSONObject)
                v is String -> "•••• (${v.length} chars)"
                else -> v.toString()
            }
            val device = when {
                provider == null -> unmappedText
                state == null -> peerDownText
                else -> state.providers[provider]?.let { (present, hint) ->
                    if (present) "$provider · key …$hint" else "$provider · no key"
                } ?: "$provider · unknown to the serving app"
            }
            val st = when {
                pending(v) || v !is String -> State.PENDING
                provider == null -> State.PENDING
                state == null -> State.ABSENT
                state.providers[provider]?.first == true &&
                    v.endsWith(state.providers[provider]!!.second) -> State.MATCH
                state.providers[provider]?.first == true -> State.DIFFERS
                else -> State.ABSENT
            }
            Row(item, declared, device, st)
        }.toList()
    }

    /**
     * #1801 Every mapped, non-pending token is entered in the fleet's AI-key owner engine, never pushed to an app: this
     * opens the engine's AI keys screen ([open], DecisionsLink.openKeySettings in the caller) once per distinct provider
     * and reports, per vault item, where its key goes. No key leaves the vault bundle here.
     */
    fun applyAi(bundle: JSONObject, layout: Layout, open: (provider: String) -> Boolean): String {
        val tokens = bundle.optJSONObject("ai")?.optJSONObject("tokens") ?: return "✗ no ai.tokens in the vault"
        val opened = HashMap<String, Boolean>()
        val lines = layout.aiTokens.mapNotNull { (item, provider) ->
            val v = tokens.opt(item) as? String ?: return@mapNotNull null
            if (v.isBlank()) return@mapNotNull null
            val ok = opened.getOrPut(provider) { open(provider) }
            if (ok) "→ $item → $provider: set it in the AI keys engine (opened)"
            else "✗ $item → $provider: the AI keys engine is not installed (Store ▸ Libs ▸ Cloud Lib Decisions Engine)"
        }
        return if (lines.isEmpty()) "✗ nothing to apply: every mapped token is pending" else lines.joinToString("\n")
    }

    // ── apps ─────────────────────────────────────────────────────────────

    /**
     * The app set the vault declares for [device]: `apps.devices.<id>`, walked
     * for (a) any object that IS a Store ▸ Phone Apps inventory (#565's
     * `kind`), parsed by the one parser, and (b) any array of objects carrying
     * `package` (the fleet manifest referenced from the vault). Pending markers
     * contribute nothing. `ours` is THIS build's fleet, as in the import.
     */
    fun appsDeclared(bundle: JSONObject, device: Device, fleet: Set<String>): List<AppInventory.Entry> =
        appsDeclared(bundle, device.id, fleet)

    /** The same set by the vault's own device key (`apps.devices.<id>`). */
    fun appsDeclared(bundle: JSONObject, deviceId: String, fleet: Set<String>): List<AppInventory.Entry> {
        val mine = bundle.optJSONObject("apps")?.optJSONObject("devices")?.opt(deviceId) ?: return emptyList()
        val out = mutableListOf<AppInventory.Entry>()
        fun walk(v: Any?) {
            when {
                v is JSONObject && v.optString("kind") == AppInventory.KIND ->
                    out += runCatching { AppInventory.parse(v.toString()) }.getOrDefault(emptyList())
                pending(v) -> Unit
                v is JSONObject -> v.keys().forEach { walk(v.get(it)) }
                v is JSONArray -> (0 until v.length()).forEach { i ->
                    val o = v.optJSONObject(i)
                    val pkg = o?.optString("package").orEmpty()
                    if (pkg.isNotBlank()) out += AppInventory.Entry(pkg, "", 0, null, pkg in fleet, null)
                    else walk(v.get(i))
                }
            }
        }
        walk(mine)
        return out.distinctBy { it.pkg }.sortedBy { it.pkg }
    }

    /** #727 One declared app as Infos lists it: its name, its package, and the
     *  installer of record the vault's file names (null = none recorded). */
    data class DeclaredApp(val pkg: String, val label: String, val store: String?, val ours: Boolean)

    /**
     * #727 [appsDeclared] for [deviceId], each with a NAME: the `label` (or
     * `name`) the vault's own file gives the package — the phone export's
     * label, the fleet manifest's — else the package id itself. Nothing typed here.
     */
    fun appsListed(bundle: JSONObject, deviceId: String, fleet: Set<String>): List<DeclaredApp> {
        val names = HashMap<String, String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val pkg = v.optString("package")
                    val name = (v.opt("label") as? String).orEmpty().ifBlank { (v.opt("name") as? String).orEmpty() }
                    if (pkg.isNotBlank() && name.isNotBlank()) names.putIfAbsent(pkg, name)
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> (0 until v.length()).forEach { walk(v.opt(it)) }
            }
        }
        walk(bundle.optJSONObject("apps")?.optJSONObject("devices")?.opt(deviceId))
        return appsDeclared(bundle, deviceId, fleet).map {
            DeclaredApp(it.pkg, names[it.pkg] ?: it.pkg, it.origin, it.ours || it.pkg in fleet)
        }
    }

    /** #727 The store an app came from, named through the ONE install-source map
     *  (libs:appstore appstore-install-sources.json): ours → its `ours` label, a
     *  declared store → that store's label, else the installer package as recorded. */
    fun storeLabel(sources: JSONObject, app: DeclaredApp): String? = when {
        app.ours -> sources.optJSONObject("ours")?.optString("label")?.ifBlank { null } ?: app.store
        else -> app.store?.let { sources.optJSONObject("sources")?.optJSONObject(it)?.optString("label")?.ifBlank { null } ?: it }
    }

    /** Sections the cockpit consumed; the rest of the bundle is shown raw. */
    fun consumed(layout: Layout): Set<String> = layout.sections.flatMap { it.vault }.toSet()

    const val SELF = "self"
    const val TEXT_TOOLS = "text_tools"
    private const val PREFS = "vault_cockpit"
    private const val K_DEVICE = "device_id"
    private const val DEVICE_ICON_DEFAULT = "_default"
    const val SECTION_GIT = "git"
    const val K_GITHUB_TOKEN = "github_token"
    const val SECTION_SSH = "ssh"
    const val K_VAULT_REPO_KEY = "vault_repo_key"
}
