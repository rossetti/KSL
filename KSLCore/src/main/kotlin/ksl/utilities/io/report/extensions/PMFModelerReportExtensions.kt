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

import ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
import ksl.utilities.distributions.fitting.PMFData
import ksl.utilities.distributions.fitting.PMFFitData
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
 * data held by [pmf], with no plots.
 *
 * **Produces (inside a section titled `caption` or `"Discrete Data Summary"`):**
 * 1. Overview paragraph — n, mean, variance, min, max, zero count, negative count
 * 2. **Integer Frequency** sub-section — full frequency table, statistics property
 *    sheet, and frequency bar chart (delegated to [integerFrequency])
 * 3. **Dispersion Analysis** sub-section — index of dispersion; Poisson variance
 *    test statistic T = (n−1)·S²/X̄; upper-tail, lower-tail, and two-sided p-values
 *    from χ²(n−1) (Fisher 1950); equidispersion/overdispersion/underdispersion
 *    interpretation; and a small-sample warning when n < 30. All values computed
 *    directly from the sample statistics.
 *
 * @param pmf             the [PMFData] whose sample will be summarised
 * @param caption         optional section title
 * @param confidenceLevel confidence level for the statistics property sheet; must be in (0, 1)
 */
fun ReportBuilder.discreteDataSummary(
    pmf: PMFData,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "Discrete Data Summary"
    section(myTitle) {

        // ── Overview paragraph ────────────────────────────────────────────────
        val myStat = pmf.statistics
        paragraph(
            "n = ${myStat.count.toInt()}  |  " +
            "Mean = ${fmtDouble(myStat.average)}  |  " +
            "Variance = ${fmtDouble(myStat.variance)}  |  " +
            "Min = ${fmtDouble(myStat.min)}  |  " +
            "Max = ${fmtDouble(myStat.max)}  |  " +
            "Zeros = ${pmf.hasZeroes}  |  " +
            "Negatives = ${pmf.hasNegatives}"
        )

        // ── Integer frequency distribution (full section via existing extension) ──
        integerFrequency(
            freq              = pmf.frequency,
            caption           = "Frequency Distribution",
            confidenceLevel   = confidenceLevel,
            showStatistics    = true
        )

        // ── Dispersion analysis ───────────────────────────────────────────────
        section("Dispersion Analysis") {
            val myN     = myStat.count
            val myDisp  = DiscretePMFGoodnessOfFit.poissonDispersionTest(
                myStat.average, myStat.variance, myN
            )
            val myIod        = myDisp.indexOfDispersion
            val myPvt        = myDisp.testStatistic
            val myUpperP     = myDisp.upperPValue
            val myLowerP     = myDisp.lowerPValue
            val myTwoSidedP  = myDisp.twoSidedPValue
            val myInterp = when {
                myIod.isNaN()  -> "Undefined (zero mean)"
                myIod < 0.9999 -> "Underdispersed (IoD < 1.0)"
                myIod > 1.0001 -> "Overdispersed (IoD > 1.0)"
                else           -> "Equidispersed (IoD \u2248 1.0)"
            }

            // Poisson variance test p-values via χ²(n−1)
            val myDofLabel = "n\u22121 = ${myDisp.degreesOfFreedom}"

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
 * integer data held by [pmf]. No fitted distribution is assumed.
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
 * @param pmf     the [PMFData] whose sample will be plotted
 * @param caption optional section title
 */
fun ReportBuilder.discreteVisualization(
    pmf: PMFData,
    caption: String? = null
) {
    val myTitle = caption ?: "Discrete Data Visualization"
    section(myTitle) {

        section("Frequency Distribution") {
            plot(pmf.frequency.frequencyPlot(),
                caption = "Observed Frequency Distribution")
        }

        section("Observations") {
            plot(ObservationsPlot(pmf.data),
                caption = "Observations in Sequence")
        }

        section("Autocorrelation") {
            plot(ACFPlot(pmf.data.toDoubles()),
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
 *    empirical PMF (from the fit's data) against the theoretical PMF (from the fitted distribution)
 *
 * @param fit             the [PMFFitData] to report (the goodness-of-fit object plus the data)
 * @param caption         optional section title; defaults to the fitted distribution's name
 * @param confidenceLevel significance level for the GOF test conclusion; must be in (0, 1)
 */
fun ReportBuilder.discreteGoodnessOfFit(
    fit: PMFFitData,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val gof = fit.goodnessOfFit
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
            val (myUpperP, myLowerP, myTwoSidedP) =
                DiscretePMFGoodnessOfFit.poissonDispersionPValues(myPvt, myN)
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
                PMFComparisonPlot(fit.data, gof.distribution),
                caption = "Empirical vs Theoretical PMF — ${gof.distribution}"
            )
        }
    }
}

/**
 * Backward-compatible overload that renders the discrete goodness-of-fit section
 * for a live [DiscretePMFGoodnessOfFit], using [modeler] for the PMF comparison
 * plot's empirical data. Delegates to the [PMFFitData] renderer.
 */
fun ReportBuilder.discreteGoodnessOfFit(
    gof: DiscretePMFGoodnessOfFit,
    modeler: PMFModeler,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) = discreteGoodnessOfFit(LivePmfFitData(gof, modeler), caption, confidenceLevel)

/** Adapts a live [DiscretePMFGoodnessOfFit] (+ its [PMFModeler] for the empirical data) to [PMFFitData]. */
private class LivePmfFitData(
    override val goodnessOfFit: DiscretePMFGoodnessOfFit,
    private val modeler: PMFModeler
) : PMFFitData {
    override val data get() = modeler.data
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

// The Poisson dispersion p-value computation is centralized on
// DiscretePMFGoodnessOfFit.Companion.poissonDispersionPValues / poissonDispersionTest.
