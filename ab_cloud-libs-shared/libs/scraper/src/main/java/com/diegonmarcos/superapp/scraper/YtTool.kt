package com.diegonmarcos.superapp.scraper

import org.json.JSONObject
import java.net.URI

/** What the YouTube tool saves: the video, its best audio stream, or the audio track taken out of the video. */
enum class YtAs(val label: String) { VIDEO("Video"), AUDIO("Audio"), AUDIO_FROM_VIDEO("Audio from video") }

/** Where the file goes: the app's external folder, the shared Downloads, or a place the user picks. */
enum class YtTarget(val label: String) { EXTERNAL("External"), DOWNLOADS("Downloads"), CHOOSE("Choose…") }

data class YtRequest(val url: String, val saveAs: YtAs, val quality: Int?, val target: YtTarget)

/**
 * #1428 the YouTube Download tool's request rules. Decision (recorded in the ticket): extraction is the
 * API's (yt-dlp in scrappers-api); "Audio from video" fetches a small video (360p, best audio) and takes
 * the audio track out ON THE PHONE (libs:media-convert-audio, a remux), falling back to the API's own
 * extract when the codec cannot be remuxed here.
 */
object YtTool {
    const val NOTICE = "Only download content you have rights to. YouTube's Terms of Service restrict downloading."
    val VIDEO_QUALITIES = listOf(2160, 1440, 1080, 720, 480, 360)
    val AUDIO_QUALITIES = listOf(320, 256, 160, 128, 96, 64)
    const val EXTRACT_SOURCE_QUALITY = 360
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

    fun qualities(saveAs: YtAs): List<Int> = when (saveAs) {
        YtAs.VIDEO -> VIDEO_QUALITIES
        YtAs.AUDIO -> AUDIO_QUALITIES
        YtAs.AUDIO_FROM_VIDEO -> emptyList()
    }

    /** The 11-character video id of a pasted link, or null when it is not a YouTube video link. */
    fun videoId(raw: String): String? {
        val s = raw.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        val u = runCatching { URI(s) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val path = u.rawPath.orEmpty()
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path == "/watch" -> u.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/") -> path.split('/').getOrNull(2)
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    /** The canonical link sent to the API. */
    fun canonical(id: String) = "https://www.youtube.com/watch?v=$id"

    /** What is wrong with [r], empty when it can be sent. */
    fun problems(r: YtRequest): List<String> {
        val out = ArrayList<String>()
        if (videoId(r.url) == null) out += "Paste a YouTube video link"
        val q = qualities(r.saveAs)
        if (r.quality != null && r.quality !in q) out += "Pick a quality from the list"
        return out
    }

    /** True when the phone takes the audio out of the downloaded video. */
    fun extractsOnDevice(r: YtRequest) = r.saveAs == YtAs.AUDIO_FROM_VIDEO

    /** The JSON body of POST /download/youtube. [serverExtract] asks the API to extract the audio itself. */
    fun body(r: YtRequest, serverExtract: Boolean = false): JSONObject {
        val id = videoId(r.url) ?: throw IllegalArgumentException("not a YouTube video link")
        val o = JSONObject().put("url", canonical(id))
        when (r.saveAs) {
            YtAs.VIDEO -> { o.put("mode", "video"); r.quality?.let { o.put("quality", it) } }
            YtAs.AUDIO -> { o.put("mode", "audio"); r.quality?.let { o.put("quality", it) } }
            YtAs.AUDIO_FROM_VIDEO -> if (serverExtract) o.put("mode", "audio_extract")
                else { o.put("mode", "video"); o.put("quality", EXTRACT_SOURCE_QUALITY) }
        }
        return o
    }

    /** The name the file is saved under before the API names it (the Choose… picker needs one first). */
    fun suggestedName(r: YtRequest): String {
        val id = videoId(r.url) ?: "video"
        return when (r.saveAs) { YtAs.VIDEO -> "youtube-$id.mp4"; else -> "youtube-$id.m4a" }
    }

    fun suggestedMime(r: YtRequest) = if (r.saveAs == YtAs.VIDEO) "video/mp4" else "audio/mp4"
}
