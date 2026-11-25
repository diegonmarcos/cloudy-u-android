package com.diegonmarcos.cloudme

/**
 * #1932 the viewer of a Proj repo entry. A last-child entry whose page file embeds https://github.com/<owner>/<repo>[/tree/<ref>/<dir>] declares
 * `"views": ["gh","md","html","canvas"]` next to its `url`; the four views are DERIVED from that one URL (owner, repo, optional folder), never listed
 * per repo: GH Repo Page is the entry's own url (today's page, unchanged), the other three are README.md / README.html / README.canvas of the repo
 * (of the folder, for a /tree/ entry) on main. Pure Kotlin so the JVM suite and test/test-proj-repo-views-1932.sh cover it.
 */
object RepoViews {
    const val GH = "gh"; const val MD = "md"; const val HTML = "html"; const val CANVAS = "canvas"

    /** One view: [id] is the declared name, [file] the repo file it shows (null for the GitHub page). */
    data class View(val id: String, val file: String?)

    /** Declared order; GH Repo Page first and the default. */
    val VIEWS = listOf(View(GH, null), View(MD, "README.md"), View(HTML, "README.html"), View(CANVAS, "README.canvas"))
    val IDS: List<String> = VIEWS.map { it.id }

    data class Ref(val owner: String, val repo: String, val dir: String = "", val branch: String = "main") {
        val slug: String get() = "$owner/$repo" + if (dir.isEmpty()) "" else "/$dir"
        private fun path(file: String) = if (dir.isEmpty()) file else "$dir/$file"
        /** The file as raw.githubusercontent.com serves it (text/plain). */
        fun rawUrl(file: String) = "https://raw.githubusercontent.com/$owner/$repo/$branch/${path(file)}"
        /** The authenticated route to the same file (private repos). */
        fun apiUrl(file: String) = "https://api.github.com/repos/$owner/$repo/contents/${path(file)}?ref=$branch"
        /** The base the fetched README.html is rendered against, so its relative images and links resolve inside the repo. */
        fun baseUrl() = "https://raw.githubusercontent.com/$owner/$repo/$branch/" + if (dir.isEmpty()) "" else "$dir/"
        fun githubBlobUrl(file: String) = "https://github.com/$owner/$repo/blob/$branch/${path(file)}"
        fun githubUrl() = "https://github.com/$owner/$repo"
    }

    private val GH_URL = Regex("^https://github\\.com/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)(?:/tree/([A-Za-z0-9._-]+)/(.+?))?/?$")

    /** The repo an entry's url opens; null for a site, a bundled page or anything that is not a GitHub repo (or folder of one). */
    fun parse(url: String): Ref? = GH_URL.matchEntire(url)?.let { m ->
        Ref(m.groupValues[1], m.groupValues[2], m.groupValues[4])
    }

    /** The views a block declares, in declared order, restricted to the four known ids; empty when it declares none. */
    fun declared(views: List<String>): List<String> = IDS.filter { it in views }

    /** The view to open: the remembered one when it is one of the four, else GH Repo Page. */
    fun initial(remembered: String?): String = if (remembered != null && remembered in IDS) remembered else GH

    fun fileOf(view: String): String? = VIEWS.firstOrNull { it.id == view }?.file

    /** Why a fetch gave no text; shown verbatim (url, status, exception) by the viewer, never a blank pane. */
    data class Failure(val url: String, val status: Int?, val error: String?, val authTried: Boolean, val tokenHeld: Boolean)
    enum class Kind { NotFound, PrivateNoCredential, CredentialRefused, Server, Network }

    fun kind(f: Failure): Kind = when (f.status) {
        null -> Kind.Network
        401, 403, 404 -> if (!f.authTried) Kind.PrivateNoCredential else if (f.status == 404) Kind.NotFound else Kind.CredentialRefused
        in 500..599 -> Kind.Server
        else -> Kind.Network
    }
}
