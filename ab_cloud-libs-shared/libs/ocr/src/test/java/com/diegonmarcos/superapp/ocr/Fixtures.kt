package com.diegonmarcos.superapp.ocr

import java.io.File

object Fixtures {
    fun line(text: String, l: Int, t: Int, r: Int, h: Int = 30, conf: Double? = 0.9) = OcrLine(text, Box(l, t, r, t + h), conf)

    /** A battery-stats screen: a heading, a paragraph, then a 3-column table. */
    fun battery(): List<OcrLine> = listOf(
        line("Battery usage", 40, 100, 600, 60),
        line("Since last full charge", 40, 180, 500),
        line("App", 40, 260, 200), line("Screen time", 400, 260, 640), line("Usage", 800, 260, 960),
        line("Chrome", 40, 310, 220), line("2 h 14 min", 400, 310, 640), line("12.5%", 820, 310, 960),
        line("Maps", 40, 360, 160), line("45 min", 400, 360, 600), line("7%", 900, 360, 960),
        line("Tip: lower brightness", 40, 520, 700),
        line("to save power", 40, 556, 400),
    )

    fun tmp(): File = File.createTempFile("ocr", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
}
