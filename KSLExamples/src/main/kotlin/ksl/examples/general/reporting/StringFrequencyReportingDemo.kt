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

package ksl.examples.general.reporting

import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.stringFrequency
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.random.robj.DEmpiricalList
import ksl.utilities.statistic.StringFrequency

// ── Shared data-generation helpers ────────────────────────────────────────────

/**
 * Samples [n] classification outcomes from a four-category confusion-matrix
 * distribution and returns a [StringFrequency] tabulation.
 *
 * **Category probabilities:**
 * - `"TP"` — true positive  (~60 %)
 * - `"FP"` — false positive (~15 %)
 * - `"FN"` — false negative (~10 %)
 * - `"TN"` — true negative  (~15 %)
 */
private fun classificationFrequency(n: Int = 200): StringFrequency {
    val myCategories = listOf("TP", "FP", "FN", "TN")
    val myList = DEmpiricalList<String>(myCategories, doubleArrayOf(0.60, 0.75, 0.85, 1.0))
    return StringFrequency(data = myList.sample(n), name = "Classification Outcome")
}

/**
 * Samples [n] shift assignments from a three-category uniform distribution
 * and returns a [StringFrequency] tabulation.
 *
 * **Categories:** `"Morning"`, `"Afternoon"`, `"Night"` (roughly equal probability)
 */
private fun shiftFrequency(n: Int = 300): StringFrequency {
    val myShifts = listOf("Morning", "Afternoon", "Night")
    val myList = DEmpiricalList<String>(myShifts, doubleArrayOf(0.333, 0.667, 1.0))
    return StringFrequency(data = myList.sample(n), name = "Shift Assignment")
}

// ── Demo 1: Zero-code entry point ─────────────────────────────────────────────

/**
 * Demonstrates [StringFrequency.toReport]: the simplest reporting path.
 *
 * A single call on the frequency object produces a document containing:
 * - Overview paragraph (distinct count, total)
 * - Frequency table (String | Count | Cum Count | % | Cum %)
 * - Bar chart (counts on y-axis)
 *
 * All four output formats are written to `kslOutput/`.
 */
fun demoStringFrequencyZeroCode() {
    val mySf  = classificationFrequency(n = 200)
    val myDoc = mySf.toReport(title = "Classification Outcome — Frequency Report")
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myDoc.writeMarkdown()
    myDoc.writeText()
    println("Zero-code string-frequency report written to kslOutput/")
}

// ── Demo 2: Count vs. proportions bar chart ────────────────────────────────────

/**
 * Demonstrates the [proportions] flag on [StringFrequency.toReport].
 *
 * Two documents are produced from the same shift-assignment data:
 * - `proportions = false` (default) — bar chart y-axis shows raw counts
 * - `proportions = true`            — bar chart y-axis shows proportions [0, 1]
 *
 * The frequency table content is identical in both; only the plot changes.
 */
fun demoStringFrequencyProportionsPlot() {
    val mySf = shiftFrequency(n = 300)

    val myCountDoc = mySf.toReport(
        title      = "Shift Assignment — Counts",
        proportions = false
    )
    myCountDoc.showInBrowser()
    myCountDoc.writeHtml()

    val myPropDoc = mySf.toReport(
        title       = "Shift Assignment — Proportions",
        proportions = true
    )
    myPropDoc.showInBrowser()
    myPropDoc.writeHtml()
    println("Count and proportion bar-chart reports written to kslOutput/")
}

// ── Demo 3: Limit set — capturing "other" observations ────────────────────────

/**
 * Demonstrates [StringFrequency] with a `limitSet` that restricts which values
 * are tabulated, routing everything else to [StringFrequency.otherCount].
 *
 * **Scenario:** a job-routing log contains four sanctioned job types
 * (`"Design"`, `"Fabrication"`, `"Assembly"`, `"Testing"`) but also records
 * `"Rework"` and `"Idle"` entries that are not part of the limit set. Those
 * unsanctioned entries accumulate in `otherCount` and appear as
 * `"Other: N"` in the report's overview paragraph.
 */
fun demoStringFrequencyLimitSet() {
    val myAllTypes  = listOf("Design", "Fabrication", "Assembly", "Testing", "Rework", "Idle")
    val myList      = DEmpiricalList<String>(myAllTypes, doubleArrayOf(0.25, 0.55, 0.75, 0.88, 0.95, 1.0))
    val myLimitSet  = setOf("Design", "Fabrication", "Assembly", "Testing")
    val mySf        = StringFrequency(
        data     = myList.sample(300),
        name     = "Job Type Distribution",
        limitSet = myLimitSet
    )

    val myDoc = mySf.toReport(title = "Job Type Distribution — With Limit Set")
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("Limit-set string-frequency report written to kslOutput/")
}

// ── Demo 4: DSL composition — two frequencies in one document ─────────────────

/**
 * Demonstrates embedding [stringFrequency] inside a hand-crafted [report] block
 * to combine two independent [StringFrequency] objects in a single document.
 *
 * This is the most comprehensive demo. It shows:
 * - Two [stringFrequency] sections side-by-side under separate top-level sections
 * - Narrative [paragraph] content interspersed between sections
 * - A custom [stringFrequency] call that renders proportions on the bar chart
 * - A custom DSL block passed to [StringFrequency.toReport] to demonstrate
 *   how to add commentary after the standard section
 *
 * All four output formats are written to `kslOutput/`.
 */
fun demoStringFrequencyCustomBlock() {
    val myClassSf = classificationFrequency(n = 500)
    val myShiftSf = shiftFrequency(n = 500)

    // ── Composite report: two frequencies in one document ────────────────────
    val myComposite = report("Operational Summary — Classification and Shift Data") {
        paragraph(
            "This report covers two categorical distributions collected over 500 " +
            "simulated production runs. Section 1 tabulates model classification " +
            "outcomes. Section 2 tabulates shift assignments. Both sections include " +
            "frequency tables with cumulative columns and bar charts."
        )

        section("Section 1 — Classification Outcomes") {
            paragraph(
                "Four confusion-matrix categories observed across 500 runs. " +
                "True positives dominate; false negatives and true negatives " +
                "appear in roughly equal proportions."
            )
            stringFrequency(
                freq        = myClassSf,
                caption     = "Classification Outcome",
                showPlot    = true,
                proportions = false
            )
        }

        section("Section 2 — Shift Assignments") {
            paragraph(
                "Three shifts sampled with approximately equal probability. " +
                "The bar chart below shows proportions to facilitate comparison " +
                "across shifts despite equal expected frequencies."
            )
            stringFrequency(
                freq        = myShiftSf,
                caption     = "Shift Assignment",
                showPlot    = true,
                proportions = true
            )
        }
    }
    myComposite.showInBrowser()
    myComposite.writeHtml()
    myComposite.writeMarkdown()
    myComposite.writeText()

    // ── toReport() with a custom block — adds commentary after the standard section ──
    val myAnnotated = myClassSf.toReport(title = "Classification Outcomes — Annotated") {
        stringFrequency(myClassSf)
        section("Analyst Notes") {
            paragraph(
                "The false-positive rate (FP proportion) should remain below 20 %. " +
                "Review the confusion matrix if FP or FN counts exceed threshold."
            )
        }
    }
    myAnnotated.showInBrowser()
    myAnnotated.writeMarkdown()
    println("Composite and annotated string-frequency reports written to kslOutput/")
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
//    demoStringFrequencyZeroCode()
//    demoStringFrequencyProportionsPlot()
//    demoStringFrequencyLimitSet()
    demoStringFrequencyCustomBlock()
}
