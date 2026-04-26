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
import ksl.utilities.io.report.extensions.stateFrequency
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.statistic.StateFrequency

// ── Shared Markov-chain simulation ────────────────────────────────────────────

/**
 * Simulates [nTransitions] steps of a three-state discrete-time Markov chain
 * and returns the resulting [StateFrequency] tabulation.
 *
 * **States:** `State:0` (Idle), `State:1` (Busy), `State:2` (Blocked)
 *
 * **Transition matrix:**
 * ```
 *            → Idle   → Busy   → Blocked
 * Idle       0.20     0.70     0.10
 * Busy       0.30     0.50     0.20
 * Blocked    0.10     0.40     0.50
 * ```
 *
 * The steady-state distribution implied by this matrix is approximately
 * Idle ≈ 22 %, Busy ≈ 54 %, Blocked ≈ 24 %.
 */
private fun machineStateFrequency(nTransitions: Int = 10_000): StateFrequency {
    // One DEmpiricalRV per row of the transition matrix
    val myIdleRV    = DEmpiricalRV(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.20, 0.90, 1.0))
    val myBusyRV    = DEmpiricalRV(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.30, 0.80, 1.0))
    val myBlockedRV = DEmpiricalRV(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.10, 0.50, 1.0))
    val myTransRVs  = arrayOf(myIdleRV, myBusyRV, myBlockedRV)

    val mySf = StateFrequency(numStates = 3, name = "Machine State")
    var myCurrentState = 0
    mySf.collect(mySf.state(myCurrentState))
    repeat(nTransitions - 1) {
        val myNextState = myTransRVs[myCurrentState].value.toInt()
        mySf.collect(mySf.state(myNextState))
        myCurrentState = myNextState
    }
    return mySf
}

// ── Demo 1: Zero-code entry point ─────────────────────────────────────────────

/**
 * Demonstrates [StateFrequency.toReport]: the simplest reporting path using all defaults.
 *
 * Default flags: `showStatistics = true`, `showTransitions = true`,
 * `showTransitionProportions = false`, `showPlot = true`, `proportions = false`.
 *
 * The document contains:
 * - Overview paragraph
 * - State frequency table (State | Count | Cum Count | % | Cum %)
 * - Statistics on observed state numbers (StatPropertyTable via [StateFrequency.statistic])
 * - Transition count matrix DataTable
 * - Bar chart (counts)
 *
 * All four output formats are written to `kslOutput/`.
 */
fun demoStateFrequencyZeroCode() {
    val mySf  = machineStateFrequency(nTransitions = 10_000)
    val myDoc = mySf.toReport(title = "Machine State — Default Report")
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myDoc.writeMarkdown()
    myDoc.writeText()
    println("Zero-code state-frequency report written to kslOutput/")
}

// ── Demo 2: Both transition matrices ──────────────────────────────────────────

/**
 * Demonstrates enabling both [showTransitions] and [showTransitionProportions]
 * to produce the count matrix and the row-normalised proportion matrix side by side.
 *
 * The proportion matrix rows sum to 1.0 for any state that was visited at least once.
 * Comparing the two matrices shows absolute volume (counts) alongside relative
 * routing behaviour (proportions).
 */
fun demoStateFrequencyBothMatrices() {
    val mySf  = machineStateFrequency(nTransitions = 10_000)
    val myDoc = mySf.toReport(
        title                     = "Machine State — Both Transition Matrices",
        showTransitions           = true,
        showTransitionProportions = true
    )
    myDoc.showInBrowser()
    myDoc.writeHtml()
    println("Both-matrices state-frequency report written to kslOutput/")
}

// ── Demo 3: Proportions bar chart, statistics suppressed ──────────────────────

/**
 * Demonstrates two independent flags:
 * - `proportions = true` — bar chart y-axis shows state proportions instead of counts,
 *   making it easier to read the empirical steady-state distribution visually.
 * - `showStatistics = false` — suppresses the StatPropertyTable; numeric statistics
 *   on state *numbers* (0, 1, 2) are not meaningful in isolation, so this flag
 *   removes clutter when the frequency table and plot tell the complete story.
 */
fun demoStateFrequencyProportionsPlot() {
    val mySf  = machineStateFrequency(nTransitions = 10_000)
    val myDoc = mySf.toReport(
        title          = "Machine State — Proportions Chart",
        showStatistics = false,
        proportions    = true
    )
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("Proportions-chart state-frequency report written to kslOutput/")
}

// ── Demo 4: Minimal report — frequency table and chart only ───────────────────

/**
 * Demonstrates the minimal report: only the state frequency table and bar chart,
 * with all other sections suppressed.
 *
 * This layout is useful for presentation slides or executive summaries where
 * full statistical detail is not needed.
 */
fun demoStateFrequencyMinimal() {
    val mySf  = machineStateFrequency(nTransitions = 10_000)
    val myDoc = mySf.toReport(
        title                     = "Machine State — Frequency Summary",
        showStatistics            = false,
        showTransitions           = false,
        showTransitionProportions = false,
        showPlot                  = true,
        proportions               = true
    )
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("Minimal state-frequency report written to kslOutput/")
}

// ── Demo 5: DSL composition — annotated analysis with all sections ─────────────

/**
 * Demonstrates embedding [stateFrequency] inside a hand-crafted [report] block
 * with both transition matrices enabled, analyst commentary, and a second
 * custom section discussing the observed steady-state distribution.
 *
 * This is the most comprehensive demo. It shows:
 * - All five section types: frequency table, statistics, count matrix,
 *   proportion matrix, bar chart
 * - Narrative [paragraph] content before and after the standard section
 * - A custom [stateFrequency] call with all flags set explicitly
 * - [toReport] with a custom block that appends an additional section
 *
 * All four output formats are written to `kslOutput/`.
 */
fun demoStateFrequencyCustomBlock() {
    val mySf = machineStateFrequency(nTransitions = 20_000)

    // ── Composite report: stateFrequency() embedded in a larger document ──────
    val myComposite = report("Machine State Analysis — Annotated Report") {
        paragraph(
            "This report analyses 20,000 transitions of a three-state discrete-time " +
            "Markov chain modelling a single machine subject to blocking. " +
            "States: State:0 = Idle, State:1 = Busy, State:2 = Blocked. " +
            "The theoretical steady-state distribution is approximately " +
            "Idle ≈ 22 %, Busy ≈ 54 %, Blocked ≈ 24 %."
        )

        stateFrequency(
            freq                      = mySf,
            caption                   = "Machine State Frequency",
            confidenceLevel           = 0.95,
            showStatistics            = true,
            showTransitions           = true,
            showTransitionProportions = true,
            showPlot                  = true,
            proportions               = false
        )

        section("Steady-State Comparison") {
            paragraph(
                "Compare the observed proportions in the state frequency table above " +
                "against the theoretical steady-state distribution. With 20,000 " +
                "transitions the empirical proportions should be within a few tenths " +
                "of a percent of the theoretical values. The transition proportion " +
                "matrix rows estimate the one-step conditional transition probabilities " +
                "and should closely match the input transition matrix as sample size " +
                "increases."
            )
            paragraph(
                "Theoretical input matrix row for Idle:    0.20 / 0.70 / 0.10. " +
                "Theoretical input matrix row for Busy:    0.30 / 0.50 / 0.20. " +
                "Theoretical input matrix row for Blocked: 0.10 / 0.40 / 0.50."
            )
        }
    }
    myComposite.showInBrowser()
    myComposite.writeHtml()
    myComposite.writeMarkdown()
    myComposite.writeText()

    // ── toReport() with a custom block ────────────────────────────────────────
    val myAnnotated = mySf.toReport(title = "Machine State — Custom Block Demo") {
        stateFrequency(
            freq                      = mySf,
            showTransitions           = true,
            showTransitionProportions = true,
            proportions               = true
        )
        section("Notes") {
            paragraph("Proportions bar chart facilitates direct visual comparison with steady-state theory.")
        }
    }
    myAnnotated.showInBrowser()
    myAnnotated.writeMarkdown()
    println("Composite and annotated state-frequency reports written to kslOutput/")
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
//    demoStateFrequencyZeroCode()
//    demoStateFrequencyBothMatrices()
//    demoStateFrequencyProportionsPlot()
//    demoStateFrequencyMinimal()
    demoStateFrequencyCustomBlock()
}
