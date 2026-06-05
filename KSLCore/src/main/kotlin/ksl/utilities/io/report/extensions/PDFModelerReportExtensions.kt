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

import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.distributions.fitting.PDFData
import ksl.utilities.distributions.fitting.PDFFitData
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PDFModelingResults
import ksl.utilities.distributions.fitting.ScoringResult
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.BoxPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.BoxPlotSummary

/**
 * DSL extension functions on [ReportBuilder] for rendering [PDFModeler] and
 * [PDFModelingResults] results within the KSL reporting framework.
 *
 * **Separation of concerns:**
 * - [dataStatisticalSummary] — tabular data characteristics (statistics, box-plot
 *   summary, histogram bins, shift analysis); no plots
 * - [dataVisualization] — exploratory plots only (histogram, box plot,
 *   observations, ACF); no tables
 * - [goodnessOfFit] — bootstrap parameter CIs, four fit-diagnostic plots, and
 *   structured GOF test tables for a single [ScoringResult]
 * - [allGoodnessOfFit] — calls [goodnessOfFit] for every fitted distribution
 *   sorted by overall MODA value
 *
 * **Zero-code entry points:**
 * - [PDFModeler.toReport] — data exploration report (stats + visualization)
 * - [PDFModelingResults.toReport] — full fitting report (stats + viz + MODA
 *   scoring + GOF for the recommended distribution, or all distributions)
 */

// ── DSL Function 1: Data Statistical Summary ─────────────────────────────────

/**
 * Appends a self-contained section summarising the characteristics of the raw
 * data held by [data], with no plots.
 *
 * **Produces (inside a section titled `caption` or `"Data Statistical Summary"`):**
 * 1. Overview paragraph — n, mean, std dev, zero count, negative count
 * 2. `StatPropertyTable` — full 18-row property sheet on the sample statistics
 * 3. **Box Plot Summary** sub-section — five-number summary + fence values as a
 *    `DataTable`; outlier summary table (Category | Fence Range | Count | Obs. Min |
 *    Obs. Max); when [showOutlierValues] is `true`, per-category detail tables listing
 *    individual outlier values are appended after the summary table
 * 4. **Histogram** sub-section — bin frequency table + statistics on binned data
 *    (no plot; call [dataVisualization] for the histogram plot)
 * 5. **Shift Parameter Analysis** sub-section — estimated left-shift, zero/negative
 *    flags, tolerance, and bootstrap 95 % CI for the minimum
 *
 * @param data              the [PDFData] whose sample will be summarised
 * @param caption           optional section title
 * @param confidenceLevel   confidence level for the `StatPropertyTable` CI; must be in (0, 1)
 * @param showOutlierValues when `false` (default) each outlier category is summarised
 *                          in a single row (count, observed min/max). When `true`, a
 *                          per-category `DataTable` listing every individual outlier
 *                          value is appended for each non-empty category.
 */
fun ReportBuilder.dataStatisticalSummary(
    data: PDFData,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    showOutlierValues: Boolean = false
) {
    val myTitle = caption ?: "Data Statistical Summary"
    section(myTitle) {

        // ── Overview paragraph ────────────────────────────────────────────────
        val myStat = data.statistics
        paragraph(
            "n = ${myStat.count.toInt()}  |  " +
            "Mean = ${fmtDouble(myStat.average)}  |  " +
            "Std Dev = ${fmtDouble(myStat.standardDeviation)}  |  " +
            "Min = ${fmtDouble(myStat.min)}  |  " +
            "Max = ${fmtDouble(myStat.max)}  |  " +
            "Zeros = ${data.hasZeroes}  |  " +
            "Negatives = ${data.hasNegatives}"
        )

        // ── Full property sheet ───────────────────────────────────────────────
        statPropertyTable(
            stat = myStat,
            caption = "Sample Statistics",
            confidenceLevel = confidenceLevel
        )

        // ── Box plot summary ──────────────────────────────────────────────────
        section("Box Plot Summary") {
            val myBp     = BoxPlotSummary(data.originalData)
            val myBpRows = myBp.asMap().map { (k, v) -> listOf(k, fmtDouble(v)) }
            dataTable(
                listOf("Property", "Value"), myBpRows,
                caption = "Five-Number Summary and Fences"
            )

            // ── Extract the four outlier groups once ──────────────────────────
            val myExtLow   = myBp.lowerOuterPoints()
            val myMildLow  = myBp.pointsBtwLowerInnerAndOuterFences()
            val myMildHigh = myBp.pointsBtwUpperInnerAndOuterFences()
            val myExtHigh  = myBp.upperOuterPoints()

            // ── Option A: compact 4-row summary table (always shown) ──────────
            val mySummaryHeaders = listOf("Category", "Fence Range", "Count", "Obs. Min", "Obs. Max")
            val mySummaryRows = listOf(
                listOf(
                    "Extremely Low",
                    "x \u2264 ${fmtDouble(myBp.lowerOuterFence)}",
                    myExtLow.size.toString(),
                    if (myExtLow.isEmpty()) "\u2014" else fmtDouble(myExtLow.first()),
                    if (myExtLow.isEmpty()) "\u2014" else fmtDouble(myExtLow.last())
                ),
                listOf(
                    "Mildly Low",
                    "${fmtDouble(myBp.lowerOuterFence)} \u2264 x \u2264 ${fmtDouble(myBp.lowerInnerFence)}",
                    myMildLow.size.toString(),
                    if (myMildLow.isEmpty()) "\u2014" else fmtDouble(myMildLow.first()),
                    if (myMildLow.isEmpty()) "\u2014" else fmtDouble(myMildLow.last())
                ),
                listOf(
                    "Mildly High",
                    "${fmtDouble(myBp.upperInnerFence)} \u2264 x \u2264 ${fmtDouble(myBp.upperOuterFence)}",
                    myMildHigh.size.toString(),
                    if (myMildHigh.isEmpty()) "\u2014" else fmtDouble(myMildHigh.first()),
                    if (myMildHigh.isEmpty()) "\u2014" else fmtDouble(myMildHigh.last())
                ),
                listOf(
                    "Extremely High",
                    "x \u2265 ${fmtDouble(myBp.upperOuterFence)}",
                    myExtHigh.size.toString(),
                    if (myExtHigh.isEmpty()) "\u2014" else fmtDouble(myExtHigh.first()),
                    if (myExtHigh.isEmpty()) "\u2014" else fmtDouble(myExtHigh.last())
                )
            )
            dataTable(mySummaryHeaders, mySummaryRows, caption = "Outlier Summary")

            // ── Option B: per-category value tables (only when requested) ─────
            if (showOutlierValues) {
                val myDetailGroups = listOf(
                    "Extremely Low"  to myExtLow,
                    "Mildly Low"     to myMildLow,
                    "Mildly High"    to myMildHigh,
                    "Extremely High" to myExtHigh
                )
                for ((myLabel, myPoints) in myDetailGroups) {
                    if (myPoints.isNotEmpty()) {
                        dataTable(
                            headers = listOf("Value"),
                            rows    = myPoints.map { listOf(fmtDouble(it)) },
                            caption = "$myLabel Values (${myPoints.size})"
                        )
                    }
                }
            }
        }

        // ── Histogram (table + stats, no plot) ────────────────────────────────
        histogram(data.histogram, caption = "Histogram", showPlot = false,
            confidenceLevel = confidenceLevel)

        // ── Shift parameter analysis ──────────────────────────────────────────
        section("Shift Parameter Analysis") {
            val myShift = data.leftShift
            val myMinCI = data.confidenceIntervalForMinimum()
            val myRows = listOf(
                listOf("Estimated Left Shift",      fmtDouble(myShift)),
                listOf("Has Zeros",                 data.hasZeroes.toString()),
                listOf("Has Negatives",             data.hasNegatives.toString()),
                listOf("Zero Tolerance",            fmtDouble(data.defaultZeroTolerance)),
                listOf("CI for Minimum — Lower",    fmtDouble(myMinCI.lowerLimit)),
                listOf("CI for Minimum — Upper",    fmtDouble(myMinCI.upperLimit))
            )
            dataTable(listOf("Property", "Value"), myRows,
                caption = "Left-Shift Estimation (95 % Bootstrap CI for Minimum)")
        }
    }
}

// ── DSL Function 2: Data Visualization ───────────────────────────────────────

/**
 * Appends a self-contained section containing four exploratory plots for the
 * data held by [data].
 *
 * **Produces (inside a section titled `caption` or `"Data Visualization"`):**
 * 1. **Histogram** sub-section — histogram bar chart
 * 2. **Box Plot** sub-section — box-and-whisker plot
 * 3. **Observations** sub-section — values in observation order
 * 4. **Autocorrelation** sub-section — ACF plot
 *
 * @param data    the [PDFData] whose sample will be plotted
 * @param caption optional section title
 */
fun ReportBuilder.dataVisualization(
    data: PDFData,
    caption: String? = null
) {
    val myTitle = caption ?: "Data Visualization"
    section(myTitle) {

        section("Histogram") {
            plot(data.histogram.histogramPlot(), caption = "Histogram")
        }

        section("Box Plot") {
            plot(BoxPlot(BoxPlotSummary(data.originalData)), caption = "Box Plot")
        }

        section("Observations") {
            plot(ObservationsPlot(data.originalData),
                caption = "Observations in Sequence")
        }

        section("Autocorrelation") {
            plot(ACFPlot(data.originalData),
                caption = "Autocorrelation Function (ACF)")
        }
    }
}

// ── DSL Function 3: Goodness of Fit (single distribution) ────────────────────

/**
 * Appends a self-contained section reporting the goodness-of-fit results for a
 * single fitted distribution ([PDFFitData]).
 *
 * **Produces (inside a section titled `caption` or the fit's name):**
 * 1. Overview paragraph — distribution name, RV family, parameter count
 * 2. **Bootstrap Parameter Estimates** sub-section — one `DataTable` per parameter,
 *    showing original estimate, bootstrap average, bias, standard error, number of
 *    bootstrap samples, and three CI variants (normal, basic, percentile)
 * 3. **Distribution Fit Plots** sub-section — four individual diagnostic plots:
 *    density overlay, Q-Q, ECDF vs theoretical CDF, P-P
 * 4. **Goodness of Fit Tests** sub-section — chi-squared bin table (with `Expected < 5`
 *    warnings) and a summary table of KS, Anderson-Darling, and Cramér-von Mises
 *    test statistics and p-values
 *
 * **Note on fit plots:** [ksl.utilities.io.plotting.FitDistPlot] builds a
 * `GGBunch` (a multi-panel figure) and does not implement [ksl.utilities.io.plotting.PlotIfc].
 * Its four component plots (`densityPlot`, `qqPlot`, `ecdfPlot`, `ppPlot`) each extend
 * [ksl.utilities.io.plotting.BasePlot] and implement [ksl.utilities.io.plotting.PlotIfc]
 * directly, so they are rendered individually with their own captions.
 *
 * @param fit             the [PDFFitData] to analyse
 * @param caption         optional section title; defaults to the fit's name
 * @param confidenceLevel confidence level for bootstrap CIs; must be in (0, 1)
 */
fun ReportBuilder.goodnessOfFit(
    fit: PDFFitData,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: fit.name
    section(myTitle) {

        // ── Overview ──────────────────────────────────────────────────────────
        paragraph(
            "Distribution: ${fit.name}  |  " +
            "RV Type: ${fit.rvType}  |  " +
            "Parameters: ${fit.numberOfParameters}  |  " +
            "MODA Value: ${fmtDouble(fit.weightedValue)}  |  " +
            "Avg Rank: ${fmtDouble(fit.averageRanking)}"
        )

        // ── Bootstrap parameter estimates ─────────────────────────────────────
        section("Bootstrap Parameter Estimates") {
            val myBootstraps = fit.bootstrapParameterEstimates(confidenceLevel)
            if (myBootstraps.isEmpty()) {
                paragraph("No bootstrap estimates available for this distribution.")
            } else {
                for (myBse in myBootstraps) {
                    val myNormCI  = myBse.stdNormalBootstrapCI(confidenceLevel)
                    val myBasicCI = myBse.basicBootstrapCI(confidenceLevel)
                    val myPctCI   = myBse.percentileBootstrapCI(confidenceLevel)
                    val myRows = listOf(
                        listOf("Parameter",              myBse.name),
                        listOf("Original Sample Size",   myBse.originalDataSampleSize.toString()),
                        listOf("Original Estimate",      fmtDouble(myBse.originalDataEstimate)),
                        listOf("Bootstrap Average",      fmtDouble(myBse.acrossBootstrapAverage)),
                        listOf("Bias Estimate",          fmtDouble(myBse.bootstrapBiasEstimate)),
                        listOf("Bootstrap MSE Estimate", fmtDouble(myBse.bootstrapMSEEstimate)),
                        listOf("Std. Error Estimate",    fmtDouble(myBse.bootstrapStdErrEstimate)),
                        listOf("Num Bootstraps",         myBse.numberOfBootstraps.toString()),
                        listOf("CI Level",               fmtDouble(confidenceLevel)),
                        listOf("Normal CI Lower",        fmtDouble(myNormCI.lowerLimit)),
                        listOf("Normal CI Upper",        fmtDouble(myNormCI.upperLimit)),
                        listOf("Basic CI Lower",         fmtDouble(myBasicCI.lowerLimit)),
                        listOf("Basic CI Upper",         fmtDouble(myBasicCI.upperLimit)),
                        listOf("Percentile CI Lower",    fmtDouble(myPctCI.lowerLimit)),
                        listOf("Percentile CI Upper",    fmtDouble(myPctCI.upperLimit))
                    )
                    val myCaption = myBse.label ?: myBse.name
                    dataTable(listOf("Property", "Value"), myRows, caption = myCaption)
                }
            }
        }

        // ── Distribution fit plots ────────────────────────────────────────────
        section("Distribution Fit Plots") {
            val myFitPlot = fit.distributionFitPlot()
            plot(myFitPlot.densityPlot,
                caption = "Density — Empirical vs Theoretical PDF")
            plot(myFitPlot.qqPlot,
                caption = "Q-Q Plot")
            plot(myFitPlot.ecdfPlot,
                caption = "ECDF vs Theoretical CDF")
            plot(myFitPlot.ppPlot,
                caption = "P-P Plot")
        }

        // ── Goodness of fit tests ─────────────────────────────────────────────
        section("Goodness of Fit Tests") {
            val myGof = ContinuousCDFGoodnessOfFit(
                fit.testData,
                fit.distribution,
                numEstimatedParameters = fit.numberOfParameters
            )

            paragraph(
                "Estimated parameters: ${myGof.numEstimatedParameters}  |  " +
                "Intervals: ${myGof.histogram.numberBins}  |  " +
                "Chi-Sq DOF: ${myGof.chiSquaredTestDOF}"
            )

            // Chi-squared bin table
            val myChiHeaders = listOf("Bin Label", "P(Bin)", "Observed", "Expected", "Note")
            val myChiRows = myGof.histogram.bins.mapIndexed { i, bin ->
                val myExpected = myGof.expectedCounts[i]
                listOf(
                    bin.binLabel,
                    fmtDouble(myGof.binProbabilities[i]),
                    bin.count.toInt().toString(),
                    fmtDouble(myExpected),
                    if (myExpected <= 4.99999) "Expected < 5" else ""
                )
            }
            dataTable(myChiHeaders, myChiRows, caption = "Chi-Squared Bin Table")

            // Summary test statistics
            val myTestHeaders = listOf("Test", "Statistic", "p-value")
            val myTestRows = listOf(
                listOf("Chi-Squared (DOF = ${myGof.chiSquaredTestDOF})",
                    fmtDouble(myGof.chiSquaredTestStatistic),
                    fmtDouble(myGof.chiSquaredPValue)),
                listOf("Kolmogorov-Smirnov",
                    fmtDouble(myGof.ksStatistic),
                    fmtDouble(myGof.ksPValue)),
                listOf("Anderson-Darling",
                    fmtDouble(myGof.andersonDarlingStatistic),
                    fmtDouble(myGof.andersonDarlingPValue)),
                listOf("Cramér-von Mises",
                    fmtDouble(myGof.cramerVonMisesStatistic),
                    fmtDouble(myGof.cramerVonMisesPValue))
            )
            dataTable(myTestHeaders, myTestRows,
                caption = "Goodness of Fit Test Statistics")
        }
    }
}

/**
 * Backward-compatible overload that renders the goodness-of-fit section for a
 * live [ScoringResult], using [modeler] for the bootstrap parameter estimates.
 * Delegates to the [PDFFitData] renderer.
 */
fun ReportBuilder.goodnessOfFit(
    result: ScoringResult,
    modeler: PDFModeler,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) = goodnessOfFit(LivePdfFitData(result, modeler), caption, confidenceLevel)

/** Adapts a live [ScoringResult] (+ its [PDFModeler] for bootstrapping) to [PDFFitData]. */
private class LivePdfFitData(
    private val result: ScoringResult,
    private val modeler: PDFModeler
) : PDFFitData {
    override val name get() = result.name
    override val rvType get() = result.rvType
    override val numberOfParameters get() = result.numberOfParameters
    override val weightedValue get() = result.weightedValue
    override val averageRanking get() = result.averageRanking
    override val distribution get() = result.distribution
    override val testData get() = result.estimationResult.testData
    override fun distributionFitPlot() = result.distributionFitPlot()
    override fun bootstrapParameterEstimates(level: Double) =
        modeler.bootStrapParameterEstimates(result.estimationResult, level = level)
}

// ── DSL Function 4: Goodness of Fit (all distributions) ──────────────────────

/**
 * Appends a self-contained section containing a [goodnessOfFit] sub-section for
 * every distribution in [results], sorted by overall MODA value (best first).
 *
 * **Produces (inside a section titled `caption` or
 * `"Goodness of Fit — All Fitted Distributions"`):**
 * - Overview paragraph stating the number of distributions and sort criterion
 * - One collapsible [goodnessOfFit] sub-section per [ScoringResult]
 *
 * @param results         the [PDFModelingResults] containing all scoring results
 * @param modeler         the [PDFModeler] used for bootstrapping
 * @param caption         optional section title
 * @param confidenceLevel confidence level for bootstrap CIs and GOF tests; must be in (0, 1)
 */
fun ReportBuilder.allGoodnessOfFit(
    results: PDFModelingResults,
    modeler: PDFModeler,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "Goodness of Fit — All Fitted Distributions"
    section(myTitle) {
        paragraph(
            "${results.scoringResults.size} distributions fitted and evaluated; " +
            "sorted by overall MODA value (best first)."
        )
        for (myResult in results.resultsSortedByScoring) {
            goodnessOfFit(myResult, modeler, confidenceLevel = confidenceLevel)
        }
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a data exploration report for this
 * [PDFModeler] — statistical summary and visualization plots — with no fitting
 * results. Suitable for EDA before committing to a fitting run.
 *
 * Zero-code path:
 * ```kotlin
 * val modeler = PDFModeler(data)
 * modeler.toReport().showInBrowser()
 * modeler.toReport().writeMarkdown()
 * ```
 *
 * Custom block (use the captured local variable, not `this@toReport`):
 * ```kotlin
 * modeler.toReport("My Data — EDA") {
 *     dataStatisticalSummary(modeler)
 *     dataVisualization(modeler)
 *     paragraph("Data appears right-skewed; consider Exponential or Gamma.")
 * }
 * ```
 *
 * @param title           document title
 * @param confidenceLevel confidence level for the statistical property sheet
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun PDFModeler.toReport(
    title: String = "Distribution Fitting — Data Analysis",
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        dataStatisticalSummary(this@toReport, confidenceLevel = confidenceLevel)
        dataVisualization(this@toReport)
    }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full distribution-fitting report:
 * data statistical summary, visualization plots, MODA scoring results, and
 * goodness-of-fit analysis.
 *
 * The [modeler] parameter is required because [PDFModelingResults] does not hold a
 * back-reference to the [PDFModeler] that produced it, and the data-exploration
 * sections need the original data.
 *
 * Zero-code path (GOF for recommended distribution only):
 * ```kotlin
 * val modeler = PDFModeler(data)
 * val results = modeler.estimateAndEvaluateScores()
 * results.toReport(modeler).showInBrowser()
 * results.toReport(modeler).writeHtml()
 * ```
 *
 * Zero-code path (GOF for all fitted distributions):
 * ```kotlin
 * results.toReport(modeler, allGOF = true).showInBrowser()
 * ```
 *
 * Custom block (use the captured local variables, not `this@toReport`):
 * ```kotlin
 * results.toReport(modeler, "My Fitting Study") {
 *     dataStatisticalSummary(modeler)
 *     moda(results.evaluationModel, caption = "MODA Scoring")
 *     goodnessOfFit(results.topResultByScore, modeler)
 * }
 * ```
 *
 * @param modeler         the [PDFModeler] that produced these results
 * @param title           document title
 * @param confidenceLevel confidence level for all CIs and GOF tests; must be in (0, 1)
 * @param allGOF          when `true` GOF is reported for every fitted distribution
 *                        (sorted by MODA value); when `false` (default) only the
 *                        top-ranked distribution's GOF is included
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun PDFModelingResults.toReport(
    modeler: PDFModeler,
    title: String = "Distribution Fitting Analysis",
    confidenceLevel: Double = 0.95,
    allGOF: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        dataStatisticalSummary(modeler, confidenceLevel = confidenceLevel)
        dataVisualization(modeler)
        moda(this@toReport.evaluationModel, caption = "MODA Scoring Results")
        if (allGOF) {
            allGoodnessOfFit(this@toReport, modeler, confidenceLevel = confidenceLevel)
        } else {
            goodnessOfFit(this@toReport.topResultByScore, modeler,
                confidenceLevel = confidenceLevel)
        }
    }
): ReportNode.Document = report(title, block)


