package app.sterna.ui.inbox

/*
 * #1869 What a running reconcile DRAWS over the list. Cached rows on screen are the answer the reader came for: a
 * background reconcile (a folder tap, a cold start, a push) must not put a spinner over them. The tern stays the
 * answer to the pull gesture, and the only sign of a load when there is nothing to show yet; anything else is a
 * one-hair progress line at the top of the list. [indicator] is the graced display ([MailUi.showRefreshIndicator]),
 * never the truth, so #178's grace and floor apply to both.
 */

/** The pull-to-refresh tern: the pull's own answer, or a load in front of an empty list. */
internal fun ternDrawn(indicator: Boolean, gesture: Boolean, rows: Int): Boolean =
    indicator && (gesture || rows == 0)

/** The subtle sync line: a background reconcile behind rows already on screen. Never drawn with the tern. */
internal fun syncLineDrawn(indicator: Boolean, gesture: Boolean, rows: Int): Boolean =
    indicator && !gesture && rows > 0
