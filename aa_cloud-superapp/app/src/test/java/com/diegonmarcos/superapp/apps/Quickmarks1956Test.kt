package com.diegonmarcos.superapp.apps

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1956 Phone > Quickmarks: the folder faces are the Cloud page's declared icons again, and the new placements are pinned
 * (socials in Me-Pers, the Linktree link in Me-Prj, Lnktree equal to Me-Prj, Tidal in Media Center, Gemini in AI, GitHub in Drive).
 * Plain JVM test over the shipped build.json; the quickmarks-1956-mutation workflow breaks the data one way per leg and reads
 * the verdict of the named test from the XML.
 */
class Quickmarks1956Test {
    private val app = File(System.getProperty("user.dir"), "..")
    private val bj = JSONObject(File(app, "build.json").readText())
    private val sections: JSONArray = bj.getJSONObject("ui").getJSONArray("sections")
    private fun objs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
    private val phone = objs(objs(sections).first { it.has("phone_app_groups") }.getJSONArray("phone_app_groups"))
    private val cloud = objs(objs(sections).first { it.optString("id") == "cloud" }.getJSONArray("tile_groups"))
    private fun pkgs(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.get(it).let { x -> if (x is JSONObject) x.getString("pkg") else x.toString() } }
    private fun folder(group: String, label: String) = objs(phone.first { it.getString("title") == group }.getJSONArray("folders")).first { it.getString("label") == label }
    private fun members(group: String, label: String) = pkgs(folder(group, label).getJSONArray("packages"))
    private fun links(group: String, label: String) = objs(folder(group, label).optJSONArray("links")).map { Triple(it.getString("label"), it.getString("url"), it.optString("icon")) }
    private val socials = listOf("com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.linkedin.android", "com.pinterest",
        "com.bereal.ft", "com.tinder", "com.bumble.app", "co.hinge.app")
    private val primeVideo = "com.amazon.avod.thirdpartyclient"

    @Test fun `every quickmark that existed before is still present, except Prime Video in Media Center`() {
        val before = JSONObject(File(app, "test/data/phone-quickmarks-before-1956.json").readText())
        val all = phone.flatMap { g -> pkgs(g.optJSONArray("packages")) + objs(g.optJSONArray("folders")).flatMap { pkgs(it.getJSONArray("packages")) } }.toSet()
        val lost = mutableListOf<String>()
        val folders = before.getJSONObject("folders")
        for (k in folders.keys()) for (p in pkgs(folders.getJSONArray(k))) if (p !in all && p != primeVideo) lost += "$k:$p"
        val loose = before.getJSONObject("packages")
        for (k in loose.keys()) for (p in pkgs(loose.getJSONArray(k))) if (p !in all) lost += "$k:$p"
        val labels = phone.flatMap { g -> objs(g.optJSONArray("folders")).map { g.getString("title") + "/" + it.getString("label") } }.toSet()
        for (k in folders.keys()) if (k !in labels) lost += "folder $k"
        assertEquals(emptyList<String>(), lost)
        assertTrue("Prime Video is still in the Media Center folder", primeVideo !in members("Services · Leisure", "Media Center"))
    }

    @Test fun `the social apps sit in Me-Pers and no longer in Me-Prj`() {
        assertTrue(members("Me", "Me-Pers").containsAll(socials))
        assertTrue(members("Me", "Me-Prj").none { it in socials })
    }

    @Test fun `Me-Prj holds a web link quickmark to the Linktree page`() {
        assertTrue(links("Me", "Me-Prj").any { it.second == "https://linktree.diegonmarcos.com" })
    }

    @Test fun `Lnktree holds the same icons as Me-Prj`() {
        assertEquals(members("Me", "Me-Prj"), members("Me", "Lnktree"))
        assertEquals(links("Me", "Me-Prj"), links("Me", "Lnktree"))
        assertTrue(links("Me", "Lnktree").isNotEmpty())
    }

    @Test fun `Tidal is in Media Center and Prime Video is not`() {
        assertTrue("com.aspiro.tidal" in members("Services · Leisure", "Media Center"))
        assertTrue(primeVideo !in members("Services · Leisure", "Media Center"))
    }

    @Test fun `LinkedIn is in Jobs and Business after Indeed and stays in Me-Pers`() {
        assertEquals(listOf("com.indeed.android.jobsearch", "com.linkedin.android"), members("Services · City", "Jobs & Business"))
        assertTrue("com.linkedin.android" in members("Me", "Me-Pers"))
    }

    @Test fun `Gemini is in AI`() {
        assertTrue("com.google.android.apps.bard" in members("AGI", "AI"))
    }

    @Test fun `GitHub is in the Drive folder and not in Code`() {
        assertTrue("com.github.android" in members("Data", "Drive"))
        assertTrue("com.github.android" !in members("AGI", "Code"))
    }

    @Test fun `every folder icon equals the icon of the same tile on the Cloud page`() {
        val rename = mapOf("Nav" to "Navigation", "Agenda" to "Tasks")
        val diffs = mutableListOf<String>()
        var n = 0
        for (g in phone) for (f in objs(g.optJSONArray("folders"))) {
            val cg = cloud.firstOrNull { it.getString("title") == g.getString("title") } ?: continue
            val label = f.getString("label")
            val tile = objs(cg.getJSONArray("tiles")).firstOrNull { it.optString("label") == (rename[label] ?: label) }
            if (tile == null) { diffs += "${g.getString("title")}/$label has no Cloud tile"; continue }
            n++
            if (f.optString("icon_res") != tile.optString("icon")) diffs += "${g.getString("title")}/$label ${f.optString("icon_res")} != ${tile.optString("icon")}"
        }
        assertEquals(emptyList<String>(), diffs)
        assertTrue("compared $n folders", n >= 40)
    }

    @Test fun `the folder face draws the declared Cloud icon and not the first installed app`() {
        val src = File(app, "app/src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt").readText()
        assertTrue(src.contains("FolderTile(folder.label, contents, null, folder.iconRes)"))
    }
}
