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

import ksl.controls.experiments.CentralCompositeDesign
import ksl.controls.experiments.Factor
import ksl.controls.experiments.LinearModel
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.experimentalDesign
import ksl.utilities.io.report.extensions.factor
import ksl.utilities.io.report.extensions.linearModel
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser

/**
 * Demonstrates use of the experimental-design reporting framework extensions.
 *
 * Five demos:
 * 1. [demoFactor]                — zero-code [Factor.toReport] and individual `factor()`
 *                                  call for three factors with different level counts
 * 2. [demoTwoLevelFactorialDesign] — full 2³ factorial design; original and coded
 *                                  scale side-by-side via the `coded` flag
 * 3. [demoCentralCompositeDesign] — 2-factor CCD with rotatable axial spacing;
 *                                  shows the CCD-specific configuration sub-section
 *                                  and the Factorial / Axial / Center type column
 * 4. [demoLinearModel]           — three model types (FirstOrder, FirstAndSecond,
 *                                  AllTerms) for a 3-factor experiment; also shows
 *                                  a quadratic and cubic term added manually
 * 5. [demoDesignAndModelReport]   — CCD design and linear model composed together in
 *                                  one document; illustrates the composability pattern
 */
fun main() {
    demoFactor()
//    demoTwoLevelFactorialDesign()
//    demoCentralCompositeDesign()
//    demoLinearModel()
//    demoDesignAndModelReport()
}

// ── Demo 1: Factor ────────────────────────────────────────────────────────────

/**
 * Demonstrates factor-level reporting via [Factor.toReport] (zero-code path) and
 * the granular `factor()` DSL function embedded in a custom `report {}` block.
 *
 * Three factors are defined:
 * - **Temperature** — 3 levels (100 °C, 150 °C, 200 °C)
 * - **Pressure** — 2 levels (0.5 bar, 1.5 bar); a [TwoLevelFactor]
 * - **Time** — 4 levels (30, 60, 90, 120 min)
 *
 * **Illustrates:**
 * - [Factor.toReport] zero-code path
 * - Multiple `factor()` calls composed inside a single `report {}` block
 * - A mix of factor types (2-level, 3-level, 4-level)
 */
fun demoFactor() {
    println("=== Demo 1: Factor ===")

    val myTemp     = Factor("Temperature", doubleArrayOf(100.0, 150.0, 200.0))
    val myPressure = TwoLevelFactor("Pressure", low = 0.5, high = 1.5)
    val myTime     = Factor("Time", doubleArrayOf(30.0, 60.0, 90.0, 120.0))

    // Zero-code path for a single factor
    myTemp.toReport("Temperature Factor").showInBrowser()

    // Multiple factors composed in one report
    val myDoc = report("Process Factors") {
        factor(myTemp)
        factor(myPressure)
        factor(myTime)
    }
    myDoc.showInBrowser()
}

// ── Demo 2: Two-Level Factorial Design ────────────────────────────────────────

/**
 * Demonstrates `experimentalDesign()` reporting for a full 2³ factorial design
 * with three two-level factors representing a chemical process experiment.
 *
 * Factors (original scale):
 * - **Temperature** — low: 150 °C, high: 250 °C
 * - **Pressure** — low: 0.5 bar, high: 1.5 bar
 * - **Catalyst** — low: 1.0 g/L, high: 3.0 g/L
 *
 * The design has 2³ = 8 design points, each run once.
 *
 * **Illustrates:**
 * - [ExperimentalDesignIfc.toReport] zero-code path
 * - `coded = false` (original scale) vs `coded = true` (standardised −1 / +1 scale)
 * - Factor summary table and design point matrix
 */
fun demoTwoLevelFactorialDesign() {
    println("\n=== Demo 2: Two-Level Factorial Design ===")

    val myTemp     = TwoLevelFactor("Temperature", low = 150.0, high = 250.0)
    val myPressure = TwoLevelFactor("Pressure",    low = 0.5,   high = 1.5)
    val myCatalyst = TwoLevelFactor("Catalyst",    low = 1.0,   high = 3.0)

    val myDesign = TwoLevelFactorialDesign(setOf(myTemp, myPressure, myCatalyst),
        name = "Chemical Process 2^3")

    println("Design points: ${myDesign.numDesignPoints}")

    // Original scale (default)
    myDesign.toReport("Chemical Process — 2\u00b3 Factorial (Original Scale)")
        .showInBrowser()

    // Coded scale
    myDesign.toReport("Chemical Process — 2\u00b3 Factorial (Coded Scale)", coded = true)
        .showInBrowser()
}

// ── Demo 3: Central Composite Design ─────────────────────────────────────────

/**
 * Demonstrates `experimentalDesign()` reporting for a 2-factor central composite
 * design (CCD) with rotatably chosen axial spacing.
 *
 * Factors (original scale):
 * - **Temperature** — low: 150 °C, high: 250 °C (mid: 200 °C)
 * - **Pressure**    — low: 0.5 bar, high: 1.5 bar (mid: 1.0 bar)
 *
 * Rotatable axial spacing α = (2²)^(1/4) = √2 ≈ 1.4142.
 * Design layout: 4 factorial + 4 axial + 1 center = 9 total design points.
 *
 * **Illustrates:**
 * - [ExperimentalDesignIfc.toReport] zero-code path on a [CentralCompositeDesign]
 * - CCD-specific configuration sub-section (axial spacing, point counts)
 * - **Type** column (Factorial / Axial / Center) in the design point matrix
 * - `coded = true` showing axial points beyond ±1 on the standardised scale
 */
fun demoCentralCompositeDesign() {
    println("\n=== Demo 3: Central Composite Design ===")

    val myTemp     = TwoLevelFactor("Temperature", low = 150.0, high = 250.0)
    val myPressure = TwoLevelFactor("Pressure",    low = 0.5,   high = 1.5)

    val myAxial = CentralCompositeDesign.rotatableAxialSpacing(numFactors = 2)
    println("Rotatable axial spacing \u03b1 = $myAxial")

    val myCCD = CentralCompositeDesign(
        factors          = setOf(myTemp, myPressure),
        axialSpacing     = myAxial,
        numFactorialReps = 1,
        numAxialReps     = 1,
        numCenterReps    = 3,
        name             = "CCD — 2 Factors"
    )

    // Original scale
    myCCD.toReport("Response Surface — CCD (Original Scale)")
        .showInBrowser()

    // Coded scale: axial points appear beyond ±1
    myCCD.toReport("Response Surface — CCD (Coded Scale)", coded = true)
        .showInBrowser()
}

// ── Demo 4: Linear Model ──────────────────────────────────────────────────────

/**
 * Demonstrates `linearModel()` reporting for three standard model types on a
 * 3-factor experiment (A, B, C) and a manually extended model that includes
 * quadratic and cubic terms.
 *
 * **Illustrates:**
 * - [LinearModel.toReport] zero-code path
 * - [LinearModel.Type.FirstOrder] — main effects only
 * - [LinearModel.Type.FirstAndSecond] — main effects + 2-way interactions
 * - [LinearModel.Type.AllTerms] — full model including 3-way interaction
 * - Manual addition of `quadratic()` and `cubic()` terms
 * - The Order column distinguishing Main Effect, 2-Way Interaction,
 *   3-Way Interaction, Quadratic, and Cubic labels
 */
fun demoLinearModel() {
    println("\n=== Demo 4: Linear Model ===")

    val myFactors = setOf("A", "B", "C")

    // Main effects only
    val myFirstOrder = LinearModel(myFactors, LinearModel.Type.FirstOrder)
    myFirstOrder.toReport("First-Order Model (Main Effects Only)").showInBrowser()

    // Main effects + 2-way interactions
    val myFirstAndSecond = LinearModel(myFactors, LinearModel.Type.FirstAndSecond)
    myFirstAndSecond.toReport("First- and Second-Order Model").showInBrowser()

    // All terms (main + all interactions)
    val myAllTerms = LinearModel(myFactors, LinearModel.Type.AllTerms)
    myAllTerms.toReport("Full Model — All Terms").showInBrowser()

    // Manual extension: first-order + quadratic A + cubic B
    val myCustom = LinearModel(myFactors, LinearModel.Type.FirstOrder)
        .quadratic("A")
        .cubic("B")
        .twoWay("A", "C")
    val myDoc = report("Custom Response Surface Model") {
        linearModel(myCustom, caption = "Custom Model: Main Effects + A² + B³ + AC")
        paragraph(
            "This model is suitable for exploring quadratic curvature in A and " +
            "cubic behaviour in B, while retaining the A×C interaction term."
        )
    }
    myDoc.showInBrowser()
}

// ── Demo 5: Design and Model Report ──────────────────────────────────────────

/**
 * Demonstrates composing [experimentalDesign] and [linearModel] together in a
 * single report document — the recommended pattern when presenting a full
 * response-surface study setup before running simulations.
 *
 * Uses the same 2-factor CCD from [demoCentralCompositeDesign].
 * The linear model is a [LinearModel.Type.FirstAndSecond] specification (main
 * effects + 2-way interaction + quadratic terms) derived directly from the design
 * via `ccd.linearModel()`.
 *
 * **Illustrates:**
 * - Composing `experimentalDesign()` and `linearModel()` in one `report {}` block
 * - Using `design.linearModel(type)` to derive the model specification from the design
 * - Custom title and narrative `paragraph` appended after the model section
 */
fun demoDesignAndModelReport() {
    println("\n=== Demo 5: Composite Report ===")

    val myTemp     = TwoLevelFactor("Temperature", low = 150.0, high = 250.0)
    val myPressure = TwoLevelFactor("Pressure",    low = 0.5,   high = 1.5)
    val myAxial    = CentralCompositeDesign.rotatableAxialSpacing(numFactors = 2)

    val myCCD = CentralCompositeDesign(
        factors          = setOf(myTemp, myPressure),
        axialSpacing     = myAxial,
        numFactorialReps = 1,
        numAxialReps     = 1,
        numCenterReps    = 3,
        name             = "CCD — Temperature × Pressure"
    )

    // Derive the response surface model from the design (main + 2-way + quadratic)
    val myModel = myCCD.linearModel(LinearModel.Type.FirstAndSecond)
        .quadratic("Temperature")
        .quadratic("Pressure")

    val myDoc = report("Response Surface Study — Setup") {
        experimentalDesign(myCCD, coded = true, caption = "Central Composite Design")
        linearModel(myModel, caption = "Response Surface Model")
        paragraph(
            "Design: 2-factor CCD with rotatable axial spacing \u03b1 = ${
                "%.4f".format(myAxial)
            }. " +
            "Model: full second-order response surface including quadratic terms " +
            "for Temperature and Pressure. Ready for simulation execution."
        )
    }
    myDoc.showInBrowser()
}
