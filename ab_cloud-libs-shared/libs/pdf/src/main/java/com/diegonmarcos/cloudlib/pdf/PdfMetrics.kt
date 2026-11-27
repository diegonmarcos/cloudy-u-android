package com.diegonmarcos.cloudlib.pdf

import com.diegonmarcos.superapp.uikit.KitDensity

/** The reader chrome's scale, taken from the fleet density (KitDensity) so no dp literal lives here; the values equal Drive's former DriveMetrics. */
internal object PdfMetrics {
    val pad = KitDensity.small
    val padWide = KitDensity.medium
    val cardRadius = KitDensity.corner
    val gapWide = KitDensity.rowPadV
    val tap = KitDensity.touch
    val listBottom = KitDensity.large
    val islandHeight = KitDensity.touch + KitDensity.small
    val rowHeight = KitDensity.touch + KitDensity.buttonPadV
}
