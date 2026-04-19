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

import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.LinearModel
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.chapter7.RQInventorySystem
import ksl.simulation.Model
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.designedExperiment
import ksl.utilities.io.report.extensions.linearModel
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Demonstrates use of the designed-experiment reporting framework extensions.
 *
 * Model: (r, Q) inventory system ([RQInventorySystem]) with Poisson demand
 * (mean 3.6 units/month), constant lead time (0.5 months), and a cost
 * structure of ordering + holding + backorder costs.
 *
 * Two factors define the design:
 * - **ReorderLevel (r)** — low: 1, high: 5 (control: `"RQInventory:Item.initialReorderPoint"`)
 * - **ReorderQty (Q)** — low: 1, high: 7 (control: `"RQInventory:Item.initialReorderQty"`)
 *
 * Response of primary interest: `"RQInventory:Item:TotalCost"` (total cost per time unit).
 *
 * Three demos:
 * 1. [demoDesignPreRun]          — zero-code [DesignedExperiment.toReport] before any
 *                                  design points have been simulated; shows the design
 *                                  structure and responses list, gracefully noting no runs
 * 2. [demoDesignPostRun]         — zero-code [DesignedExperiment.toReport] after
 *                                  `simulateAll()`; all six sections rendered including
 *                                  per-response `StatTable` across design points
 * 3. [demoDesignCustomBlock]     — custom `report {}` block composing [designedExperiment]
 *                                  with [linearModel] and a narrative paragraph;
 *                                  illustrates the composability pattern for RSM studies
 */
fun main() {
    demoDesignPreRun()
//    demoDesignPostRun()
//    demoDesignCustomBlock()
}

// ── Shared model and design factory ──────────────────────────────────────────

/**
 * Returns the two [TwoLevelFactor] instances used in both demos.
 *
 * - **ReorderLevel** — low: 1.0 unit, high: 5.0 units
 * - **ReorderQty** — low: 1.0 unit, high: 7.0 units
 */
private fun buildFactors(): Pair<TwoLevelFactor, TwoLevelFactor> {
    val myR = TwoLevelFactor("ReorderLevel", low = 1.0, high = 5.0)
    val myQ = TwoLevelFactor("ReorderQty",   low = 1.0, high = 7.0)
    return myR to myQ
}

/**
 * Builds the (r, Q) inventory model.
 *
 * Settings: 30 replications, run length = 72 months, warm-up = 12 months.
 * Demand: ExponentialRV(mean = 1.0 / 3.6) inter-arrival times (≈ 3.6 units/month).
 * Lead time: ConstantRV(0.5) months.
 * Costs: ordering = $0.15/order, holding = $0.25/unit/month,
 * backorder = $1.75/unit/month.
 *
 * Factor-to-control mappings (from [buildFactors]):
 * - `ReorderLevel` → `"RQInventory:Item.initialReorderPoint"`
 * - `ReorderQty`   → `"RQInventory:Item.initialReorderQty"`
 *
 * Primary response: `"RQInventory:Item:TotalCost"` (total cost per month).
 */
private fun buildInventoryModel(name: String = "RQ_Model"): Model {
    val myModel = Model(name, autoCSVReports = false)
    val myRQ    = RQInventorySystem(myModel, name = "RQInventory")
    myRQ.costPerOrder      = 0.15
    myRQ.unitHoldingCost   = 0.25
    myRQ.unitBackorderCost = 1.75
    myRQ.initialReorderPoint = 2
    myRQ.initialReorderQty   = 3
    myRQ.initialOnHand = myRQ.initialReorderPoint + myRQ.initialReorderQty
    myRQ.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
    myRQ.leadTime.initialRandomSource = ConstantRV(0.5)
    myModel.lengthOfReplication        = 72.0
    myModel.lengthOfReplicationWarmUp  = 12.0
    myModel.numberOfReplications       = 30
    return myModel
}

/**
 * Builds the [TwoLevelFactorialDesign] (2² = 4 design points) and maps factors
 * to their model control names.
 */
private fun buildDesignedExperiment(
    experimentName: String = "RQ_Factorial_2x2"
): DesignedExperiment {
    val (myR, myQ) = buildFactors()
    val myDesign   = TwoLevelFactorialDesign(setOf(myR, myQ))
    val mySettings = mapOf(
        myR to "RQInventory:Item.initialReorderPoint",
        myQ to "RQInventory:Item.initialReorderQty"
    )
    val myModel = buildInventoryModel()
    return DesignedExperiment(experimentName, myModel, mySettings, myDesign)
}

// ── Demo 1: Pre-Run Report ────────────────────────────────────────────────────

/**
 * Demonstrates [DesignedExperiment.toReport] before any design points have been
 * executed. Only the design structure and model responses are shown; the
 * execution summary and response statistics sections are omitted gracefully
 * with an informative paragraph.
 *
 * **Illustrates:**
 * - [DesignedExperiment.toReport] zero-code path on an un-run experiment
 * - Design structure section (factor summary, design point matrix)
 * - Model responses list
 * - Graceful "no runs executed" paragraph
 */
fun demoDesignPreRun() {
    println("=== Demo 1: Pre-Run Report ===")

    val myDE = buildDesignedExperiment("RQ_PreRun_Demo")

    println("Design points: ${myDE.design.designPoints().size}")
    println("Responses    : ${myDE.responseNames}")
    println("Runs executed: ${myDE.numSimulationRuns}")

    myDE.toReport("(r,Q) Inventory \u2014 Design Preview (Before Execution)").showInBrowser()
}

// ── Demo 2: Post-Run Report ───────────────────────────────────────────────────

/**
 * Demonstrates [DesignedExperiment.toReport] after all 4 design points have
 * been simulated via `simulateAll()`.
 *
 * The report shows:
 * - Design structure (2² factorial, original and coded scales)
 * - Execution summary table: 4 rows, one per design point, with control values
 * - Per-response `StatTable` sections: each row = one design point; statistics
 *   are computed across 10 replications at the 95% confidence level
 *
 * Both original-scale and coded-scale versions of the report are produced.
 *
 * **Illustrates:**
 * - [DesignedExperiment.toReport] zero-code path after execution
 * - `coded = false` (original scale) vs `coded = true` (coded scale)
 * - `showDetails = true` to include per-design-point simulation run sections
 * - Execution summary with control-name columns (ReorderPoint and ReorderQty values)
 * - Per-response `StatTable` comparing all 4 design points
 */
fun demoDesignPostRun() {
    println("\n=== Demo 2: Post-Run Report ===")

    val myDE = buildDesignedExperiment("RQ_PostRun_Demo")

    println("Running all ${myDE.design.designPoints().size} design points (10 reps each)...")
    myDE.simulateAll(numRepsPerDesignPoint = 10)
    println("Simulation complete. Runs executed: ${myDE.numSimulationRuns}")

    // Original scale (default)
    myDE.toReport(
        title       = "(r,Q) Inventory \u2014 2\u00b2 Factorial (Original Scale)",
        coded       = false,
        showDetails = false
    ).showInBrowser()

    // Coded scale
    myDE.toReport(
        title       = "(r,Q) Inventory \u2014 2\u00b2 Factorial (Coded Scale)",
        coded       = true,
        showDetails = false
    ).showInBrowser()

    // Coded scale with per-point details (full audit trail)
    myDE.toReport(
        title       = "(r,Q) Inventory \u2014 2\u00b2 Factorial (Full Detail)",
        coded       = true,
        showDetails = true
    ).showInBrowser()
}

// ── Demo 3: Custom Block (Design + Linear Model) ──────────────────────────────

/**
 * Demonstrates composing [designedExperiment] and [linearModel] in a single
 * `report {}` block — the recommended pattern for a complete response-surface
 * study setup and results document.
 *
 * The linear model is derived from the design via `design.linearModel(AllTerms)`,
 * which includes both main effects and the two-way interaction for a 2-factor design.
 *
 * **Illustrates:**
 * - Composing `designedExperiment()` and `linearModel()` in one `report {}` block
 * - Using `design.linearModel(type)` to derive the model specification
 * - Narrative `paragraph` appended after both sections
 * - Typical RSM workflow: design → simulate → report → fit model
 */
fun demoDesignCustomBlock() {
    println("\n=== Demo 3: Custom Block (Design + Linear Model) ===")

    val myDE = buildDesignedExperiment("RQ_RSM_Demo")

    println("Running all design points (20 reps each)...")
    myDE.simulateAll(numRepsPerDesignPoint = 20)
    println("Simulation complete.")

    // Derive the full second-order model from the design
    val myLM = myDE.design.linearModel(LinearModel.Type.AllTerms)

    val myDoc = report("(r,Q) Inventory \u2014 Response Surface Study") {
        designedExperiment(
            de              = myDE,
            confidenceLevel = 0.95,
            coded           = true,
            caption         = "2\u00b2 Factorial Experiment Results"
        )
        linearModel(
            model   = myLM,
            caption = "Response Surface Model Specification"
        )
        paragraph(
            "The 2\u00b2 factorial design evaluates all combinations of two reorder " +
            "policy parameters at their low and high settings. The coded design " +
            "standardises factor levels to \u22121 and +1. " +
            "The linear model above specifies main effects plus the r\u00d7Q interaction " +
            "term for fitting a first-order response surface to the total cost response. " +
            "Use `de.regressionResults(responseName, linearModel)` to fit the OLS model."
        )
    }
    myDoc.showInBrowser()
}
