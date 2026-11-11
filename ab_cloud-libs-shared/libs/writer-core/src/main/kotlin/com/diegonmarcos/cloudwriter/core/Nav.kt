package com.diegonmarcos.cloudwriter.core

/** #1532 every screen the activity draws; MainActivity's one `screen` state holds one of these. */
enum class Dest { DOCS, EDITOR, CONFIGS, TOOLS, ENHANCER, DRAFTER, TRANSLATOR, GRAMMAR }

/** How the content changes between two screens: a list opening a document slides forward, closing it back, the rest fade through. */
enum class Transition { NONE, FORWARD, BACKWARD, FADE_THROUGH }

object WriterScreens {
    /** Material fade-through timings: the old screen leaves first, then the new one arrives. */
    const val FADE_OUT_MS = 90
    const val FADE_IN_MS = 210
    /** A forward or backward slide covers 1/[SLIDE_FRACTION] of the width. */
    const val SLIDE_FRACTION = 8

    /**
     * Where Back goes from [current], or null when Back leaves the app (the list). Configs returns to the open document only
     * when it was opened from it; the tool pages return to the list.
     */
    fun back(current: Dest, configsReturn: Dest, hasDoc: Boolean): Dest? = when (current) {
        Dest.DOCS -> null
        Dest.CONFIGS -> if (configsReturn == Dest.EDITOR && hasDoc) Dest.EDITOR else Dest.DOCS
        else -> Dest.DOCS
    }

    fun transition(from: Dest, to: Dest): Transition = when {
        from == to -> Transition.NONE
        from == Dest.DOCS && to == Dest.EDITOR -> Transition.FORWARD
        from == Dest.EDITOR && to == Dest.DOCS -> Transition.BACKWARD
        else -> Transition.FADE_THROUGH
    }
}
