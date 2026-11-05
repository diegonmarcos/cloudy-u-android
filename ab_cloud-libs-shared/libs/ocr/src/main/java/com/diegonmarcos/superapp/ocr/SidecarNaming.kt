package com.diegonmarcos.superapp.ocr

import java.io.File

/** The two sibling files of a screenshot: same folder, same base name, `.md` and `.json`. */
object SidecarNaming {
    const val MD = "md"
    const val JSON = "json"

    fun base(imageName: String): String = imageName.substringBeforeLast('.', imageName)
    fun md(image: File): File = File(image.parentFile, base(image.name) + "." + MD)
    fun json(image: File): File = File(image.parentFile, base(image.name) + "." + JSON)

    /** A `.md` / `.json` that has an image of the same base name beside it is one of ours, never a screenshot. */
    fun isSidecarName(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it == MD || it == JSON }
}
