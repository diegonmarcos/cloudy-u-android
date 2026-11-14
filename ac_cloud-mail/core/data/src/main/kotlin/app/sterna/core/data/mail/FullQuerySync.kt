package app.sterna.core.data.mail

/** Every page as it lands, the reconcile once at the end, on a walk that finished. The reconcile
 *  deletes every cached row outside [keepIds], so it sits on the normal return path and nowhere
 *  `emptySet()`. */
internal suspend fun <P, R> fullQueryWriteThrough(
    // The folder as it was before the walk, or null from a caller that cannot name it.
    folderIds: suspend () -> Set<String>?,
    walk: suspend (onPage: suspend (P) -> Unit) -> R,
    writePage: suspend (P) -> Unit,
    keepIds: (R) -> Set<String>?,
    spareIds: suspend () -> List<String>,
    // Given the walk's own answer too: IMAP resolves the folder to reconcile while walking.
    reconcile: suspend (walked: R, keepIds: Set<String>, spareIds: List<String>, evictableIds: Set<String>?) -> Unit,
): R {
    // Before the walk, and nowhere else: [writePage] writes into this folder as the walk runs.
    val before = folderIds()
    val walked = walk(writePage)
    // Returning is necessary, not sufficient: the walk can return having learned nothing.
    val keep = keepIds(walked) ?: return walked
    val spare = spareIds()
    // [before] is the DELETE's bound, not an addition to [keep]: the reconcile re-reads (#165).
    reconcile(walked, keep, spare, before)
    return walked
}
