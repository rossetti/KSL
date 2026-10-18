/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities.io.report

import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.renderer.HtmlReportRenderer
import ksl.utilities.io.report.renderer.LaTeXReportRenderer
import ksl.utilities.io.report.renderer.MarkdownReportRenderer
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.WeightedStatistic
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Counts in report tables (observations and missing values) are whole numbers and print as such, in every
 *  table that shows them and in all four formats. Before R1.8 they went through the general number format
 *  and a count of 40 printed as `40.0000` (or `40.00` in a property sheet).
 *
 *  The observations are chosen so that no other cell can contain the strings looked for: every value lies
 *  between 1.5 and 1.9, and the weights sum to 20.
 */
class CountFormattingTest {

    private val stat = Statistic("Obs").apply {
        for (i in 0 until 40) collect(1.5 + i * 0.01)
        collect(Double.NaN)
        collect(Double.NaN)
    }

    private val weighted = WeightedStatistic("WObs").apply {
        for (i in 0 until 40) collect(1.5 + i * 0.01, 0.5)
    }

    private val document = report("Counts") {
        statTable(listOf(stat), detail = true)
        statPropertyTable(stat)
        weightedStatTable(listOf(weighted))
        weightedStatPropertyTable(weighted)
    }

    private fun renderAll(doc: ReportNode.Document): Map<String, String> {
        val dir = Files.createTempDirectory("ksl_count_format")
        try {
            val ctx = RenderContext(outputDir = dir, plotDir = dir)
            return mapOf(
                "text" to TextReportRenderer(ctx).also { doc.accept(it) }.result(),
                "markdown" to MarkdownReportRenderer(ctx).also { doc.accept(it) }.result(),
                "html" to HtmlReportRenderer(ctx).also { doc.accept(it) }.result(),
                "latex" to LaTeXReportRenderer(ctx).also { doc.accept(it) }.result()
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun countsAndMissingValuesPrintAsWholeNumbers() {
        assertEquals(40.0, stat.count)
        assertEquals(2.0, stat.numberMissing)
        for ((format, out) in renderAll(document)) {
            assertFalse(out.contains("40.0"), "$format: a count of 40 printed with decimals")
            assertFalse(out.contains("2.0000") || out.contains("2.000"), "$format: a missing count printed with decimals")
            assertTrue(out.contains("40"), "$format: the count is present")
        }
    }

    @Test
    fun fmtCountFallsBackForValuesThatAreNotWhole() {
        val ctx = RenderContext()
        assertEquals("40", ctx.fmtCount(40.0))
        assertEquals("0", ctx.fmtCount(0.0))
        assertEquals(ctx.fmt(2.5), ctx.fmtCount(2.5))
        assertEquals(ctx.fmt(Double.NaN), ctx.fmtCount(Double.NaN))
    }
}
