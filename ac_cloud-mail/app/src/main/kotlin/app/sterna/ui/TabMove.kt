package app.sterna.ui

import androidx.navigation.NavController

/** The mail list's route: the NavHost's start destination, and the one entry whose ViewModel holds the list (pages, selection, scroll). */
internal const val LIST_ROUTE = "inbox"

/** What a tap on a bottom-bar destination does to the back stack (#1952). */
internal sealed interface TabMove {
    /** The tapped page is already on top. */
    data object Stay : TabMove

    /** Go BACK to the list entry already on the stack: same entry, same ViewModel, same loaded pages. */
    data class Return(val route: String) : TabMove

    /** Open another page directly above the list entry ([above]), replacing whatever page was above it. */
    data class Open(val route: String, val above: String) : TabMove
}

/**
 * #1952: a bar tap never pushes a second copy of the mail list. Before, every tap was `navigate(route)`, so coming back to Mail built a
 * NEW "inbox" entry with a new InboxViewModel: an empty pager, a fresh query and a fresh network refresh, each time. The list entry is
 * the bottom of the stack; Mail returns to it, every other page sits directly above it (its own state saved and restored).
 */
internal fun tabMove(target: String, current: String): TabMove = when {
    target == current -> TabMove.Stay
    target == LIST_ROUTE -> TabMove.Return(LIST_ROUTE)
    else -> TabMove.Open(target, LIST_ROUTE)
}

/** Apply [move]. The plain `navigate` of [TabMove.Return] is only the fallback for a stack that lost its list entry. */
internal fun NavController.moveToTab(move: TabMove) {
    when (move) {
        TabMove.Stay -> Unit
        is TabMove.Return -> if (!popBackStack(move.route, inclusive = false, saveState = true)) navigate(move.route)
        is TabMove.Open -> navigate(move.route) {
            popUpTo(move.above) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}
