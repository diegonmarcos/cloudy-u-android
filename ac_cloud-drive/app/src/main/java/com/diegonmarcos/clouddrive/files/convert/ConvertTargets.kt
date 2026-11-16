package com.diegonmarcos.clouddrive.files.convert

import com.diegonmarcos.clouddrive.PdfConvert

/**
 * The Convert targets of the Explorer menus, as DATA. The per-file dropdown's "Convert…" dialog, "Convert to PDF…" and
 * "Convert from PDF…" read the same [ConverterRegistry] / [ToPdf] / [PdfConvert] lists; the multi-selection dropdown
 * asks [forNames] with every selected name and gets the SAME targets in the same order. A target is [enabled] only when
 * every selected file supports it; otherwise it is listed unavailable with the [reason] naming the files that do not.
 * For ONE name this is exactly the single-file offer (ConvertTargetsTest pins that). Pure: no Android type.
 */
object ConvertTargets {

    enum class Kind { CONVERTER, TO_PDF, FROM_PDF }

    data class Target(
        val key: String,
        val label: String,
        val kind: Kind,
        /** The registry converter id, or the PDF target (`md`, `ocr`, ...); `pdf` for [Kind.TO_PDF]. */
        val arg: String,
        /** Names this target can be applied to. */
        val accepting: List<String>,
        /** Names it cannot be applied to. */
        val rejecting: List<String>,
    ) {
        val enabled: Boolean get() = accepting.isNotEmpty() && rejecting.isEmpty()
        /** Why a target is unavailable; null when it is available for every selected file. */
        val reason: String? get() = when {
            enabled -> null
            accepting.isEmpty() && rejecting.size == 1 -> "not available for ${rejecting.first()}"
            else -> "not available for " + rejecting.take(MAX_NAMES).joinToString(", ") + if (rejecting.size > MAX_NAMES) " +${rejecting.size - MAX_NAMES} more" else ""
        }
    }

    private const val MAX_NAMES = 2

    private fun isPdf(name: String) = com.diegonmarcos.clouddrive.files.FileOps.extensionOf(name) == "pdf"

    fun forNames(names: List<String>, registry: ConverterRegistry = ConverterRegistry.default): List<Target> {
        fun split(accepts: (String) -> Boolean) = names.partition(accepts)
        return buildList {
            registry.converters.forEach { c ->
                val (yes, no) = split { n -> c.id in registry.forName(n).map { it.id } }
                add(Target("conv:" + c.id, c.label, Kind.CONVERTER, c.id, yes, no))
            }
            val (toYes, toNo) = split { ToPdf.refusal(it) == null }
            add(Target("pdf", "PDF", Kind.TO_PDF, "pdf", toYes, toNo))
            PdfConvert.DIALOG_TARGETS.forEach { t ->
                val (yes, no) = split { isPdf(it) }
                add(Target("frompdf:$t", if (t == PdfConvert.OCR) "OCR" else t, Kind.FROM_PDF, t, yes, no))
            }
        }
    }
}
