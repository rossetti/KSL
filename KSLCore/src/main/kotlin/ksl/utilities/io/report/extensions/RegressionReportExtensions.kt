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

import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.RegressionResultsIfc
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for rendering [RegressionResultsIfc] results
 * within the KSL reporting framework.
 *
 * **Separation of concerns:**
 * - [regressionSummary] — ANOVA table and model fit measures; no plots
 * - [regressionParameters] — coefficient table with standard errors, t-statistics,
 *   p-values, and confidence intervals; significance legend; no plots
 * - [regressionDiagnostics] — residuals and influence summary table plus three
 *   diagnostic plots (Normal Q-Q, Residuals vs Fitted, Residuals vs Observation Order)
 *
 * **Zero-code entry point:**
 * - [RegressionResultsIfc.toReport] — composes the three functions above into a single
 *   document in the order: summary → parameters → diagnostics
 *
 * **Composing into a larger report:**
 * ```kotlin
 * report("Response Surface Study") {
 *     section("Regression Fit") {
 *         regressionSummary(ols)
 *         regressionParameters(ols)
 *     }
 *     section("Diagnostics") {
 *         regressionDiagnostics(ols)
 *     }
 *     paragraph("Curvature terms are significant; proceed to canonical analysis.")
 * }
 * ```
 */

// ── DSL Function 1: Regression Summary (ANOVA + Fit Measures) ────────────────

/**
 * Appends a self-contained section containing the ANOVA decomposition and global
 * model fit measures for [rr]. No plots are included.
 *
 * **Produces (inside a section titled `caption` or `"Regression Summary"`):**
 * 1. Overview paragraph — response name, predictor names, n, number of parameters,
 *    and whether an intercept term is estimated
 * 2. **Analysis of Variance** sub-section — `DataTable` with columns
 *    Source | SS | DoF | MS | F | P(F > F₀); rows: Regression, Error, Total
 * 3. **Model Fit** sub-section — `DataTable` with R², adjusted R², regression
 *    standard error σ̂, MSE, F-statistic, and F-test p-value
 *
 * @param rr              the [RegressionResultsIfc] to report
 * @param caption         optional section title
 * @param confidenceLevel not used in this function; reserved for API consistency
 */
fun ReportBuilder.regressionSummary(
    rr: RegressionResultsIfc,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "Regression Summary"
    section(myTitle) {

        // ── Overview paragraph ────────────────────────────────────────────────
        paragraph(
            "Response: ${rr.responseName}  |  " +
            "n = ${rr.numObservations}  |  " +
            "Parameters (p): ${rr.numParameters}  |  " +
            "Intercept: ${rr.hasIntercept}  |  " +
            "Predictors: ${rr.predictorNames.joinToString(", ")}"
        )

        // ── ANOVA table ───────────────────────────────────────────────────────
        section("Analysis of Variance") {
            val myAnovaHeaders = listOf("Source", "SS", "DoF", "MS", "F\u2080", "P(F > F\u2080)")
            val myAnovaRows = listOf(
                listOf(
                    "Regression",
                    fmtD(rr.regressionSumOfSquares),
                    rr.regressionDoF.toInt().toString(),
                    fmtD(rr.meanSquaredOfRegression),
                    fmtD(rr.fStatistic),
                    fmtD(rr.fPValue)
                ),
                listOf(
                    "Error",
                    fmtD(rr.residualSumOfSquares),
                    rr.errorDoF.toInt().toString(),
                    fmtD(rr.meanSquaredError),
                    "\u2014",
                    "\u2014"
                ),
                listOf(
                    "Total",
                    fmtD(rr.totalSumOfSquares),
                    rr.totalDoF.toInt().toString(),
                    "\u2014",
                    "\u2014",
                    "\u2014"
                )
            )
            dataTable(myAnovaHeaders, myAnovaRows, caption = "ANOVA Table")
        }

        // ── Model fit measures ────────────────────────────────────────────────
        section("Model Fit") {
            val myFitRows = listOf(
                listOf("R\u00b2 (Coefficient of Determination)", fmtD(rr.rSquared)),
                listOf("Adjusted R\u00b2", fmtD(rr.adjustedRSquared)),
                listOf("\u03c3\u0302 (Regression Standard Error)", fmtD(rr.regressionStandardError)),
                listOf("MSE (Mean Squared Error)", fmtD(rr.meanSquaredError)),
                listOf("F-statistic", fmtD(rr.fStatistic)),
                listOf("p-value (F-test)", fmtD(rr.fPValue))
            )
            dataTable(listOf("Measure", "Value"), myFitRows, caption = "Model Fit Measures")
        }
    }
}

// ── DSL Function 2: Parameter Estimates ──────────────────────────────────────

/**
 * Appends a self-contained section containing the regression coefficient table
 * and a significance summary for [rr]. No plots are included.
 *
 * **Produces (inside a section titled `caption` or `"Parameter Estimates"`):**
 * 1. Coefficient `DataTable` — Predictor | Estimate | Std Error | t₀ | p-value |
 *    CI Lower | CI Upper | Sig.; the CI columns use [confidenceLevel]
 * 2. Significance code legend paragraph (`***` p < 0.001, `**` p < 0.01,
 *    `*` p < 0.05, `.` p < 0.10)
 * 3. Conclusion paragraph listing which predictors are significant at
 *    α = 1 − [confidenceLevel]
 *
 * @param rr              the [RegressionResultsIfc] to report
 * @param caption         optional section title
 * @param confidenceLevel confidence level for the parameter CIs; must be in (0, 1)
 */
fun ReportBuilder.regressionParameters(
    rr: RegressionResultsIfc,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "Parameter Estimates"
    section(myTitle) {

        val myCIs      = rr.parameterConfidenceIntervals(confidenceLevel)
        val myNames    = paramNames(rr)
        val myParams   = rr.parameters
        val mySE       = rr.parametersStdError
        val myT        = rr.parameterTStatistics
        val myP        = rr.parameterPValues
        val myAlpha    = 1.0 - confidenceLevel
        val myPct      = (confidenceLevel * 100.0).toInt()

        val myParamRows = myParams.indices.map { i ->
            listOf(
                myNames[i],
                fmtD(myParams[i]),
                fmtD(mySE[i]),
                fmtD(myT[i]),
                fmtD(myP[i]),
                fmtD(myCIs[i].lowerLimit),
                fmtD(myCIs[i].upperLimit),
                sigCode(myP[i])
            )
        }
        dataTable(
            headers = listOf("Predictor", "Estimate", "Std Error", "t\u2080",
                             "p-value", "CI Lower", "CI Upper", "Sig."),
            rows    = myParamRows,
            caption = "Parameter Estimates ($myPct% CI)"
        )

        // Significance legend
        paragraph(
            "Sig. codes: \u2018***\u2019 p < 0.001  |  \u2018**\u2019 p < 0.01  |  " +
            "\u2018*\u2019 p < 0.05  |  \u2018.\u2019 p < 0.10"
        )

        // Conclusion at the requested alpha level
        val mySigNames = myParams.indices
            .filter { myP[it] < myAlpha }
            .map    { myNames[it] }
        if (mySigNames.isEmpty()) {
            paragraph(
                "No predictors are statistically significant at " +
                "\u03b1 = ${fmtD(myAlpha)} (${myPct}% confidence level)."
            )
        } else {
            paragraph(
                "Predictors significant at \u03b1 = ${fmtD(myAlpha)}: " +
                "${mySigNames.joinToString(", ")}."
            )
        }
    }
}

// ── DSL Function 3: Residuals and Diagnostics ────────────────────────────────

/**
 * Appends a self-contained section containing residual diagnostics and three
 * standard regression diagnostic plots for [rr].
 *
 * **Produces (inside a section titled `caption` or `"Regression Diagnostics"`):**
 * 1. **Residuals and Influence Summary** `DataTable` — n, min/max/mean/std dev of
 *    residuals, mean and max leverage (hᵢᵢ), high-leverage count (hᵢᵢ > 2p/n),
 *    max Cook's distance, and influential-point count (Cook's D > 4/n)
 * 2. Threshold interpretation paragraph
 * 3. **Diagnostic Plots** sub-section containing:
 *    - Normal Q-Q plot of standardized residuals
 *    - Residuals vs fitted values (scatter)
 *    - Residuals vs observation order
 *
 * High-leverage threshold: hᵢᵢ > 2p/n (where p = [RegressionResultsIfc.numParameters]).
 * Influential-point threshold: Cook's D > 4/n.
 *
 * @param rr      the [RegressionResultsIfc] to report
 * @param caption optional section title
 */
fun ReportBuilder.regressionDiagnostics(
    rr: RegressionResultsIfc,
    caption: String? = null
) {
    val myTitle = caption ?: "Regression Diagnostics"
    section(myTitle) {

        // ── Residuals summary ─────────────────────────────────────────────────
        val myResidStat    = Statistic("Residuals", rr.residuals)
        val myH            = rr.hatDiagonal
        val myCD           = rr.cookDistanceMeasures
        val myN            = rr.numObservations
        val myP            = rr.numParameters
        val myLevThreshold = 2.0 * myP.toDouble() / myN.toDouble()
        val myCDThreshold  = 4.0 / myN.toDouble()
        val myHighLev      = myH.count { it > myLevThreshold }
        val myHighCD       = myCD.count { it > myCDThreshold }

        val mySummaryRows = listOf(
            listOf("Observations (n)",                                myN.toString()),
            listOf("Parameters (p)",                                  myP.toString()),
            listOf("Min Residual",                                    fmtD(myResidStat.min)),
            listOf("Max Residual",                                    fmtD(myResidStat.max)),
            listOf("Mean Residual",                                   fmtD(myResidStat.average)),
            listOf("Std Dev Residual",                                fmtD(myResidStat.standardDeviation)),
            listOf("\u03c3\u0302 (Regression SE)",                    fmtD(rr.regressionStandardError)),
            listOf("Mean Leverage (h\u0304\u1d62\u1d62)",             fmtD(myH.average())),
            listOf("Max Leverage",                                    fmtD(myH.maxOrNull() ?: Double.NaN)),
            listOf("High-Leverage Points (h\u1d62\u1d62 > ${fmtD(myLevThreshold)})",
                                                                      myHighLev.toString()),
            listOf("Max Cook\u2019s Distance",                        fmtD(myCD.maxOrNull() ?: Double.NaN)),
            listOf("Influential Points (Cook\u2019s D > ${fmtD(myCDThreshold)})",
                                                                      myHighCD.toString())
        )
        dataTable(
            headers = listOf("Diagnostic", "Value"),
            rows    = mySummaryRows,
            caption = "Residuals and Influence Summary"
        )

        paragraph(
            "High-leverage threshold: h\u1d62\u1d62 > 2p/n = ${fmtD(myLevThreshold)}. " +
            "Influential-point threshold: Cook\u2019s D > 4/n = ${fmtD(myCDThreshold)}. " +
            "Observations exceeding these thresholds warrant individual inspection."
        )

        // ── Diagnostic plots ──────────────────────────────────────────────────
        section("Diagnostic Plots") {
            section("Normal Q-Q Plot") {
                // FitDistPlot is a GGBunch composite and does not implement PlotIfc;
                // use its qqPlot component (a BasePlot / PlotIfc) directly.
                plot(
                    rr.standardizedResidualsNormalPlot().qqPlot,
                    caption = "Normal Q-Q Plot \u2014 Standardized Residuals"
                )
            }
            section("Residuals vs Fitted") {
                plot(
                    rr.residualsVsPredictedPlot(),
                    caption = "Residuals vs Fitted Values"
                )
            }
            section("Residuals vs Observation Order") {
                plot(
                    rr.residualsVsObservationOrderPlot(),
                    caption = "Residuals vs Observation Order"
                )
            }
        }
    }
}

// ── toReport() — zero-code entry point ───────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a full OLS regression report:
 * ANOVA + fit measures, parameter estimates with CIs, and diagnostic plots.
 *
 * **Zero-code path:**
 * ```kotlin
 * val ols = OLSRegression(regressionData)
 * ols.toReport().showInBrowser()
 * ols.toReport().writeMarkdown()
 * ```
 *
 * **Custom block (use the captured local variable, not `this@toReport`):**
 * ```kotlin
 * ols.toReport("Delivery Time Study") {
 *     regressionSummary(ols)
 *     regressionParameters(ols)
 *     paragraph("Both predictors significant; model explains 96% of variance.")
 * }
 * ```
 *
 * @param title           document title
 * @param confidenceLevel confidence level for parameter CIs and significance conclusions
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun RegressionResultsIfc.toReport(
    title: String = "Regression Analysis — ${responseName}",
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        regressionSummary(this@toReport, confidenceLevel = confidenceLevel)
        regressionParameters(this@toReport, confidenceLevel = confidenceLevel)
        regressionDiagnostics(this@toReport)
    }
): ReportNode.Document = report(title, block)

// ── Private helpers ───────────────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtD(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "\u2014"
    else -> "%.4f".format(value)
}

/**
 * Returns the R-style significance code for [pValue].
 * `***` p < 0.001 | `**` p < 0.01 | `*` p < 0.05 | `.` p < 0.10 | (blank) otherwise.
 */
private fun sigCode(pValue: Double): String = when {
    pValue.isNaN()  -> ""
    pValue < 0.001  -> "***"
    pValue < 0.01   -> "**"
    pValue < 0.05   -> "*"
    pValue < 0.10   -> "."
    else            -> ""
}

/**
 * Builds the full ordered list of parameter names for [rr], matching the order
 * of [RegressionResultsIfc.parameters]: `"Intercept"` first (if [RegressionResultsIfc.hasIntercept]),
 * then each entry in [RegressionResultsIfc.predictorNames].
 */
private fun paramNames(rr: RegressionResultsIfc): List<String> {
    val myNames = mutableListOf<String>()
    if (rr.hasIntercept) myNames.add("Intercept")
    myNames.addAll(rr.predictorNames)
    return myNames
}
