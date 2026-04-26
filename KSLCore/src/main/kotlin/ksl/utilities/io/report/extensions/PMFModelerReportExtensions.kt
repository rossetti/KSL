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

import ksl.utilities.distributions.ChiSquaredDistribution
import ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.PMFComparisonPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.toDoubles

/**
 * DSL extension functions on [ReportBuilder] for rendering [PMFModeler] and
 * [DiscretePMFGoodnessOfFit] results within the KSL reporting framework.
 *
 * **Separation of concerns:**
 * - [discreteDataSummary] — tabular data characteristics (statistics, integer frequency
 *   distribution, dispersion analysis); no plots
 * - [discreteVisualization] — exploratory plots only (frequency bar chart, observations,
 *   ACF); no tables and no fitted distribution assumed
 * - [discreteGoodnessOfFit] — chi-squared bin table, test summary, dispersion tests, and
 *   empirical-vs-theoretical PMF comparison plot for a single [DiscretePMFGoodnessOfFit]
 *
 * **Zero-code entry points:**
 * - [PMFModeler.toReport] — EDA report (data summary + visualization)
 * - [DiscretePMFGoodnessOfFit.toReport] — full report (summary + visualization + GOF)
 *
 * **Why [PMFModeler] is passed to [discreteGoodnessOfFit]:**
 * [DiscretePMFGoodnessOfFit] does not hold a back-reference to the modeler that produced
 * it. [PMFComparisonPlot] requires raw `IntArray` data for the empirical side of the
 * comparison, which is obtained via [PMFModeler.data].
 */

// ── DSL Function 1: Discrete Data Summary ────────────────────────────────────

/**
 * Appends a self-contained section summarising the characteristics of the integer
 * data held by [modeler], with no plots.
 *
 * **Produces (inside a section titled `caption` or `"Discrete Data Summary"`):**
 * 1. Overview paragraph — n, mean, variance, min, max, zero count, negative count
 * 2. **Integer Frequency** sub-section — full frequency table, statistics property
 *    sheet, and frequency bar chart (delegated to [integerFrequency])
 * 3. **Dispersion Analysis** sub-section — index of dispersion; Poisson variance
 *    test statistic T = (n−1)·S²/X̄; upper-tail, lower-tail, and two-sided p-values
 *    from χ²(n−1) (Fisher 1950); equidispersion/overdispersion/underdispersion
 *    interpretation; and a small-sample warning when n < 30. All values computed
 *    directly from [PMFModeler.statistics].
 *
 * @param modeler         the [PMFModeler] whose data will be summarised
 * @param caption         optional section title
 * @param confidenceLevel confidence level for the statistics property sheet; must be in (0, 1)
 */
fun ReportBuilder.discreteDataSummary(
    modeler: PMFModeler,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "Discrete Data Summary"
    section(myTitle) {

        // ── Overview paragraph ────────────────────────────────────────────────
        val myStat = modeler.statistics
        paragraph(
            "n = ${myStat.count.toInt()}  |  " +
            "Mean = ${fmtDouble(myStat.average)}  |  " +
            "Variance = ${fmtDouble(myStat.variance)}  |  " +
            "Min = ${fmtDouble(myStat.min)}  |  " +
            "Max = ${fmtDouble(myStat.max)}  |  " +
            "Zeros = ${modeler.hasZeroes}  |  " +
            "Negatives = ${modeler.hasNegatives}"
        )

        // ── Integer frequency distribution (full section via existing extension) ──
        integerFrequency(
            freq              = modeler.frequency,
            caption           = "Frequency Distribution",
            confidenceLevel   = confidenceLevel,
            showStatistics    = true
        )

        // ── Dispersion analysis ───────────────────────────────────────────────
        section("Dispersion Analysis") {
            val myMean = myStat.average
            val myVar  = myStat.variance
            val myN    = myStat.count
            val myIod  = if (myMean == 0.0) Double.NaN else myVar / myMean
            val myPvt  = if (myMean == 0.0) Double.NaN else (myN - 1.0) * myVar / myMean
            val myInterp = when {
                myIod.isNaN()  -> "Undefined (zero mean)"
                myIod < 0.9999 -> "Underdispersed (IoD < 1.0)"
                myIod > 1.0001 -> "Overdispersed (IoD > 1.0)"
                else           -> "Equidispersed (IoD \u2248 1.0)"
            }

            // Poisson variance test p-values via χ²(n−1)
            val (myUpperP, myLowerP, myTwoSidedP) = poissonDispersionPValues(myPvt, myN)
            val myDofLabel = "n\u22121 = ${(myN - 1.0).toInt()}"

            val myDispRows = listOf(
                listOf("Index of Dispersion (Var / Mean)",   fmtDouble(myIod),      myInterp),
                listOf("Poisson Variance Test Statistic (T)", fmtDouble(myPvt),     "DOF = $myDofLabel"),
                listOf("p-value (upper \u2014 overdispersion)",  fmtDouble(myUpperP),
                    "P(\u03c7\u00b2($myDofLabel) \u2265 T)"),
                listOf("p-value (lower \u2014 underdispersion)", fmtDouble(myLowerP),
                    "P(\u03c7\u00b2($myDofLabel) \u2264 T)"),
                listOf("p-value (two-sided)",                fmtDouble(myTwoSidedP), "2\u00b7min(upper, lower)")
            )
            dataTable(
                listOf("Property", "Value", "Note"), myDispRows,
                caption = "Dispersion Indicators"
            )
            if (myN < 30.0) {
                paragraph(
                    "\u26a0 Small-sample warning: the \u03c7\u00b2(n\u22121) approximation " +
                    "for the Poisson variance test requires n \u2265 30. " +
                    "The p-values above may not be reliable (n = ${myN.toInt()})."
                )
            }
            paragraph(
                "The Index of Dispersion (IoD = Var/Mean) equals 1.0 for a Poisson process. " +
                "IoD > 1.0 indicates overdispersion (variance exceeds the mean); " +
                "IoD < 1.0 indicates underdispersion. " +
                "The Poisson variance test statistic T = (n\u22121)\u00b7S\u00b2/X\u0305 " +
                "follows approximately \u03c7\u00b2(n\u22121) under H\u2080: Poisson " +
                "(Fisher 1950; Law 2015 \u00a76.5)."
            )
        }
    }
}

// ── DSL Function 2: Discrete Data Visualization ──────────────────────────────

/**
 * Appends a self-contained section containing three exploratory plots for the
 * integer data held by [modeler]. No fitted distribution is assumed.
 *
 * **Produces (inside a section titled `caption` or `"Discrete Data Visualization"`):**
 * 1. **Frequency Distribution** sub-section — frequency bar chart
 * 2. **Observations** sub-section — integer values in observation order
 * 3. **Autocorrelation** sub-section — ACF plot
 *
 * Note: [PMFComparisonPlot] (empirical vs theoretical PMF) is intentionally
 * omitted here because it requires a fitted distribution; it belongs in
 * [discreteGoodnessOfFit].
 *
 * @param modeler the [PMFModeler] whose data will be plotted
 * @param caption optional section title
 */
fun ReportBuilder.discreteVisualization(
    modeler: PMFModeler,
    caption: String? = null
) {
    val myTitle = caption ?: "Discrete Data Visualization"
    section(myTitle) {

        section("Frequency Distribution") {
            plot(modeler.frequency.frequencyPlot(),
                caption = "Observed Frequency Distribution")
        }

        section("Observations") {
            plot(ObservationsPlot(modeler.data),
                caption = "Observations in Sequence")
        }

        section("Autocorrelation") {
            plot(ACFPlot(modeler.data.toDoubles()),
                caption = "Autocorrelation Function (ACF)")
        }
    }
}

// ── DSL Function 3: Discrete Goodness of Fit (single distribution) ───────────

/**
 * Appends a self-contained section reporting the goodness-of-fit results for a
 * single [DiscretePMFGoodnessOfFit].
 *
 * **Produces (inside a section titled `caption` or `gof.distribution.toString()`):**
 * 1. Overview paragraph — distribution, estimated parameters, DOF, index of dispersion
 * 2. **Chi-Squared Bin Table** — Bin Label | P(Bin) | Observed | Expected | Note;
 *    `Note` column flags bins where expected count is less than 5
 * 3. **Goodness of Fit Test** — test summary `DataTable` (DOF, statistic, p-value,
 *    conclusion at the given [confidenceLevel])
 * 4. **Dispersion Tests** — index of dispersion and Poisson variance test statistic
 *    from [DiscreteDistributionGOFIfc]
 * 5. **Distribution Comparison** sub-section — [PMFComparisonPlot] overlaying the
 *    empirical PMF (from [modeler]) against the theoretical PMF (from [gof.distribution])
 *
 * **Why [modeler] is a required parameter:**
 * [DiscretePMFGoodnessOfFit] does not back-reference the [PMFModeler] that produced it,
 * and [PMFComparisonPlot] requires the raw integer data from [PMFModeler.data].
 *
 * @param gof             the [DiscretePMFGoodnessOfFit] to report
 * @param modeler         the [PMFModeler] used to generate the data
 * @param caption         optional section title; defaults to `gof.distribution.toString()`
 * @param confidenceLevel significance level for the GOF test conclusion; must be in (0, 1)
 */
fun ReportBuilder.discreteGoodnessOfFit(
    gof: DiscretePMFGoodnessOfFit,
    modeler: PMFModeler,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: gof.distribution.toString()
    section(myTitle) {

        // ── Overview paragraph ────────────────────────────────────────────────
        val myIod = if (gof.histogram.average == 0.0) Double.NaN
                    else gof.indexOfDispersion
        val myIodInterp = when {
            myIod.isNaN()  -> "Undefined"
            myIod < 0.9999 -> "Underdispersed"
            myIod > 1.0001 -> "Overdispersed"
            else           -> "Equidispersed"
        }
        paragraph(
            "Distribution: ${gof.distribution}  |  " +
            "Estimated Parameters: ${gof.numEstimatedParameters}  |  " +
            "Intervals: ${gof.histogram.numberBins}  |  " +
            "DOF: ${gof.chiSquaredTestDOF}  |  " +
            "IoD: ${fmtDouble(myIod)} ($myIodInterp)"
        )

        // ── Chi-squared bin table ─────────────────────────────────────────────
        val myBinHeaders = listOf("Bin Label", "P(Bin)", "Observed", "Expected", "Note")
        val myBinRows = gof.histogram.bins.mapIndexed { i, bin ->
            val myExpected = gof.expectedCounts[i]
            listOf(
                bin.binLabel,
                fmtDouble(gof.binProbabilities[i]),
                bin.count.toInt().toString(),
                fmtDouble(myExpected),
                if (myExpected <= 4.99999) "Expected < 5" else ""
            )
        }
        dataTable(myBinHeaders, myBinRows, caption = "Chi-Squared Bin Table")

        // ── Goodness of fit test summary ──────────────────────────────────────
        val myAlpha      = 1.0 - confidenceLevel
        val myConclusion = if (gof.chiSquaredPValue >= myAlpha)
            "p-value ${fmtDouble(gof.chiSquaredPValue)} \u2265 \u03b1 ${fmtDouble(myAlpha)}: Do not reject"
        else
            "p-value ${fmtDouble(gof.chiSquaredPValue)} < \u03b1 ${fmtDouble(myAlpha)}: Reject"
        val myTestRows = listOf(
            listOf("Estimated Parameters",  gof.numEstimatedParameters.toString()),
            listOf("Number of Intervals",   gof.histogram.numberBins.toString()),
            listOf("Degrees of Freedom",    gof.chiSquaredTestDOF.toString()),
            listOf("Chi-Squared Statistic", fmtDouble(gof.chiSquaredTestStatistic)),
            listOf("p-value",               fmtDouble(gof.chiSquaredPValue)),
            listOf("Conclusion",            myConclusion)
        )
        dataTable(listOf("Property", "Value"), myTestRows,
            caption = "Goodness of Fit Test (Chi-Squared)")

        // ── Dispersion tests ──────────────────────────────────────────────────
        section("Dispersion Tests") {
            val myPvt  = gof.poissonVarianceTestStatistic
            val myN    = gof.histogram.count
            val (myUpperP, myLowerP, myTwoSidedP) = poissonDispersionPValues(myPvt, myN)
            val myDofLabel = "n\u22121 = ${(myN - 1.0).toInt()}"
            val myDispRows = listOf(
                listOf("Index of Dispersion (Var / Mean)",    fmtDouble(myIod),      myIodInterp),
                listOf("Poisson Variance Test Statistic (T)", fmtDouble(myPvt),      "DOF = $myDofLabel"),
                listOf("p-value (upper \u2014 overdispersion)",  fmtDouble(myUpperP),
                    "P(\u03c7\u00b2($myDofLabel) \u2265 T)"),
                listOf("p-value (lower \u2014 underdispersion)", fmtDouble(myLowerP),
                    "P(\u03c7\u00b2($myDofLabel) \u2264 T)"),
                listOf("p-value (two-sided)",                fmtDouble(myTwoSidedP),  "2\u00b7min(upper, lower)")
            )
            dataTable(
                listOf("Metric", "Value", "Note"), myDispRows,
                caption = "Dispersion Test Statistics"
            )
            if (myN < 30.0) {
                paragraph(
                    "\u26a0 Small-sample warning: the \u03c7\u00b2(n\u22121) approximation " +
                    "for the Poisson variance test requires n \u2265 30. " +
                    "The p-values above may not be reliable (n = ${myN.toInt()})."
                )
            }
            paragraph(
                "The Index of Dispersion (IoD = Var/Mean) equals 1.0 for a Poisson process. " +
                "The Poisson variance test statistic T = (n\u22121)\u00b7S\u00b2/X\u0305 " +
                "follows approximately \u03c7\u00b2(n\u22121) under H\u2080: Poisson " +
                "(Fisher 1950; Law 2015 \u00a76.5)."
            )
        }

        // ── PMF comparison plot ───────────────────────────────────────────────
        section("Distribution Comparison") {
            plot(
                PMFComparisonPlot(modeler.data, gof.distribution),
                caption = "Empirical vs Theoretical PMF — ${gof.distribution}"
            )
        }
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a discrete data exploration report —
 * statistical summary and visualization plots — with no goodness-of-fit analysis.
 * Suitable for EDA before committing to a specific distribution.
 *
 * Zero-code path:
 * ```kotlin
 * val modeler = PMFModeler(data)
 * modeler.toReport().showInBrowser()
 * modeler.toReport().writeMarkdown()
 * ```
 *
 * Custom block (use the captured local variable, not `this@toReport`):
 * ```kotlin
 * modeler.toReport("Arrival Count — EDA") {
 *     discreteDataSummary(modeler)
 *     discreteVisualization(modeler)
 *     paragraph("Data appears Poisson; index of dispersion close to 1.0.")
 * }
 * ```
 *
 * @param title           document title
 * @param confidenceLevel confidence level for the statistics property sheet
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun PMFModeler.toReport(
    title: String = "Discrete Distribution Fitting — Data Analysis",
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        discreteDataSummary(this@toReport, confidenceLevel = confidenceLevel)
        discreteVisualization(this@toReport)
    }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full discrete distribution fitting
 * report: data statistical summary, visualization plots, and chi-squared
 * goodness-of-fit analysis with a PMF comparison plot.
 *
 * The [modeler] parameter is required because [DiscretePMFGoodnessOfFit] does not
 * hold a back-reference to the [PMFModeler] that produced it, and the data-exploration
 * sections plus [PMFComparisonPlot] need the original integer data.
 *
 * Zero-code path:
 * ```kotlin
 * val modeler = PMFModeler(data)
 * val results = modeler.estimateParameters(setOf(PoissonMLEParameterEstimator))
 * val mean    = results.first().parameters!!.doubleParameter("mean")
 * val gof     = PoissonGoodnessOfFit(data.toDoubles(), mean = mean)
 * gof.toReport(modeler).showInBrowser()
 * ```
 *
 * Custom block (use the captured local variables, not `this@toReport`):
 * ```kotlin
 * gof.toReport(modeler, "Poisson Fit — Count Data") {
 *     discreteDataSummary(modeler)
 *     discreteGoodnessOfFit(gof, modeler)
 *     paragraph("Poisson fit is acceptable at the 0.05 level.")
 * }
 * ```
 *
 * @param modeler         the [PMFModeler] that provided the data
 * @param title           document title
 * @param confidenceLevel significance level for the GOF test conclusion; must be in (0, 1)
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun DiscretePMFGoodnessOfFit.toReport(
    modeler: PMFModeler,
    title: String = "Discrete Distribution Fitting Analysis",
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        discreteDataSummary(modeler, confidenceLevel = confidenceLevel)
        discreteVisualization(modeler)
        discreteGoodnessOfFit(this@toReport, modeler, confidenceLevel = confidenceLevel)
    }
): ReportNode.Document = report(title, block)

/**
 * Computes upper-tail, lower-tail, and two-sided p-values for the Poisson variance
 * test statistic [pvt] = (n−1)·S²/X̄ using a χ²(n−1) reference distribution.
 *
 * - **Upper-tail p-value** = P(χ²(n−1) ≥ T): evidence of overdispersion (variance > mean)
 * - **Lower-tail p-value** = P(χ²(n−1) ≤ T): evidence of underdispersion (variance < mean)
 * - **Two-sided p-value** = 2·min(upper, lower): evidence against equidispersion in either direction
 *
 * Returns `Triple(NaN, NaN, NaN)` when [pvt] is not finite or [n] < 2.
 *
 * Reference: Fisher (1950); Law (2015) §6.5. Requires n ≥ 30 for reliable approximation.
 *
 * @param pvt the Poisson variance test statistic T = (n−1)·S²/X̄
 * @param n   the sample size
 * @return Triple(upperP, lowerP, twoSidedP)
 */
private fun poissonDispersionPValues(pvt: Double, n: Double): Triple<Double, Double, Double> {
    if (pvt.isNaN() || pvt.isInfinite() || n < 2.0) {
        return Triple(Double.NaN, Double.NaN, Double.NaN)
    }
    val myDof     = maxOf(1.0, n - 1.0)
    val myChiDist = ChiSquaredDistribution(myDof)
    val myUpperP  = myChiDist.complementaryCDF(pvt)
    val myLowerP  = myChiDist.cdf(pvt)
    return Triple(myUpperP, myLowerP, 2.0 * minOf(myUpperP, myLowerP))
}
