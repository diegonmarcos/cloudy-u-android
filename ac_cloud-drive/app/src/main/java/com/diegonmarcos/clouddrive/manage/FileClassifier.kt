package com.diegonmarcos.clouddrive.manage

/**
 * Manage ▸ Data: the owner's four filter groups and their subtypes, and the ONE classifier that
 * puts a file in a subtype. Extension first (the cheap, exact signal), then the MIME type for a
 * file whose extension says nothing. #1537 a file that matches no subtype is [DataKind.OTHERS] for the
 * scan ([FileClassifier.classifyOrOthers]), so the "All" filter (first and default) really shows every file.
 * The filter order is All, then the groups below in declared order: .tmp, Bin (recycle-bin leftovers) and
 * Others come after the original four. The extension sets ARE the declaration: one table, one owner per extension.
 */
enum class DataGroup { MEDIA, OFFICE, STRUCTURE, BINS, TMP, BIN, OTHERS }

enum class DataKind(val group: DataGroup, val extensions: Set<String>) {
    VIDEOS(DataGroup.MEDIA, setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "3g2", "wmv", "flv", "mpg", "mpeg", "m2ts", "vob")),
    PICTURES(DataGroup.MEDIA, setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "svg", "dng", "raw", "cr2", "nef", "arw", "avif", "ico")),
    MUSIC(DataGroup.MEDIA, setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mid", "midi", "aiff", "alac")),
    PDF(DataGroup.OFFICE, setOf("pdf")),
    OFFICE(DataGroup.OFFICE, setOf("doc", "docx", "xls", "xlsx", "xlsm", "ppt", "pptx", "odt", "ods", "odp", "odg", "rtf", "pages", "numbers", "key", "epub")),
    STRUCTURED(DataGroup.STRUCTURE, setOf("json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "csv", "tsv", "ini", "conf", "cfg", "plist", "properties", "env")),
    MARKDOWN(DataGroup.STRUCTURE, setOf("md", "markdown", "mdx", "rst", "org")),
    TEXT(DataGroup.STRUCTURE, setOf("txt", "log", "text")),
    CODE(DataGroup.STRUCTURE, setOf("kt", "kts", "java", "c", "h", "cpp", "cc", "cxx", "hpp", "cs", "go", "rs", "swift", "ts", "tsx", "jsx", "scala", "dart", "m", "mm", "vue", "svelte", "html", "htm", "css", "scss", "sass", "less", "sql", "gradle", "nix", "hs", "ex", "exs", "erl", "clj", "zig")),
    BINARIES(DataGroup.BINS, setOf("bin", "exe", "dll", "so", "o", "a", "dex", "elf", "img", "iso", "dylib", "class", "jar", "aar", "wasm", "odex", "vdex", "oat", "dat", "db", "sqlite")),
    APK(DataGroup.BINS, setOf("apk", "apks", "xapk", "apkm", "aab")),
    ARCHIVES(DataGroup.BINS, setOf("zip", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lz4", "lzma", "rar", "cab", "z")),
    SCRIPTS(DataGroup.BINS, setOf("sh", "bash", "zsh", "fish", "py", "js", "mjs", "cjs", "rb", "pl", "php", "lua", "ps1", "bat", "cmd", "awk", "r", "tcl")),
    /** #1537 ".tmp": temporary and leftover files. */
    TMP(DataGroup.TMP, setOf("tmp", "temp", "bak", "swp", "swo", "part", "crdownload", "cache", "old", "orig")),
    /** #1537 "Bin": what a recycle bin keeps (Android's .trashed-* files and the usual trash suffixes). */
    BIN(DataGroup.BIN, setOf("trashed", "trash", "del", "deleted", "recycle")),
    /** #1537 "Others": every file no other subtype owns (no extension set: it is the remainder). */
    OTHERS(DataGroup.OTHERS, emptySet());

    companion object {
        fun inGroup(g: DataGroup): List<DataKind> = entries.filter { it.group == g }
    }
}

object FileClassifier {
    private val byExtension: Map<String, DataKind> = buildMap { DataKind.entries.forEach { k -> k.extensions.forEach { put(it, k) } } }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** [classify], with the remainder as [DataKind.OTHERS] (what the scan stores). A name starting ".trashed-" (Android's trash) is Bin. */
    fun classifyOrOthers(name: String, mime: String? = null): DataKind =
        if (name.startsWith(".trashed-")) DataKind.BIN else classify(name, mime) ?: DataKind.OTHERS

    /** The subtype of a file named [name] with MIME type [mime] (null when unknown), or null when it is none of them. */
    fun classify(name: String, mime: String? = null): DataKind? {
        byExtension[extensionOf(name)]?.let { return it }
        val m = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        return when {
            m.isEmpty() -> null
            m.startsWith("video/") -> DataKind.VIDEOS
            m.startsWith("image/") -> DataKind.PICTURES
            m.startsWith("audio/") -> DataKind.MUSIC
            m == "application/pdf" -> DataKind.PDF
            m == "application/vnd.android.package-archive" -> DataKind.APK
            m.startsWith("application/vnd.openxmlformats-officedocument") || m.startsWith("application/vnd.oasis.opendocument") || m == "application/msword" || m.startsWith("application/vnd.ms-") -> DataKind.OFFICE
            m in setOf("application/zip", "application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-rar-compressed", "application/vnd.rar", "application/x-bzip2", "application/x-xz") -> DataKind.ARCHIVES
            m == "application/json" || m.endsWith("+json") || m.endsWith("/xml") || m.endsWith("+xml") || m == "text/csv" || m.contains("yaml") || m.contains("toml") -> DataKind.STRUCTURED
            m == "text/markdown" -> DataKind.MARKDOWN
            m == "text/x-shellscript" || m == "text/x-python" || m == "text/javascript" || m == "application/javascript" || m == "application/x-sh" -> DataKind.SCRIPTS
            m.startsWith("text/x-") -> DataKind.CODE
            m == "text/plain" -> DataKind.TEXT
            m == "application/x-executable" || m == "application/x-sharedlib" || m == "application/octet-stream" -> DataKind.BINARIES
            else -> null
        }
    }
}

/** Tri-state of a group toggle: every subtype on, some on, none on. */
enum class GroupState { ALL, PARTIAL, NONE }

/**
 * The Data page's filter: "All" (every file, the default) or the set of subtypes switched on. Each
 * subtype toggles on its own; the group toggle turns the whole group on (from NONE or PARTIAL) or
 * off (from ALL), and reports PARTIAL when only some of its subtypes are on. Picking anything leaves
 * All; switching the last subtype off returns to All.
 */
data class DataFilter(val on: Set<DataKind>, val all: Boolean = false) {
    fun groupState(g: DataGroup): GroupState {
        val kinds = DataKind.inGroup(g)
        val n = kinds.count { it in on }
        return when (n) { 0 -> GroupState.NONE; kinds.size -> GroupState.ALL; else -> GroupState.PARTIAL }
    }

    private fun of(kinds: Set<DataKind>) = if (kinds.isEmpty()) DEFAULT else DataFilter(kinds)

    fun toggleGroup(g: DataGroup): DataFilter =
        if (all) of(DataKind.inGroup(g).toSet())
        else if (groupState(g) == GroupState.ALL) of(on - DataKind.inGroup(g).toSet()) else of(on + DataKind.inGroup(g))

    fun toggle(k: DataKind): DataFilter = if (all) of(setOf(k)) else of(if (k in on) on - k else on + k)

    fun accepts(k: DataKind?): Boolean = k != null && (all || k in on)

    fun encode(): String = if (all) ALL_TOKEN else DataKind.entries.filter { it in on }.joinToString(",") { it.name }

    companion object {
        private const val ALL_TOKEN = "ALL"
        /** #1537 All is the first and default option: every file shows until the user narrows it. */
        val DEFAULT = DataFilter(emptySet(), all = true)
        /** [text] as written by [encode]; null or unreadable → [DEFAULT]. An explicit empty string is a filter with nothing on. */
        fun decode(text: String?): DataFilter {
            if (text == null || text.trim() == ALL_TOKEN) return DEFAULT
            if (text.isBlank()) return DataFilter(emptySet())
            val kinds = text.split(',').mapNotNull { n -> DataKind.entries.firstOrNull { it.name == n.trim() } }.toSet()
            return if (kinds.isEmpty()) DEFAULT else DataFilter(kinds)
        }
    }
}
