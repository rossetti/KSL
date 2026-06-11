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

package ksl.utilities.io.report.extensions

import ksl.controls.experiments.CentralCompositeDesign
import ksl.controls.experiments.ExperimentalDesignIfc
import ksl.controls.experiments.Factor
import ksl.controls.experiments.LinearModel
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report

/**
 * DSL extension functions on [ReportBuilder] for rendering experimental-design
 * structures — [Factor], [ExperimentalDesignIfc], and [LinearModel] — within the
 * KSL reporting framework.
 *
 * **Granular building blocks:**
 * - [factor] — full property sheet for a single factor (levels, range, midpoint,
 *   half-range, coded levels)
 * - [experimentalDesign] — overview paragraph, optional design-type configuration
 *   block (CCD only), factor summary table, design-point matrix in original or coded
 *   scale with a Type column (Factorial / Axial / Center) for [CentralCompositeDesign]
 * - [linearModel] — terms table showing term key, component factors, and order label
 *   (Main Effect, 2-Way Interaction, Quadratic, etc.)
 *
 * **Zero-code entry points:**
 * - [Factor.toReport] — full report via [factor]
 * - [ExperimentalDesignIfc.toReport] — full report via [experimentalDesign]
 * - [LinearModel.toReport] — full report via [linearModel]
 *
 * **Composability example — design and model in one document:**
 * ```kotlin
 * val doc = report("Response Surface Study") {
 *     experimentalDesign(ccd, coded = true)
 *     linearModel(ccd.linearModel(LinearModel.Type.FirstAndSecond))
 * }
 * doc.showInBrowser()
 * ```
 */

// ── DSL Function 1: Factor ────────────────────────────────────────────────────

/**
 * Appends a single section describing all properties of [factor].
 *
 * **Produces (inside a section titled `caption` or the factor's name):**
 * - `DataTable` (headers: Property | Value): name, levels (comma-separated),
 *   number of levels, low, high, mid point, half range, coded levels (comma-separated)
 *
 * This building block is usable before any simulation has been run — it reports the
 * current configuration of the factor only. Call it from inside a `section {}` block
 * or at the top level of a `report {}` block.
 *
 * @param factor  the [Factor] to report
 * @param caption optional section title; defaults to the factor's name
 */
fun ReportBuilder.factor(factor: Factor, caption: String? = null) {
    val myRows = listOf(
        listOf("Name",             factor.name),
        listOf("Levels",           factor.levels.joinToString(", ") { fmtDouble(it) }),
        listOf("Number of Levels", factor.levels.size.toString()),
        listOf("Low",              fmtDouble(factor.levels.first())),
        listOf("High",             fmtDouble(factor.levels.last())),
        listOf("Mid Point",        fmtDouble(factor.midPoint)),
        listOf("Half Range",       fmtDouble(factor.halfRange)),
        listOf("Coded Levels",     factor.codedLevels.joinToString(", ") { fmtDouble(it) })
    )
    section(caption ?: factor.name) {
        dataTable(
            headers = listOf("Property", "Value"),
            rows    = myRows,
            caption = caption ?: factor.name
        )
    }
}

// ── DSL Function 2: Experimental Design ──────────────────────────────────────

/**
 * Appends a self-contained section describing the complete structure of [design].
 *
 * **Produces (inside a section titled `caption` or `"Experimental Design"`):**
 * 1. Overview paragraph — design type, number of factors, number of design points,
 *    and display scale
 * 2. *(CCD only)* **Central Composite Design Configuration** sub-section — factorial
 *    reps, axial reps, center reps, axial spacing α, factorial point count,
 *    axial point count (2k)
 * 3. **Factors** sub-section — `DataTable` (Factor | # Levels | Low | High |
 *    Mid Point | Half Range | Coded Levels), one row per factor
 * 4. **Design Points** sub-section — `DataTable` (Point | Reps | `Type` |
 *    Factor₁ | … | Factorₙ); the `Type` column (Factorial / Axial / Center)
 *    is present only when [design] is a [CentralCompositeDesign]; factor values
 *    are on the [coded] scale when `coded = true`, otherwise the original scale
 *
 * @param design  the [ExperimentalDesignIfc] to report
 * @param coded   `false` (default) = original measurement scale;
 *                `true` = coded (standardised −1/+1) scale
 * @param caption optional section title; defaults to `"Experimental Design"`
 */
fun ReportBuilder.experimentalDesign(
    design: ExperimentalDesignIfc,
    coded: Boolean = false,
    caption: String? = null
) {
    val myDesignPoints = design.designPoints()
    val myTitle        = caption ?: "Experimental Design"
    val myScaleLabel   = if (coded) "Coded" else "Original"

    section(myTitle) {

        // ── Overview paragraph ────────────────────────────────────────────────
        paragraph(
            "Design type: ${design.javaClass.simpleName}  |  " +
            "Factors: ${design.numFactors}  |  " +
            "Design points: ${myDesignPoints.size}  |  " +
            "Scale: $myScaleLabel"
        )

        // ── CCD-specific configuration block ─────────────────────────────────
        if (design is CentralCompositeDesign) {
            section("Central Composite Design Configuration") {
                val myCCDRows = listOf(
                    listOf("Factorial Replications", design.numFactorialReps.toString()),
                    listOf("Axial Replications",     design.numAxialReps.toString()),
                    listOf("Center Replications",    design.numCenterReps.toString()),
                    listOf("Axial Spacing (\u03b1)", fmtDouble(design.axialSpacing)),
                    listOf("Factorial Points",       design.numFactorialPoints.toString()),
                    listOf("Axial Points (2k)",      design.numAxialPoints.toString())
                )
                dataTable(
                    headers = listOf("Parameter", "Value"),
                    rows    = myCCDRows,
                    caption = "CCD Configuration"
                )
            }
        }

        // ── Factor summary table ──────────────────────────────────────────────
        section("Factors") {
            val myFactorHeaders = listOf(
                "Factor", "# Levels", "Low", "High", "Mid Point", "Half Range", "Coded Levels"
            )
            val myFactorRows = design.factors.values.map { f ->
                listOf(
                    f.name,
                    f.levels.size.toString(),
                    fmtDouble(f.levels.first()),
                    fmtDouble(f.levels.last()),
                    fmtDouble(f.midPoint),
                    fmtDouble(f.halfRange),
                    f.codedLevels.joinToString(", ") { fmtDouble(it) }
                )
            }
            dataTable(
                headers = myFactorHeaders,
                rows    = myFactorRows,
                caption = "Factor Summary"
            )
        }

        // ── Design point matrix ───────────────────────────────────────────────
        val myIsCCD  = design is CentralCompositeDesign
        val myNumFac = if (myIsCCD) (design as CentralCompositeDesign).numFactorialPoints else 0
        val myNumAx  = if (myIsCCD) design.numAxialPoints else 0

        section("Design Points ($myScaleLabel Scale)") {
            val myHeaders = buildList {
                add("Point")
                add("Reps")
                if (myIsCCD) add("Type")
                addAll(design.factorNames)
            }
            val myRows = myDesignPoints.mapIndexed { idx, dp ->
                val myValues = if (coded) dp.codedValues() else dp.values()
                buildList {
                    add(dp.number.toString())
                    add(dp.numReplications.toString())
                    if (myIsCCD) {
                        add(
                            when {
                                idx < myNumFac           -> "Factorial"
                                idx < myNumFac + myNumAx -> "Axial"
                                else                     -> "Center"
                            }
                        )
                    }
                    addAll(myValues.map { fmtDouble(it) })
                }
            }
            dataTable(
                headers = myHeaders,
                rows    = myRows,
                caption = "Design Points ($myScaleLabel Scale)"
            )
        }
    }
}

// ── DSL Function 3: Linear Model ─────────────────────────────────────────────

/**
 * Appends a single section describing the terms of [model].
 *
 * **Produces (inside a section titled `caption` or `"Linear Model"`):**
 * - Overview paragraph — main effects, term count, intercept flag
 * - `DataTable` (headers: Term | Components | Order): one row per term, sorted by
 *   complexity (main effects first, then interactions by term length); the Term
 *   column uses the standard `A*B` key notation; Components shows the factors joined
 *   with `×`; Order is a human-readable label (`Main Effect`, `2-Way Interaction`,
 *   `Quadratic`, `Cubic`, etc.)
 *
 * @param model   the [LinearModel] to report
 * @param caption optional section title; defaults to `"Linear Model"`
 */
fun ReportBuilder.linearModel(model: LinearModel, caption: String? = null) {
    section(caption ?: "Linear Model") {

        paragraph(
            "Main effects: ${model.mainEffects.sorted().joinToString(", ")}  |  " +
            "Terms: ${model.termsAsMap.size}  |  " +
            "Intercept: ${model.intercept}"
        )

        val myTermRows = model.termsAsMap.entries
            .sortedWith(compareBy({ it.value.size }, { it.key }))
            .map { (key, components) ->
                listOf(
                    key,
                    components.joinToString(" \u00d7 "),
                    termOrder(components)
                )
            }
        dataTable(
            headers = listOf("Term", "Components", "Order"),
            rows    = myTermRows,
            caption = caption ?: "Linear Model Terms"
        )
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a full experimental-design report
 * via [experimentalDesign].
 *
 * Zero-code path:
 * ```kotlin
 * val fd = FactorialDesign(setOf(factorA, factorB, factorC))
 * fd.toReport("My Factorial Design").showInBrowser()
 * ```
 *
 * Custom block (design on coded scale + linear model in one document):
 * ```kotlin
 * ccd.toReport("Response Surface Setup") {
 *     experimentalDesign(ccd, coded = true)
 *     linearModel(ccd.linearModel(LinearModel.Type.FirstAndSecond))
 * }
 * ```
 *
 * @param title  document title; defaults to `"Experimental Design"`
 * @param coded  `false` (default) = original scale; `true` = coded scale
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun ExperimentalDesignIfc.toReport(
    title: String = "Experimental Design",
    coded: Boolean = false,
    block: ReportBuilder.() -> Unit = { experimentalDesign(this@toReport, coded) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full factor report via [factor].
 *
 * Zero-code path:
 * ```kotlin
 * val f = Factor("Temperature", doubleArrayOf(100.0, 150.0, 200.0))
 * f.toReport().showInBrowser()
 * ```
 *
 * @param title  document title; defaults to the factor's name
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun Factor.toReport(
    title: String = name,
    block: ReportBuilder.() -> Unit = { factor(this@toReport) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full linear model report
 * via [linearModel].
 *
 * Zero-code path:
 * ```kotlin
 * val m = LinearModel(setOf("A", "B", "C")).apply { specifyAllTerms() }
 * m.toReport("Full 3-Factor Model").showInBrowser()
 * ```
 *
 * @param title  document title; defaults to `"Linear Model"`
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun LinearModel.toReport(
    title: String = "Linear Model",
    block: ReportBuilder.() -> Unit = { linearModel(this@toReport) }
): ReportNode.Document = report(title, block)

// ── Private helpers ───────────────────────────────────────────────────────────

/**
 * Returns a human-readable order label for a model term described by [components].
 *
 * Rules:
 * - 1 component → `"Main Effect"`
 * - 2 identical components → `"Quadratic"`
 * - 2 distinct components → `"2-Way Interaction"`
 * - 3 identical components → `"Cubic"`
 * - 3+ components → `"n-Way Interaction"`
 */
private fun termOrder(components: List<String>): String = when {
    components.size == 1                              -> "Main Effect"
    components.size == 2 && components[0] == components[1] -> "Quadratic"
    components.size == 2                              -> "2-Way Interaction"
    components.size == 3 && components.toSet().size == 1   -> "Cubic"
    else                                              -> "${components.size}-Way Interaction"
}
