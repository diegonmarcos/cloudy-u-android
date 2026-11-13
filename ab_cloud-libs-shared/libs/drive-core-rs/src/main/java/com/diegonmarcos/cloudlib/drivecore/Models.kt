package com.diegonmarcos.cloudlib.drivecore

/** The three engines, and the one switch each has. */
enum class Engine(val id: String) { HASH("hash"), WALK("walk"), DIFF("diff") }

/** Which implementation answers an engine: the Rust core (default) or the Kotlin reference path (also the fallback). */
enum class Impl(val id: String) { RUST("rust"), KOTLIN("kotlin") }

/** One file's digests. [blake3] / [sha256] are lowercase hex, empty when that algorithm was not asked. [error] is empty when the file was read. */
data class FileHash(val path: String, val bytes: Long, val blake3: String, val sha256: String, val error: String)

data class WalkOpts(
    /** Folder names never entered, anywhere in the tree. */
    val skipNames: Set<String> = emptySet(),
    /** Paths relative to the root never entered (e.g. Android/data). */
    val skipRel: Set<String> = emptySet(),
    val maxEntries: Long = 200_000L,
    val budgetMs: Long = 25_000L,
    /** The N largest files to return (0: none). */
    val topFiles: Int = 25,
    /** The N largest first-level folders to return (0: all). */
    val topFolders: Int = 25,
    /** Return every file as (path, size, mtime): the snapshot a tree diff compares. */
    val listFiles: Boolean = false,
    val threads: Int = 4,
    /** The folder-total key of the files directly in the root. */
    val looseLabel: String = "",
)

data class SizedPath(val bytes: Long, val path: String)
data class ExtTotal(val count: Long, val bytes: Long)
data class ListedFile(val path: String, val bytes: Long, val mtimeMs: Long, val hash: String = "")

/** A walk's answer, whole, from one call. [byExt] keys are the extension as written in the name ("" when none). */
data class WalkAgg(
    val files: Long,
    val dirs: Long,
    val bytes: Long,
    val entries: Long,
    /** Empty when the walk finished; the reason when it was bounded. */
    val partial: String,
    /** First-level folder (absolute path, or the loose label) to bytes, largest first, path breaks a tie. */
    val folders: List<Pair<String, Long>>,
    val topFiles: List<SizedPath>,
    val byExt: Map<String, ExtTotal>,
    val listing: List<ListedFile>,
)

/** A tree delta entry: ADDED (right only), DELETED (left only), MODIFIED. */
data class Delta(val kind: Char, val path: String)
