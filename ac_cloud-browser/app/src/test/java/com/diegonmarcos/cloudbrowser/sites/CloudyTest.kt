package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1493 Cloudy: the public Pages and the pages the Cloud-Webserver serves. */
class CloudyTest {
    @Test
    fun `the Pages list is the declared ones merged with the live public ones, by address`() {
        val decl = JSONObject("""{"gh_pages":[{"name":"b-repo","url":"https://o.github.io/b-repo/"},{"name":"o.github.io","url":"https://o.github.io/"}]}""")
        val live = """{"pages":[{"repo":"a-repo","url":"https://o.github.io/a-repo/","public":true},{"repo":"b-repo","url":"https://o.github.io/b-repo","public":true},{"repo":"hid","url":"https://o.github.io/hid/","public":false}]}"""
        val out = Cloudy.merge(Cloudy.seedPages(decl), live)
        assertEquals(listOf("a-repo", "b-repo", "o.github.io"), out.map { it.title })
        assertEquals(Cloudy.seedPages(decl).sortedBy { it.title }, Cloudy.merge(Cloudy.seedPages(decl), null))
        assertTrue(Cloudy.merge(emptyList(), "garbage").isEmpty())
    }

    private val ws = JSONObject("""{"base_role":"drive","endpoints":[
        {"id":"e","title":"Local","name":"emulated.app","port":8002,"role":"drive","summary":"the drive"},
        {"id":"c","title":"Cloud","name":"cloud.app","port":8003,"role":"diag","summary":"diag"}],
        "pages":[{"id":"drive","label":"Drive","route":"/__app__/api/sub/pages-drive","tab":"pages"}]}""")

    @Test
    fun `the declared Webserver pages are its endpoints and its Pages sub-pages on the drive endpoint`() {
        val out = Cloudy.declaredWebserver(ws)
        assertEquals(listOf("Local", "Cloud", "Drive"), out.map { it.title })
        assertEquals("http://emulated.app/__app__/api/sub/pages-drive", out.last().url)
        assertEquals(8002, out.last().port)
        assertTrue(Cloudy.declaredWebserver(JSONObject()).isEmpty())
    }

    @Test
    fun `the Repos store adds each running site on its loopback port, and a missing or bad store adds nothing`() {
        val store = """{"entries":[{"id":"1","label":"Site","repo":"r","source":"emulated","start":"","exposure":"mesh","port":8100},{"id":"2","label":"Bad","repo":"r","port":0}]}"""
        val out = Cloudy.storeEntries(store)
        assertEquals(1, out.size)
        assertEquals("http://127.0.0.1:8100/", out[0].url)
        assertEquals("r · mesh", out[0].detail)
        assertTrue(Cloudy.storeEntries(null).isEmpty())
        assertTrue(Cloudy.storeEntries("nope").isEmpty())
    }

    private val root = java.io.File("../..")
    private val browser = JSONObject(java.io.File("../build.json").readText())
    private val routes = org.json.JSONArray(java.io.File(root, "ac_cloud-c3/data/routes.json").readText())

    @Test
    fun `the Cloudy subpages are WebApps, GH Pages, Local and C3, in that order`() {
        val sections = browser.getJSONObject("ui").getJSONArray("sections")
        val cloudy = (0 until sections.length()).map { sections.getJSONObject(it) }.first { it.getString("id") == "cloudy" }.getJSONArray("pages")
        val pages = (0 until cloudy.length()).map { cloudy.getJSONObject(it) }
        assertEquals(listOf("webapps", "gh_pages", "local", "c3_urls"), pages.map { it.getString("id") })
        assertEquals(listOf("WebApps", "GH Pages", "Local", "C3"), pages.map { it.getString("label") })
    }

    @Test
    fun `#1856 every page of diegonmarcos github io comes from its front-topology, by category, once per category`() {
        val topo = """{"projects":[
            {"name":"Linktree","category":"Lnktree","deploy_name":"linktree","framework":"vanilla"},
            {"name":"Cloud SuperApp","category":"cloud-webapps","deploy_name":"cloud-superapp"},
            {"name":"Cloud Mobile","category":"a_Portals","deploy_name":"cloud-superapp"},
            {"name":"Dup","category":"Lnktree","deploy_name":"linktree"},
            {"name":"Unpublished","category":"Lnktree","deploy_name":""}]}"""
        val out = Cloudy.sitePages(topo, "https://diegonmarcos.github.io/")
        assertEquals(listOf("a_Portals", "cloud-webapps", "Lnktree"), out.map { it.first })
        assertEquals(listOf("https://diegonmarcos.github.io/linktree/"), out.first { it.first == "Lnktree" }.second.map { it.url })
        assertEquals("a project is listed in each category it belongs to", "https://diegonmarcos.github.io/cloud-superapp/", out[0].second[0].url)
        assertTrue(Cloudy.sitePages(null, "https://x/").isEmpty() && Cloudy.sitePages("nope", "https://x/").isEmpty())
    }

    @Test
    fun `#1856 the declared Webserver splits into File Explorers (endpoints) and Pages, and loses nothing`() {
        val ws = JSONObject("""{"base_role":"drive","endpoints":[{"title":"Local","name":"emulated.app","role":"drive","port":8002},{"title":"Termux","name":"termux.app","role":"external","port":8000}],
            "pages":[{"label":"Drive","route":"/p/drive","tab":"pages"}]}""")
        assertEquals(listOf("Local", "Termux"), Cloudy.webserverExplorers(ws).map { it.title })
        assertEquals(listOf("Drive"), Cloudy.webserverPages(ws).map { it.title })
        assertEquals(Cloudy.webserverExplorers(ws) + Cloudy.webserverPages(ws), Cloudy.declaredWebserver(ws))
    }

    @Test
    fun `C3 Urls holds each container once with the very addresses the private and public lists had, grouped by c3-infra category`() {
        val groups = Cloudy.c3Groups(routes, "http://", "https://")
        val all = groups.flatMap { it.containers }
        assertEquals("each container once", all.size, all.map { it.name }.toSet().size)
        val priv = Cloudy.fleetUrls(routes, true, "http://", "https://")
        val pub = Cloudy.fleetUrls(routes, false, "http://", "https://")
        assertEquals(priv.map { it.title to it.url }.toSet(), all.filter { it.privateUrl != null }.map { it.name to it.privateUrl!! }.toSet())
        assertEquals(pub.map { it.title to it.url }.toSet(), all.filter { it.publicUrl != null }.map { it.name to it.publicUrl!! }.toSet())
        val cats = (0 until routes.length()).map { routes.getJSONObject(it).optString("category") }.toSet()
        assertEquals(cats, groups.map { it.category }.toSet())
        assertTrue(groups.all { g -> g.containers.all { it.category == g.category } })
        assertEquals(groups.map { it.category }.sortedBy { it.lowercase() }, groups.map { it.category })
        assertTrue(Cloudy.c3Groups(null, "http://", "https://").isEmpty())
    }

    @Test
    fun `a container with only one address keeps the other null, and two routes of one container merge`() {
        val r = org.json.JSONArray("""[{"container":"a","category":"app","auth_type":"mesh","private_url":"a.mesh:1","public_url":""},{"container":"a","category":"app","public_url":"a.pub"},{"container":"b","category":"data","public_url":"b.pub"}]""")
        val g = Cloudy.c3Groups(r, "http://", "https://")
        assertEquals(listOf("app", "data"), g.map { it.category })
        assertEquals(C3Container("a", "app", "mesh", "http://a.mesh:1", "https://a.pub"), g[0].containers.single())
        assertEquals(null, g[1].containers.single().privateUrl)
    }

    @Test
    fun `the private and public URL lists are derived from the declared fleet routes, nothing typed`() {
        val priv = Cloudy.fleetUrls(routes, true, "http://", "https://")
        val pub = Cloudy.fleetUrls(routes, false, "http://", "https://")
        val all = (0 until routes.length()).map { routes.getJSONObject(it) }
        assertEquals(all.count { it.optString("private_url").isNotBlank() }, priv.size)
        assertEquals(all.count { it.optString("public_url").isNotBlank() }, pub.size)
        assertTrue(priv.size > 50 && pub.size > 20)
        assertTrue(priv.all { it.url.startsWith("http://") })
        assertTrue(pub.all { it.url.startsWith("https://") })
        assertEquals(priv.sortedBy { it.title.lowercase() }, priv)
        // the browser build bakes the routes and the two schemes from the declaration, not from a list here
        val gradle = java.io.File("build.gradle").readText()
        assertTrue("routes.json" in gradle && "fleetRoutes" in gradle)
        assertEquals("http://", browser.getJSONObject("ui").getJSONObject("cloudy").getString("private_scheme"))
        assertEquals("https://", browser.getJSONObject("ui").getJSONObject("cloudy").getString("public_scheme"))
        assertTrue(Cloudy.fleetUrls(null, true, "http://", "https://").isEmpty())
    }

    @Test
    fun `the GH Pages tab is the declared public repos' Pages and the Webserver tab is its declared serving map`() {
        val repos = JSONObject(java.io.File(root, "ac_cloud-c3/data/repos_declared.json").readText()).getJSONArray("repos")
        val declared = (0 until repos.length()).map { repos.getJSONObject(it) }.filter { it.optString("visibility") == "pub" && it.optString("pages_url").isNotBlank() }
        val decl = JSONObject().put("gh_pages", org.json.JSONArray().also { a -> declared.forEach { a.put(JSONObject().put("name", it.getString("name")).put("url", it.getString("pages_url"))) } })
        assertEquals(declared.map { it.getString("pages_url") }, Cloudy.seedPages(decl).map { it.url })
        val serving = JSONObject(java.io.File(root, "ac_cloud-c3-webserver/build.json").readText()).getJSONObject("serving")
        assertTrue(serving.getJSONArray("endpoints").length() >= 4)
        val gradle = java.io.File("build.gradle").readText()
        assertTrue("repos_declared.json" in gradle && "servingDecl.endpoints" in gradle)
    }

    @Test
    fun `the Webserver link is an explicit intent to its declared launcher activity`() {
        val manifest = java.io.File(root, "ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main/AndroidManifest.xml").readText()
        val launcher = Regex("(?s)<activity\\b([^>]*)>(.*?)</activity>").findAll(manifest)
            .first { "android.intent.category.LAUNCHER" in it.groupValues[2] }.groupValues[1].let { Regex("android:name=\"([^\"]+)\"").find(it)!!.groupValues[1] }
        val ws = JSONObject(java.io.File(root, "ac_cloud-c3-webserver/build.json").readText())
        val pkg = ws.getJSONObject("android").getString("application_id")
        assertEquals(pkg, ws.getJSONObject("serving").getJSONObject("keepalive").getString("package"))
        val t = Cloudy.webserverTarget(JSONObject().put("package", pkg).put("launcher_activity", launcher))!!
        assertEquals(pkg, t.pkg)
        assertEquals("$pkg.MainActivity", t.cls)
        assertEquals(WebserverTarget("a.b", "a.b.Main"), Cloudy.webserverTarget(JSONObject().put("package", "a.b").put("launcher_activity", "Main")))
        assertEquals(WebserverTarget("a.b", "x.y.Main"), Cloudy.webserverTarget(JSONObject().put("package", "a.b").put("launcher_activity", "x.y.Main")))
        // nothing declared = nothing to start: the page says it is not installed
        assertNull(Cloudy.webserverTarget(JSONObject()))
        assertNull(Cloudy.webserverTarget(JSONObject().put("package", "a.b")))
    }
}
