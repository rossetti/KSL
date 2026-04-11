/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.simulation.Model
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.*
import ksl.utilities.io.report.renderer.HtmlReportRenderer
import ksl.utilities.io.report.renderer.MarkdownReportRenderer
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.WeightedStatistic
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ReportingFrameworkTest {

    // ── Test 1: DSL constructs the correct AST shape ─────────────────────────

    @Test
    fun `report dsl produces Document with expected node structure`() {
        val myDoc = report("Test Report") {
            paragraph("Intro paragraph.")
            section("Section A") {
                paragraph("Inside A.")
            }
            section("Section B") {
                paragraph("Inside B.")
                section("Nested") {
                    paragraph("Deep.")
                }
            }
        }

        assertTrue(myDoc is ReportNode.Document)
        assertTrue(myDoc.title == "Test Report")
        assertTrue(myDoc.children.size == 3) // paragraph + 2 sections
        val mySectionA = myDoc.children[1] as ReportNode.Section
        assertTrue(mySectionA.title == "Section A")
        val mySectionB = myDoc.children[2] as ReportNode.Section
        assertTrue(mySectionB.children.size == 2) // paragraph + nested section
    }

    // ── Test 2: Statistic extension renders without errors ───────────────────

    @Test
    fun `statistic extension renders to non-empty text`() {
        val myStat = Statistic("Wait Time")
        repeat(30) { myStat.collect(it.toDouble() * 0.5) }

        val myDoc = report("Statistic Test") {
            statistic(myStat, detail = false)
        }

        val myText = myDoc.renderToText()
        assertFalse(myText.isBlank(), "Text output should not be blank")
        assertTrue(myText.contains("Wait Time"), "Output should contain statistic name")
    }

    @Test
    fun `statistic extension detail mode renders to non-empty text`() {
        val myStat = Statistic("Service Time")
        repeat(50) { myStat.collect(Math.random() * 10.0) }

        val myDoc = report("Detail Test") {
            statistic(myStat, detail = true)
        }

        val myText = myDoc.renderToText()
        assertFalse(myText.isBlank())
        assertTrue(myText.contains("Service Time"))
    }

    // ── Test 3: Histogram extension ──────────────────────────────────────────

    @Test
    fun `histogram extension renders bin table and stats`() {
        val myBreakpoints = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
        val myHist = Histogram(myBreakpoints, name = "Wait Time Histogram")
        repeat(100) { myHist.collect(Math.random() * 5.0) }

        val myDoc = report("Histogram Test") {
            histogram(myHist)
        }

        val myText = myDoc.renderToText()
        assertFalse(myText.isBlank())
        assertTrue(myText.contains("Wait Time Histogram"))
        assertTrue(myText.contains("Bin Frequencies"))
    }

    // ── Test 4: IntegerFrequency extension ───────────────────────────────────

    @Test
    fun `integerFrequency extension renders frequency table and stats`() {
        val myFreq = IntegerFrequency(name = "Server Count")
        repeat(200) { myFreq.collect((Math.random() * 5).toInt()) }

        val myDoc = report("Frequency Test") {
            integerFrequency(myFreq)
        }

        val myText = myDoc.renderToText()
        assertFalse(myText.isBlank())
        assertTrue(myText.contains("Server Count"))
        assertTrue(myText.contains("Frequency Table"))
    }

    // ── Test 5: BatchStatistic extension ─────────────────────────────────────

    @Test
    fun `batchStatistic extension renders configuration and stats`() {
        val myBs = BatchStatistic(name = "Queue Length")
        repeat(500) { myBs.collect(Math.random() * 10.0) }

        val myDoc = report("Batch Statistic Test") {
            batchStatistic(myBs)
        }

        val myText = myDoc.renderToText()
        assertFalse(myText.isBlank())
        assertTrue(myText.contains("Queue Length"))
        assertTrue(myText.contains("Batch Configuration"))
    }

    // ── Test 6: WeightedStatistic extension ──────────────────────────────────

    @Test
    fun `weightedStatistic extension renders weighted table`() {
        val myWs = WeightedStatistic("Time-Weighted Utilisation")
        repeat(50) { myWs.collect(Math.random(), Math.random() * 2.0) }

        val myDoc = report("Weighted Statistic Test") {
            weightedStatistic(myWs)
        }

        val myText = myDoc.renderToText()
        assertFalse(myText.isBlank())
        assertTrue(myText.contains("Time-Weighted Utilisation"))
    }

    // ── Test 7: Simulation report with DriveThroughPharmacyWithQ ─────────────

    @Test
    fun `simulationResults extension produces a full report for a simulated model`() {
        val myModel = Model("Drive-Through Pharmacy")
        myModel.numberOfReplications = 10
        myModel.lengthOfReplication = 480.0
        myModel.lengthOfReplicationWarmUp = 50.0
        DriveThroughPharmacyWithQ(myModel)
        myModel.simulate()

        val myDoc = myModel.buildReport("Drive-Through Pharmacy Results")

        assertNotNull(myDoc)
        assertTrue(myDoc.title == "Drive-Through Pharmacy Results")

        // Document must have at least the Simulation Summary section
        assertTrue(myDoc.children.isNotEmpty(), "Document should have at least one child")

        // Text render should contain expected response names
        val myText = myDoc.renderToText()
        assertFalse(myText.isBlank())
        assertTrue(myText.contains("System Time") || myText.contains("Num"),
            "Output should contain simulation response names")
    }

    @Test
    fun `simulationResults HTML render contains table and document structure`() {
        val myModel = Model("HTML Render Test")
        myModel.numberOfReplications = 5
        myModel.lengthOfReplication = 240.0
        DriveThroughPharmacyWithQ(myModel)
        myModel.simulate()

        val myDoc = myModel.buildReport("HTML Render Test")
        val myHtml = myDoc.renderToHtml()

        assertFalse(myHtml.isBlank())
        assertTrue(myHtml.contains("<html"), "HTML output should start with html tag")
        assertTrue(myHtml.contains("<table"), "HTML output should contain tables")
        assertTrue(myHtml.contains("HTML Render Test"), "HTML output should contain document title")
    }

    // ── Test 8: Markdown renderer ─────────────────────────────────────────────

    @Test
    fun `Markdown render contains headings and table markers`() {
        val myStat = Statistic("Throughput")
        repeat(20) { myStat.collect(it.toDouble()) }

        val myDoc = report("Markdown Test") {
            statistic(myStat)
        }

        val myCtx = RenderContext()
        val myRenderer = MarkdownReportRenderer(myCtx)
        myDoc.accept(myRenderer)
        val myMd = myRenderer.result()

        assertFalse(myMd.isBlank())
        assertTrue(myMd.contains("# Markdown Test"), "Markdown should contain h1 title")
        assertTrue(myMd.contains("|"), "Markdown should contain table pipes")
    }

    // ── Test 9: KSLReport convenience extension writes files ─────────────────

    @Test
    fun `writeText creates a non-empty text file`() {
        val myStat = Statistic("Test Stat")
        repeat(30) { myStat.collect(Math.random()) }

        val myDoc = report("File Write Test") {
            statistic(myStat)
        }

        val myTmpDir = Files.createTempDirectory("ksl_report_test")
        val myFile = myDoc.writeText(myTmpDir.resolve("test_report.txt"))

        assertTrue(myFile.exists(), "Output file should exist")
        assertTrue(myFile.length() > 0, "Output file should not be empty")
        myFile.delete()
        myTmpDir.toFile().delete()
    }

    @Test
    fun `writeHtml creates a non-empty HTML file`() {
        val myStat = Statistic("HTML Stat")
        repeat(30) { myStat.collect(Math.random()) }

        val myDoc = report("HTML File Test") {
            statistic(myStat)
        }

        val myTmpDir = Files.createTempDirectory("ksl_report_html")
        val myCtx = RenderContext(outputDir = myTmpDir, plotDir = myTmpDir)
        val myFile = myDoc.writeHtml(myTmpDir.resolve("test_report.html"), myCtx)

        assertTrue(myFile.exists(), "HTML file should exist")
        assertTrue(myFile.length() > 0, "HTML file should not be empty")
        myFile.delete()
        myTmpDir.toFile().deleteRecursively()
    }

    @Test
    fun `writeMarkdown creates a non-empty Markdown file`() {
        val myStat = Statistic("MD Stat")
        repeat(30) { myStat.collect(Math.random()) }

        val myDoc = report("Markdown File Test") {
            statistic(myStat)
        }

        val myTmpDir = Files.createTempDirectory("ksl_report_md")
        val myFile = myDoc.writeMarkdown(myTmpDir.resolve("test_report.md"))

        assertTrue(myFile.exists(), "Markdown file should exist")
        assertTrue(myFile.length() > 0, "Markdown file should not be empty")
        myFile.delete()
        myTmpDir.toFile().delete()
    }

    // ── Private render helpers ────────────────────────────────────────────────

    private fun ReportNode.Document.renderToText(): String {
        val myCtx = RenderContext()
        val myRenderer = TextReportRenderer(myCtx)
        accept(myRenderer)
        return myRenderer.result()
    }

    private fun ReportNode.Document.renderToHtml(): String {
        val myTmpDir = Files.createTempDirectory("ksl_html_render")
        val myCtx = RenderContext(outputDir = myTmpDir, plotDir = myTmpDir)
        val myRenderer = HtmlReportRenderer(myCtx)
        accept(myRenderer)
        val myResult = myRenderer.result()
        myTmpDir.toFile().deleteRecursively()
        return myResult
    }
}
