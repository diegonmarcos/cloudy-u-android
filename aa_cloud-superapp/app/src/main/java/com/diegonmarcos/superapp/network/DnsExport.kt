package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.mesh.MeshReport

/**
 * #1924 The DNS page as text for Copy all and Export to Markdown: Status, Test lookup, Test reachability by IP,
 * Bridges and DNS servers, in the page's order. Same helper the VPN page uses ([MeshReport.markdown] / [MeshReport.redact],
 * #1845 / #1900): `== ` sections become `##` headings with the rows fenced. Pure, so DnsExportTest drives it.
 */
object DnsExport {
    val SECTIONS = listOf("Status", "Test lookup", "Test reachability by IP", "Bridges", "DNS servers")

    fun text(title: String, status: String, lookup: String, reach: String, bridges: String, servers: String): String {
        val out = StringBuilder(title).append('\n')
        listOf(status, lookup, reach, bridges, servers).forEachIndexed { i, body ->
            out.append("\n== ").append(SECTIONS[i]).append('\n').append(body.trimEnd()).append('\n')
        }
        return MeshReport.redact(out.toString())
    }

    fun markdown(text: String): String = MeshReport.markdown(text)
}
