package com.diegonmarcos.superapp.translate

/**
 * WHO OWNS THE KEYSTROKES — the bar's own box, or the host application's field.
 *
 * While a bar is open its box holds the keys: LatinIME routes printable keys, backspace,
 * the editing keys and the caret gestures into [TextBoxEditor] instead of into the field
 * behind the bar. That capture is #87 and it is not in question here. What this class adds
 * is the way OUT of it, which #355 part 2 decided: **tapping into the host application's
 * own text field releases the keys back to it.** No new gesture, because that is what
 * every IME panel on the platform already does and the instinctive action should be the
 * one that works.
 *
 * THE ONLY SIGNAL AN IME GETS FOR "THE USER MOVED THE CARET THEMSELVES" is
 * `onUpdateSelection`, and it does not say who caused it. The bar's own writes to the
 * host field produce one too, so a release on every selection change hands the keys back
 * the instant the user types into the panel — which is the whole feature, gone.
 *
 * SO THE DISCRIMINATOR IS DECLARED, NOT INFERRED. Every write the bar makes to the host
 * field announces itself with [onSelfWrite] first, and the echo that comes back is spent
 * against that announcement rather than read as a takeover. This is the SAME rule the
 * live-commit ownership state machine already ran on — `releaseOutput` sets
 * `Output.NONE` before its InputConnection calls precisely so the guard swallows its own
 * echo — widened from that one call site to every one of them. It is not a second,
 * rival distinction.
 *
 * WHY IT COULD NOT JUST REUSE `Output`. `Output.OWNED` only ever exists in live-commit
 * mode, so with live commit off the ownership machine never leaves `NONE` and would have
 * classified nothing at all. Key ownership also has to outlive text ownership in the
 * other direction: after a host tap the bar must stop writing to the field FOREVER
 * (`Output.LOST` is terminal, and stays terminal) while still being allowed to take the
 * keys back when the user taps the panel again. One flag cannot say both things.
 *
 * WHY NOT `RichInputConnection.isBelatedExpectedUpdate`. That is AOSP's answer to this
 * exact question and it is in this tree, but the bar is bound to the RAW
 * `InputConnection` from `getCurrentInputConnection()`, not to `mConnection`. Its
 * expected-position tracking never sees the bar's writes and would report every one of
 * them as unexpected — the wrong answer, confidently.
 *
 * No Android imports, on purpose: this is the part whose correctness is worth executing,
 * and keeping it free of the view layer is what lets a test compile and RUN it without a
 * device. `libs/` carries engines, and this is one.
 */
class KeyHandoff {

    /** True while the bar's own box owns the keys; false once the host field took them. */
    var heldByBox = true
        private set

    /**
     * One unspent self-write announcement. A boolean and not a counter, deliberately:
     * the bar writes to the field only from the main thread, in response to one user
     * action, and the user cannot tap the field in the middle of that. A counter could
     * only ever drift upward — a host that coalesces two writes into one update, or
     * reports none at all, would leave it permanently above zero and swallow real taps
     * for the rest of the session. Capped at one, the worst a lost echo can cost is a
     * single ignored tap, and the next tap works.
     *
     * ponytail: one outstanding self-write is enough for every write this bar makes;
     * if a caller ever needs two in flight at once, that is the point to make it a
     * counter AND to bound it.
     */
    private var selfWritePending = false

    /** A fresh session — the bar was just shown. The box starts out holding the keys. */
    fun reset() {
        heldByBox = true
        selfWritePending = false
    }

    /**
     * The bar is about to write to the HOST field in a way that reports no composing
     * region — `commitText`, `finishComposingText`, a select-all-and-replace. Called
     * BEFORE the InputConnection call, because `onUpdateSelection` arrives asynchronously
     * and the announcement has to already be standing when it does.
     *
     * A composing write (`setComposingText` with real text) does not need this: it
     * reports a composing span of >= 0, which LatinIME filters out before ever calling
     * [onSelectionChange]. It is only the writes that look exactly like a host tap that
     * have to say so.
     */
    fun onSelfWrite() {
        selfWritePending = true
    }

    /**
     * The host reported a selection change with no composing region.
     *
     * @return true when it came from the HOST — the user tapped into the application's
     *   own field — and false when it was the echo of the bar's own write. Releasing the
     *   keys is done here; the caller decides what else a host takeover means to it.
     */
    fun onSelectionChange(): Boolean {
        if (selfWritePending) {
            selfWritePending = false
            return false
        }
        heldByBox = false
        return true
    }

    /**
     * The user touched the bar's own box, so the keys come back to it.
     *
     * The enhance bar's box already took the keys this way and the translate bar passed
     * an empty `onClaim` because it never gave them up. Now that it can, the same touch
     * is the way back — symmetric with the release, and again no gesture to learn.
     */
    fun reclaim() {
        heldByBox = true
    }

    /** #1818: the panel's resting state is "the host app has the keys" (Enhance, Draft Answer); also the tap-the-app path. */
    fun release() {
        heldByBox = false
    }

    /** #1818: a write announced with [onSelfWrite] that never happened must not swallow the user's next real tap. */
    fun cancelSelfWrite() {
        selfWritePending = false
    }
}
