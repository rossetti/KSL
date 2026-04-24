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
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.extensions.welchAnova
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistic.WelchANOVA

// ── Shared data-generation helpers ────────────────────────────────────────────

/**
 * Simulates response data for a four-arm drug-efficacy study.
 *
 * **Groups:** Placebo, Low Dose, Mid Dose, High Dose.
 *
 * **True means:** 10, 15, 22, 30. The means are spaced far apart relative to
 * within-group variance so Welch's ANOVA reliably rejects H₀. Within-group
 * variances are intentionally unequal (4, 9, 6, 16) to justify Welch over the
 * classical equal-variance one-way ANOVA.
 */
private fun drugStudyAnova(n: Int = 25): WelchANOVA {
    val placebo  = NormalRV(mean = 10.0, variance =  4.0, streamNum = 1).sample(n)
    val lowDose  = NormalRV(mean = 15.0, variance =  9.0, streamNum = 2).sample(n)
    val midDose  = NormalRV(mean = 22.0, variance =  6.0, streamNum = 3).sample(n)
    val highDose = NormalRV(mean = 30.0, variance = 16.0, streamNum = 4).sample(n)
    return WelchANOVA(
        mapOf(
            "Placebo"   to placebo,
            "Low Dose"  to lowDose,
            "Mid Dose"  to midDose,
            "High Dose" to highDose
        )
    )
}

/**
 * Simulates part-dimension measurements from three machines whose true means
 * are nearly identical (100.0, 101.0, 99.5).
 *
 * With means this close, Welch's ANOVA should fail to reject H₀ at α = 0.05,
 * illustrating the non-rejection path in the report.
 */
private fun machineOutputAnova(n: Int = 25): WelchANOVA {
    val machine1 = NormalRV(mean = 100.0, variance = 9.00, streamNum = 5).sample(n)
    val machine2 = NormalRV(mean = 101.0, variance = 4.00, streamNum = 6).sample(n)
    val machine3 = NormalRV(mean =  99.5, variance = 6.25, streamNum = 7).sample(n)
    return WelchANOVA(
        mapOf(
            "Machine 1" to machine1,
            "Machine 2" to machine2,
            "Machine 3" to machine3
        )
    )
}

// ── Demo 1: Zero-code entry point ─────────────────────────────────────────────

/**
 * Demonstrates [WelchANOVA.toReport]: the simplest reporting path using all defaults.
 *
 * Default flags: confidence level 0.95, section title "Welch's ANOVA".
 *
 * The document contains:
 * - Hypothesis paragraph (k groups, α, H₀ statement)
 * - Test Statistics DataTable (k, weighted mean, F, df1, df2, p-value, α, Reject H₀)
 * - Group Summary DataTable (Group | N | Mean | Std Dev | Std Err | HW (95%) | CI Lower | CI Upper)
 *
 * All four output formats are written to `kslOutput/`.
 */
fun demoWelchAnovaZeroCode() {
    val myAnova = drugStudyAnova()
    val myDoc   = myAnova.toReport(title = "Drug Efficacy Study — Default Report")
    myDoc.showInBrowser()
    myDoc.writeHtml()
    myDoc.writeMarkdown()
    myDoc.writeText()
    println("Zero-code Welch ANOVA report written to kslOutput/")
}

// ── Demo 2: Non-rejection case ────────────────────────────────────────────────

/**
 * Demonstrates a report where Welch's ANOVA does **not** reject H₀.
 *
 * The three machine groups have almost identical true means, so the p-value exceeds
 * α = 0.05 and "Reject H₀" reads `false`. The Group Summary CI columns show wide,
 * overlapping intervals confirming that no group stands apart.
 */
fun demoWelchAnovaNonRejection() {
    val myAnova = machineOutputAnova()
    val myDoc   = myAnova.toReport(title = "Machine Output — No Significant Difference")
    myDoc.showInBrowser()
    myDoc.writeHtml()
    println("Non-rejection Welch ANOVA report written to kslOutput/")
}

// ── Demo 3: Strict significance threshold ─────────────────────────────────────

/**
 * Demonstrates the effect of tightening the significance threshold to α = 0.01.
 *
 * The drug-study data is the same as Demo 1, but [WelchANOVA.type1ErrorCriteria]
 * is overridden to 0.01 before reporting. Because the group means are far apart,
 * the test still rejects H₀ at the stricter threshold. The report header reflects
 * the α value in use, and the "Reject H₀" row updates accordingly.
 */
fun demoWelchAnovaStrictAlpha() {
    val myAnova = drugStudyAnova()
    myAnova.type1ErrorCriteria = 0.01
    val myDoc = myAnova.toReport(title = "Drug Efficacy Study — α = 0.01")
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("Strict-alpha Welch ANOVA report written to kslOutput/")
}

// ── Demo 4: Wide confidence intervals (99 %) ──────────────────────────────────

/**
 * Demonstrates the [confidenceLevel] parameter on [WelchANOVA.toReport].
 *
 * Setting `confidenceLevel = 0.99` widens each group's CI columns (HW, CI Lower,
 * CI Upper) in the Group Summary DataTable relative to the default 95 % intervals.
 * Use this when a conservative interval is required for regulatory or publication
 * purposes, even though the F-test hypothesis is still evaluated at α = 0.05.
 */
fun demoWelchAnovaWideCi() {
    val myAnova = drugStudyAnova()
    val myDoc   = myAnova.toReport(
        title           = "Drug Efficacy Study — 99 % Confidence Intervals",
        confidenceLevel = 0.99
    )
    myDoc.showInBrowser()
    myDoc.writeMarkdown()
    println("Wide-CI Welch ANOVA report written to kslOutput/")
}

// ── Demo 5: DSL composition — annotated side-by-side comparison ───────────────

/**
 * Demonstrates embedding multiple [welchAnova] calls inside a single [report] block
 * to compare two scenarios in one document, with analyst commentary.
 *
 * **Structure:**
 * - Executive-summary introduction paragraph
 * - Section "Drug Efficacy Study" — Welch ANOVA with 95 % CIs (H₀ rejected)
 * - Section "Machine Output Uniformity" — Welch ANOVA with 99 % CIs (H₀ not rejected)
 * - Section "Summary" — narrative comparing both outcomes
 *
 * Also demonstrates [WelchANOVA.toReport] with a custom block, showing how to
 * append context-specific notes after the standard section.
 *
 * All four output formats are written to `kslOutput/`.
 */
fun demoWelchAnovaCustomBlock() {
    val myDrugAnova    = drugStudyAnova(n = 30)
    val myMachineAnova = machineOutputAnova(n = 30)

    // ── Composite report: two ANOVA results in one document ───────────────────
    val myComposite = report("Welch ANOVA — Side-by-Side Analysis") {
        paragraph(
            "This report presents two Welch ANOVA analyses demonstrating contrasting " +
            "outcomes: one where group means differ significantly, and one where they " +
            "do not. Welch's procedure is used in both cases because within-group " +
            "variances are heterogeneous."
        )

        welchAnova(
            anova   = myDrugAnova,
            caption = "Drug Efficacy Study (four treatment arms)",
            confidenceLevel = 0.95
        )

        welchAnova(
            anova   = myMachineAnova,
            caption = "Machine Output Uniformity (three machines)",
            confidenceLevel = 0.99
        )

        section("Summary") {
            val drugResult    = if (myDrugAnova.rejectH0)    "rejected" else "not rejected"
            val machineResult = if (myMachineAnova.rejectH0) "rejected" else "not rejected"
            paragraph(
                "Drug study: H₀ (equal means across dose groups) was $drugResult " +
                "at α = ${myDrugAnova.type1ErrorCriteria} " +
                "(F = ${"%.4f".format(myDrugAnova.fValue)}, " +
                "p = ${"%.4f".format(myDrugAnova.pValue)}). " +
                "The group summary CI columns confirm the dose–response gradient: " +
                "each dose group's 95 % interval is well separated from adjacent groups."
            )
            paragraph(
                "Machine output: H₀ (equal means across machines) was $machineResult " +
                "at α = ${myMachineAnova.type1ErrorCriteria} " +
                "(F = ${"%.4f".format(myMachineAnova.fValue)}, " +
                "p = ${"%.4f".format(myMachineAnova.pValue)}). " +
                "The 99 % CI columns for all three machines overlap substantially, " +
                "consistent with the failure to detect a difference."
            )
        }
    }
    myComposite.showInBrowser()
    myComposite.writeHtml()
    myComposite.writeMarkdown()
    myComposite.writeText()

    // ── toReport() with a custom block ────────────────────────────────────────
    val myAnnotated = myDrugAnova.toReport(title = "Drug Efficacy — Custom Block Demo") {
        welchAnova(
            anova           = myDrugAnova,
            confidenceLevel = 0.99
        )
        section("Clinical Interpretation") {
            paragraph(
                "The high-dose group mean is approximately three times the placebo mean. " +
                "Non-overlapping 99 % confidence intervals for Placebo vs High Dose " +
                "confirm a clinically meaningful difference beyond the ANOVA p-value alone."
            )
        }
    }
    myAnnotated.showInBrowser()
    myAnnotated.writeMarkdown()
    println("Composite and annotated Welch ANOVA reports written to kslOutput/")
}

// ── Entry point ───────────────────────────────────────────────────────────────

fun main() {
//    demoWelchAnovaZeroCode()
//    demoWelchAnovaNonRejection()
//    demoWelchAnovaStrictAlpha()
//    demoWelchAnovaWideCi()
    demoWelchAnovaCustomBlock()
}
