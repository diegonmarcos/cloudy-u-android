package com.diegonmarcos.superapp.texttools

/**
 * Where the Text tools live, and what a caller is allowed to assume about them.
 *
 * [SERVICE_PACKAGES] is a constellation ADDRESS, not configuration — the same kind of
 * constant as `FleetToken.AUTHORITY_PKG` and the translate client's companion
 * package. It is not read from a build.json on purpose: a library shared by
 * reference into several apps reads the CONSUMING app's build.json, so a field
 * baked that way would resolve to mail's own package when mail builds it, which is
 * the exact bug the keyboard's version_code once had. Which app holds the routing
 * settings and the provider key is a fact about the fleet rather than about whoever
 * is calling.
 */
object TextTools {

    /**
     * The apps that may OWN the engines, the settings and the credential, MOST PREFERRED
     * FIRST. [TextToolsClient] binds the first entry that actually publishes [ACTION].
     *
     * A LIST RATHER THAN ONE NAME so a future home can be added by an install rather than a fleet-wide release.
     * #1707: the standalone cloud-writer app was retired and its Writer lives in cloud-keyboard (the Writer tab), so the
     * keyboard is the only entry: it owns the engines, the settings and the credential. (The retired app never published
     * the action, so nothing on a phone ever bound to it.)
     *
     * ORDER IS THE PREFERENCE AND IS THE ONLY THING THIS LIST MEANS. It is not a fallback
     * chain to be walked when a call fails: a bound peer that answers an ERROR has answered,
     * and re-asking a different app would spend a second provider's money on the same text
     * and report whichever reply came back second. Resolution happens once per bind.
     *
     * Every entry must be signed with the Cloud key — [PERMISSION] is signature-level and
     * the platform refuses the rest — so adding a name here cannot hand the fleet's text to
     * a stranger.
     */
    val SERVICE_PACKAGES = listOf(
        "com.diegonmarcos.cloudkeyboard",
    )

    /** The intent action [ITextTools] is published under. */
    const val ACTION = "com.diegonmarcos.superapp.texttools.ITextTools"

    /** Signature-level, so the platform refuses any caller not signed with the Cloud key. */
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    /**
     * Shown when NOTHING in [SERVICE_PACKAGES] is there to answer. Names the fix, because
     * the fix is an install and not a retry — the translate bar learned that the hard way
     * and its wording is deliberately echoed here.
     *
     * It names Cloud Writer AND Cloud Keyboard rather than only the preferred one: either
     * genuinely repairs this, and telling the owner to install Cloud Writer while Cloud
     * Keyboard sits uninstalled on the same phone would send him to the longer of two fixes.
     */
    const val NOT_INSTALLED =
        "Text tools need the Cloud Writer app, or the Cloud Keyboard app — one of them holds " +
            "the AI routing settings and the provider key"

    /** Slot 0 of every [ITextTools] reply: the result. */
    const val SLOT_TEXT = 0

    /** Slot 1 of every [ITextTools] reply: why there is no result. */
    const val SLOT_REASON = 1

    /**
     * The empty [styleId] argument to `enhance`: use the style, tone, length and
     * output language the user pinned in Text Enhancements, rather than one named style.
     */
    const val STYLE_CONFIGURED = ""

    /** The empty target-tag argument to `translate`: use the configured default target. */
    const val TARGET_CONFIGURED = ""

    /**
     * The empty [summaryId] argument to `summarise`: use the summary shape the user pinned in
     * Text Resume. ("Resume" is the owner's name for SUMMARISE — see [ITextTools.summarise].)
     */
    const val SUMMARY_CONFIGURED = ""

    /** One call's outcome. [text] non-null = it worked; otherwise [error] says why, always readable. */
    class Result(val text: String?, val error: String?) {
        val ok: Boolean get() = text != null

        companion object {
            /** Read the wire's `{text, reason}` without trusting its shape — it crossed a process. */
            fun of(reply: Array<String>?): Result {
                val text = reply?.getOrNull(SLOT_TEXT)?.takeIf { it.isNotEmpty() }
                val reason = reply?.getOrNull(SLOT_REASON)?.takeIf { it.isNotEmpty() }
                return when {
                    text != null -> Result(text, null)
                    reason != null -> Result(null, reason)
                    // Neither slot filled: the far side answered, but said nothing. That is
                    // not success, and reporting it as an empty rewrite would wipe the
                    // user's text with the model's silence.
                    else -> Result(null, "Text tools returned an empty reply")
                }
            }

            fun failed(reason: String) = Result(null, reason)
        }
    }
}
