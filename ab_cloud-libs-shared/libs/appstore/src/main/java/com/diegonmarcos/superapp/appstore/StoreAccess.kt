package com.diegonmarcos.superapp.appstore

/**
 * ACCESS — the per-app access-surface MODEL: one tab per layer of the fleet's
 * MCP → API → IPC → identity → Android-permission design, so the owner can CHECK
 * each app's exposed surface, DECLARED beside ACTUAL. The Compose reader is
 * [StoreInspect.Access]; this object is the pure, testable half.
 *
 * A READER OVER ONE DECLARATION (the store-feed rule T7, applied to the access
 * surface): this object parses no JSON and names no route, tool, authority or
 * permission of its own. It is handed already-parsed rows —
 *   - MCP + API: the generated [AppsMcpApi] projection of the webserver's unified
 *     registry (ac_cloud-c3-webserver/data/api-ipc-map.json, the MCP⇄API⇄IPC⇄ACL
 *     source of truth), per app;
 *   - IPC: the baked [IpcMesh] registry (aa_cloud-superapp/data/ipc-mesh.json);
 *   - ID: the baked fleet row beside this phone's PackageManager —
 * so a surface appears here only by being declared upstream, and GROWS with the
 * declaration. A cross-link the on-device projection does not yet carry — an MCP
 * tool's API route, an API route's IPC target: the unified schema's NEW edges —
 * is reported as [PENDING], never invented.
 */
object StoreAccess {

    /** The five layers, in surface order (outermost caller first). */
    enum class Tab { MCP, API, IPC, ID, PERMS }

    /** Where a row's value came from, for the checkable column:
     *  DECLARED (in the registry, no device cross-check), ACTUAL (read off this
     *  phone only), MATCH / MISMATCH (declared vs actual compared), PENDING (an
     *  edge the unified schema will carry but the projection does not yet),
     *  NONE (declared, but nothing on the device to compare). */
    enum class Status { DECLARED, ACTUAL, MATCH, MISMATCH, PENDING, NONE }

    /** Shown wherever the unified registry's edge is not yet in the on-device
     *  projection — rendered instead of a fabricated target. */
    const val PENDING = "declared: pending"

    /** The byKind keys of [AppsMcpApi] (its own schema, same keys Routes reads):
     *  the MCP tool kind, and the two API-route kinds. */
    const val KIND_MCP = "mcp"
    const val KIND_APP = "app"
    const val KIND_DEBUG = "debug"

    /** One dense table row: a lead token (method / kind / field), the address or
     *  value, a description, the next-layer cross-[link] it maps to (or [PENDING]),
     *  and its checkable [status]. */
    class Row(
        val lead: String,
        val addr: String,
        val what: String = "",
        val link: String? = null,
        val status: Status = Status.DECLARED,
    )

    /** One named table inside a tab. Empty [rows] → the reader draws "none declared". */
    class Section(val title: String, val rows: List<Row>)

    // ── MCP ────────────────────────────────────────────────────────────────
    /** The app's exposed MCP tools. Each maps to an API route the unified registry
     *  links; the projection does not carry that edge yet, so the link is [PENDING]. */
    fun mcp(row: AppsMcpApi.Row?): List<Section> {
        val eps = row?.byKind?.get(KIND_MCP).orEmpty()
        return listOf(Section("", eps.map { Row(it.method, it.url, it.what, PENDING, Status.DECLARED) }))
    }

    // ── API ────────────────────────────────────────────────────────────────
    /** The app's API routes (its own `app` kind, then the `debug` kind, which may
     *  be INHERITED from the shared lib's default). Each maps to an IPC target the
     *  unified registry links — [PENDING] until the projection carries that edge. */
    fun api(row: AppsMcpApi.Row?): List<Section> {
        fun rows(kind: String): List<Row> {
            val inherited = kind in (row?.inherited ?: emptySet())
            return row?.byKind?.get(kind).orEmpty().map {
                Row(it.method, it.url, it.what, PENDING, if (inherited) Status.ACTUAL else Status.DECLARED)
            }
        }
        return listOf(Section("", rows(KIND_APP) + rows(KIND_DEBUG)))
    }

    // ── IPC ────────────────────────────────────────────────────────────────
    /** The app's IPC endpoints from the baked registry: content:// providers, bound
     *  services + their AIDL, intent actions, deep links, and the fleet-IPC ops. The
     *  DECLARED endpoint is checked ACTUAL against [resolves] (does it resolve on this
     *  phone — PackageManager only, nothing started): MATCH resolves, MISMATCH does not. */
    fun ipc(endpoints: List<IpcMesh.Endpoint>, resolves: Map<String, Boolean> = emptyMap()): List<Section> =
        listOf(Section("", endpoints.map { e ->
            val what = e.permission?.let { "perm: ${it.substringAfterLast('.')}" } ?: e.name
            val status = when (resolves[e.id]) {
                true -> Status.MATCH
                false -> Status.MISMATCH
                else -> Status.DECLARED
            }
            Row(e.kind, e.address, what, null, status)
        }))

    // ── ID ─────────────────────────────────────────────────────────────────
    /** App identity: the baked fleet's declaration beside this phone's PackageManager.
     *  [installed] false ⇒ the actual side is NONE (nothing on the device to read). */
    class Id(
        val fleetId: String,
        val pkg: String,
        val declaredVersionName: String?,
        val declaredVersionCode: Long,
        val installed: Boolean,
        val actualPkg: String?,
        val actualVersionName: String?,
        val actualVersionCode: Long?,
        val uid: Int?,
        val signingSha: String?,
    )

    /** One identity row: the field, its DECLARED value, its ACTUAL value, compared. */
    fun id(x: Id): List<Section> {
        val rows = ArrayList<Row>()
        // fleet id is a pure declaration (the device has no notion of it).
        rows += Row("fleet id", x.fleetId, "", null, Status.DECLARED)
        rows += idRow("package", x.pkg, if (x.installed) x.actualPkg else null)
        rows += idRow("version", x.declaredVersionName ?: "—",
            if (x.installed) x.actualVersionName ?: "—" else null, declaredKnown = x.declaredVersionName != null)
        rows += idRow("version code", x.declaredVersionCode.takeIf { it > 0 }?.toString() ?: "—",
            if (x.installed) x.actualVersionCode?.toString() ?: "—" else null, declaredKnown = x.declaredVersionCode > 0)
        // UID and the signing cert exist only once installed — actual-only facts.
        rows += Row("uid", if (x.installed) x.uid?.toString() ?: "—" else "—", "", null,
            if (x.installed && x.uid != null) Status.ACTUAL else Status.NONE)
        rows += Row("signing cert", if (!x.installed) "—" else x.signingSha ?: PENDING, "", null,
            when { !x.installed -> Status.NONE; x.signingSha != null -> Status.ACTUAL; else -> Status.PENDING })
        return listOf(Section("", rows))
    }

    /** A declared-vs-actual identity row: [actual] null ⇒ not installed (NONE);
     *  equal ⇒ MATCH; different ⇒ MISMATCH; declared unknown ⇒ PENDING. */
    private fun idRow(field: String, declared: String, actual: String?, declaredKnown: Boolean = true): Row {
        val status = when {
            actual == null -> Status.NONE
            !declaredKnown -> Status.PENDING
            declared == actual -> Status.MATCH
            else -> Status.MISMATCH
        }
        val shown = if (actual == null) declared else "$declared → $actual"
        return Row(field, shown, "", null, status)
    }

    /** The per-tab summary line the reader draws under the strip: how many rows a
     *  tab holds and how many are checked against the device. */
    fun summary(tab: Tab, sections: List<Section>): String {
        val rows = sections.flatMap { it.rows }
        if (rows.isEmpty()) return "${tab.name} · none declared"
        val checked = rows.count { it.status == Status.MATCH || it.status == Status.MISMATCH || it.status == Status.ACTUAL }
        val pending = rows.count { it.status == Status.PENDING || it.link == PENDING }
        return "${tab.name} · ${rows.size} declared · $checked checked" + if (pending > 0) " · $pending pending" else ""
    }
}
