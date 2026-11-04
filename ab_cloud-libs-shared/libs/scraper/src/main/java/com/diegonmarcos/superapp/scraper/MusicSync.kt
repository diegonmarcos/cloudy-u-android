package com.diegonmarcos.superapp.scraper

/** #1459 one service behind one interface: reads for every source, the write calls only where the service has them. */
interface MusicConnector {
    val id: String
    val canWrite: Boolean
    fun playlists(): List<Playlist>
    fun tracks(p: Playlist): List<Track>
    fun liked(): List<Track>
    /** Candidates for [t] on this service: an ISRC search first, a title + artist search when that finds nothing. */
    fun find(t: Track): List<Track>
    fun createPlaylist(name: String): String
    fun addTracks(playlistId: String, ids: List<String>)
}

data class SyncEntry(val source: Track, val match: Match)

data class SyncPlan(val name: String, val entries: List<SyncEntry>) {
    val byIsrc get() = entries.count { it.match.how == How.ISRC }
    val byFuzzy get() = entries.count { it.match.how == How.FUZZY }
    val unmatched get() = entries.filter { it.match.how == How.NONE }
    /** The target's own ids, in source order, each song once. */
    val targetIds get() = entries.mapNotNull { it.match.candidate?.id?.takeIf { id -> id.isNotBlank() } }.distinct()
    fun summary() = "${entries.size} tracks: $byIsrc by ISRC, $byFuzzy fuzzy, ${unmatched.size} not found"
}

data class SyncReport(val plan: SyncPlan, val dryRun: Boolean, val added: Int, val playlistId: String?)

/** Copy a playlist from one service to another: [plan] is the preview, [run] with dryRun = true writes nothing. */
object MusicSync {
    const val CHUNK = 50

    fun plan(name: String, tracks: List<Track>, target: MusicConnector, progress: (Int, Int) -> Unit = { _, _ -> }): SyncPlan {
        val out = ArrayList<SyncEntry>(tracks.size)
        for ((i, t) in tracks.withIndex()) {
            out += SyncEntry(t, Matcher.best(t, target.find(t)))
            progress(i + 1, tracks.size)
        }
        return SyncPlan(name, out)
    }

    fun run(plan: SyncPlan, target: MusicConnector, dryRun: Boolean): SyncReport {
        if (dryRun) return SyncReport(plan, true, 0, null)
        require(target.canWrite) { "${target.id} cannot be written to" }
        val ids = plan.targetIds
        if (ids.isEmpty()) return SyncReport(plan, false, 0, null)
        val pid = target.createPlaylist(plan.name)
        for (chunk in ids.chunked(CHUNK)) target.addTracks(pid, chunk)
        return SyncReport(plan, false, ids.size, pid)
    }
}
