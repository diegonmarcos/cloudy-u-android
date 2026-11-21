package app.sterna.ui.inbox

/**
 * #1837: the last crumb of the top bar names the open folder (label name, Archive, ...), so "Cloud Mail / Mail / Inbox" does not
 * stay on screen over the Receipts label. Null (no extra crumb) where the path already says it: the Inbox itself, the unified
 * inbox, the unread view, or no folder selected yet. [displayName] is the name the list title shows.
 */
internal fun breadcrumbFolder(role: String?, unified: Boolean, unreadView: Boolean, hasFolder: Boolean, displayName: String): String? =
    if (unified || unreadView || !hasFolder || role == "inbox" || displayName.isBlank()) null else displayName
