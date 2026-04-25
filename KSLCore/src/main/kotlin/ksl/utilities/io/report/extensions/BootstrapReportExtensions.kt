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
import ksl.utilities.statistic.Bootstrap
import ksl.utilities.statistic.BootstrapEstimateIfc
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.MultiBootstrap

/**
 * DSL extension functions on [ReportBuilder] for rendering bootstrap results within
 * the KSL reporting framework.
 *
 * **Separation of concerns:**
 * - [bootstrapEstimate] — core leaf node; statistics, bias, SE, MSE, and all available
 *   confidence intervals for a single [BootstrapEstimateIfc]; optional density plot
 * - [bootstrapEstimates] — aggregated summary table for a list of estimates (used for
 *   [ksl.utilities.statistic.BootstrapSampler] and
 *   [ksl.utilities.statistic.CaseBootstrapSampler] results), with optional per-estimate
 *   detail sub-sections
 * - [bootstrap] — configuration section (estimator, n, B, stream, antithetic) followed
 *   by a [bootstrapEstimate] section; dedicated to the [Bootstrap] class
 * - [multiBootstrap] — container configuration (name, estimator, factor list with n/B
 *   per factor) followed by [bootstrapEstimates] for all internal [Bootstrap] instances
 *
 * **Zero-code entry points:**
 * - [BootstrapEstimateIfc.toReport] — single-estimate report via [bootstrapEstimate]
 * - [Bootstrap.toReport] — full configuration + estimate report via [bootstrap]
 * - [List.toReport] (on `List<BootstrapEstimateIfc>`) — list summary report via [bootstrapEstimates]
 * - [MultiBootstrap.toReport] — full container report via [multiBootstrap]
 *
 * **Why [Bootstrap] has its own function separate from [bootstrapEstimate]:**
 * [Bootstrap] carries configuration that [BootstrapEstimateIfc] does not expose —
 * the estimator type, RNG stream number, antithetic flag, and the number of bootstrap
 * samples *requested* (as opposed to the number actually generated). Surfacing this
 * configuration alongside the results requires a dedicated function.
 *
 * **BCa confidence interval availability:**
 * [bcaBootstrapCI][Bootstrap.bcaBootstrapCI] is only available on [Bootstrap] instances.
 * When rendering a `List<BootstrapEstimateIfc>` via [bootstrapEstimates], the BCa columns
 * are **included only when at least one entry is a [Bootstrap] instance**. For mixed lists
 * the BCa cells show `"—"` for plain [ksl.utilities.statistic.BootstrapEstimate] entries.
 * When no entry is a [Bootstrap] (e.g. results from [ksl.utilities.statistic.BootstrapSampler]
 * or [ksl.utilities.statistic.CaseBootstrapSampler]), the BCa columns are omitted entirely.
 */

// ── DSL Function 1: Single Bootstrap Estimate ────────────────────────────────

/**
 * Appends a self-contained section reporting the results of a single
 * [BootstrapEstimateIfc]. No configuration metadata (estimator, stream, antithetic)
 * is emitted here; use [bootstrap] for [Bootstrap] instances that require configuration.
 *
 * **Produces (inside a section titled `caption` or [BootstrapEstimateIfc.name]):**
 * 1. Overview paragraph — name, label (when set), n, B, default CI level
 * 2. **Bootstrap Statistics** `DataTable` — original estimate θ, bootstrap mean E[θ*],
 *    bias estimate, standard error estimate, MSE estimate
 * 3. **Confidence Intervals** `DataTable` — Method | Lower | Upper for Std Normal,
 *    Basic, and Percentile at [confidenceLevel]; BCa and Bootstrap-t rows are added
 *    when `be` is a [Bootstrap] instance
 * 4. **Bootstrap Replicates Distribution** sub-section (only when [showDensityPlot] is `true`) —
 *    a histogram of the B bootstrap replicates showing the empirical sampling distribution
 *
 * @param be              the [BootstrapEstimateIfc] to report
 * @param caption         optional section title; defaults to [BootstrapEstimateIfc.name]
 * @param confidenceLevel confidence level for all CI computations; must be in (0, 1)
 * @param showDensityPlot when `true`, appends a histogram of the bootstrap replicates
 */
fun ReportBuilder.bootstrapEstimate(
    be: BootstrapEstimateIfc,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    showDensityPlot: Boolean = false
) {
    val myTitle = caption ?: be.name
    section(myTitle) {

        // ── Overview paragraph ────────────────────────────────────────────────
        val myLabelPart = if (be.label != null) "  |  Label: ${be.label}" else ""
        paragraph(
            "Statistic: ${be.name}$myLabelPart  |  " +
            "n = ${be.originalDataSampleSize}  |  " +
            "B = ${be.numberOfBootstraps}  |  " +
            "Default CI Level: ${be.defaultCILevel}"
        )

        // ── Bootstrap statistics ──────────────────────────────────────────────
        val myStatRows = listOf(
            listOf("Original Estimate (\u03b8)",        fmtDouble(be.originalDataEstimate)),
            listOf("Bootstrap Mean (E[\u03b8*])",       fmtDouble(be.acrossBootstrapAverage)),
            listOf("Bias Estimate (E[\u03b8*] \u2212 \u03b8)", fmtDouble(be.bootstrapBiasEstimate)),
            listOf("Std Error Estimate",                fmtDouble(be.bootstrapStdErrEstimate)),
            listOf("MSE Estimate",                      fmtDouble(be.bootstrapMSEEstimate))
        )
        dataTable(listOf("Statistic", "Value"), myStatRows,
            caption = "Bootstrap Statistics")

        // ── Confidence intervals ──────────────────────────────────────────────
        val myPct  = (confidenceLevel * 100.0).toInt()
        val mySN   = be.stdNormalBootstrapCI(confidenceLevel)
        val myBasic = be.basicBootstrapCI(confidenceLevel)
        val myPctCI = be.percentileBootstrapCI(confidenceLevel)

        val myCIRows = mutableListOf(
            listOf("Std Normal (not recommended)", fmtDouble(mySN.lowerLimit),    fmtDouble(mySN.upperLimit)),
            listOf("Basic (Centered Percentile)",  fmtDouble(myBasic.lowerLimit), fmtDouble(myBasic.upperLimit)),
            listOf("Percentile",                   fmtDouble(myPctCI.lowerLimit), fmtDouble(myPctCI.upperLimit))
        )

        // BCa and bootstrap-t are only on Bootstrap
        if (be is Bootstrap) {
            val myBCa  = be.bcaBootstrapCI(confidenceLevel)
            val myBtCI = be.bootstrapTCI(confidenceLevel)
            myCIRows += listOf("BCa (Bias-Corrected & Accelerated)",
                fmtDouble(myBCa.lowerLimit), fmtDouble(myBCa.upperLimit))
            if (myBtCI.lowerLimit.isFinite() && myBtCI.upperLimit.isFinite()) {
                myCIRows += listOf("Bootstrap-t (Percentile-t)",
                    fmtDouble(myBtCI.lowerLimit), fmtDouble(myBtCI.upperLimit))
            }
        }

        dataTable(
            headers = listOf("Method", "Lower", "Upper"),
            rows    = myCIRows,
            caption = "Bootstrap Confidence Intervals ($myPct%)"
        )

        // ── Optional density plot ─────────────────────────────────────────────
        if (showDensityPlot && be.bootstrapEstimates.isNotEmpty()) {
            section("Bootstrap Replicates Distribution") {
                val myH = Histogram.create(
                    be.bootstrapEstimates,
                    name = "Bootstrap Replicates \u2014 ${be.name}"
                )
                plot(
                    myH.histogramPlot(),
                    caption = "Distribution of Bootstrap Replicates \u2014 ${be.name}"
                )
            }
        }
    }
}

// ── DSL Function 2: List of Bootstrap Estimates ──────────────────────────────

/**
 * Appends a self-contained section summarising a list of [BootstrapEstimateIfc] objects.
 * Typical sources are [ksl.utilities.statistic.BootstrapSampler.bootStrapEstimates] and
 * [ksl.utilities.statistic.CaseBootstrapSampler.bootStrapEstimates].
 *
 * **Produces (inside a section titled `caption` or `"Bootstrap Estimates"`):**
 * 1. Overview paragraph — number of estimates k, common n, common B
 * 2. **Bootstrap Estimates Summary** `DataTable` — one row per estimate:
 *    Name | n | B | θ | E[θ*] | Bias | SE | Percentile CI | Basic CI | BCa CI (optional);
 *    the BCa columns are included only when at least one entry is a [Bootstrap] instance;
 *    for mixed lists the BCa cells show `"—"` for plain
 *    [ksl.utilities.statistic.BootstrapEstimate] entries; when no entry is a [Bootstrap]
 *    (e.g. results from [ksl.utilities.statistic.BootstrapSampler]) the BCa columns are
 *    omitted entirely
 * 3. When [showDetail] is `true`, one [bootstrapEstimate] sub-section per estimate
 *    (each with its own [showDensityPlot] setting)
 *
 * @param estimates       the list of estimates to summarise
 * @param caption         optional section title
 * @param confidenceLevel confidence level for all CI computations; must be in (0, 1)
 * @param showDetail      when `true`, appends a full [bootstrapEstimate] sub-section per estimate
 * @param showDensityPlot passed through to each [bootstrapEstimate] when [showDetail] is `true`
 */
fun ReportBuilder.bootstrapEstimates(
    estimates: List<BootstrapEstimateIfc>,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    showDetail: Boolean = false,
    showDensityPlot: Boolean = false
) {
    val myTitle = caption ?: "Bootstrap Estimates"
    section(myTitle) {

        if (estimates.isEmpty()) {
            paragraph("No bootstrap estimates are available.")
            return@section
        }

        // ── Overview paragraph ────────────────────────────────────────────────
        val myFirst = estimates.first()
        paragraph(
            "Estimates: ${estimates.size}  |  " +
            "n = ${myFirst.originalDataSampleSize}  |  " +
            "B = ${myFirst.numberOfBootstraps}  |  " +
            "CI Level: $confidenceLevel"
        )

        // ── Summary table ─────────────────────────────────────────────────────
        val myPct    = (confidenceLevel * 100.0).toInt()
        // BCa columns are included only when at least one entry is a full Bootstrap instance.
        // BootstrapSampler / CaseBootstrapSampler return plain BootstrapEstimate objects
        // that do not retain original data, so BCa cannot be computed for them.
        val myHasBca = estimates.any { it is Bootstrap }

        val myBaseHeaders = listOf(
            "Name", "n", "B",
            "\u03b8", "E[\u03b8*]", "Bias", "SE",
            "Pct CI Lower", "Pct CI Upper",
            "Basic CI Lower", "Basic CI Upper"
        )
        val myHeaders = if (myHasBca)
            myBaseHeaders + listOf("BCa CI Lower", "BCa CI Upper")
        else
            myBaseHeaders

        val mySummaryRows = estimates.map { be ->
            val myPctCI   = be.percentileBootstrapCI(confidenceLevel)
            val myBasicCI = be.basicBootstrapCI(confidenceLevel)
            val myBaseRow = listOf(
                be.name,
                be.originalDataSampleSize.toString(),
                be.numberOfBootstraps.toString(),
                fmtDouble(be.originalDataEstimate),
                fmtDouble(be.acrossBootstrapAverage),
                fmtDouble(be.bootstrapBiasEstimate),
                fmtDouble(be.bootstrapStdErrEstimate),
                fmtDouble(myPctCI.lowerLimit),   fmtDouble(myPctCI.upperLimit),
                fmtDouble(myBasicCI.lowerLimit), fmtDouble(myBasicCI.upperLimit)
            )
            if (myHasBca) {
                val (myBcaLow, myBcaHigh) = if (be is Bootstrap) {
                    val myBca = be.bcaBootstrapCI(confidenceLevel)
                    Pair(fmtDouble(myBca.lowerLimit), fmtDouble(myBca.upperLimit))
                } else {
                    Pair("\u2014", "\u2014")
                }
                myBaseRow + listOf(myBcaLow, myBcaHigh)
            } else {
                myBaseRow
            }
        }
        dataTable(myHeaders, mySummaryRows,
            caption = "Bootstrap Estimates Summary ($myPct% CI)")

        // ── Optional per-estimate detail ──────────────────────────────────────
        if (showDetail) {
            for (myBe in estimates) {
                bootstrapEstimate(
                    be                = myBe,
                    confidenceLevel   = confidenceLevel,
                    showDensityPlot   = showDensityPlot
                )
            }
        }
    }
}

// ── DSL Function 3: Bootstrap (univariate, with configuration) ────────────────

/**
 * Appends a self-contained section that first reports the [Bootstrap] configuration
 * and then delegates to [bootstrapEstimate] for the statistical results.
 *
 * **Produces (inside a section titled `caption` or `"Bootstrap — <name>"`):**
 * 1. **Bootstrap Configuration** `DataTable` — estimator type, original sample size n,
 *    number of bootstrap samples requested B, RNG stream number, antithetic flag
 * 2. Full [bootstrapEstimate] sub-section (statistics, bias, SE, MSE, all CIs
 *    including BCa and Bootstrap-t, optional density plot)
 *
 * @param bs              the [Bootstrap] instance to report
 * @param caption         optional section title
 * @param confidenceLevel confidence level for all CI computations; must be in (0, 1)
 * @param showDensityPlot when `true`, appends a histogram of the bootstrap replicates
 */
fun ReportBuilder.bootstrap(
    bs: Bootstrap,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    showDensityPlot: Boolean = false
) {
    val myTitle = caption ?: "Bootstrap \u2014 ${bs.name}"
    section(myTitle) {

        // ── Configuration ─────────────────────────────────────────────────────
        val myEstimatorName = bs.estimator.javaClass.simpleName
            .ifBlank { bs.estimator.javaClass.name }
        val myConfigRows = listOf(
            listOf("Estimator",                     myEstimatorName),
            listOf("Original Sample Size (n)",      bs.originalDataSampleSize.toString()),
            listOf("Bootstrap Samples Requested (B)", bs.numBootstrapSamples.toString()),
            listOf("Bootstrap Samples Generated",   bs.numberOfBootstraps.toString()),
            listOf("RNG Stream Number",             bs.streamNumber.toString()),
            listOf("Antithetic Variates",           bs.antithetic.toString())
        )
        dataTable(listOf("Parameter", "Value"), myConfigRows,
            caption = "Bootstrap Configuration")

        // ── Estimate results ──────────────────────────────────────────────────
        bootstrapEstimate(
            be                = bs,
            confidenceLevel   = confidenceLevel,
            showDensityPlot   = showDensityPlot
        )
    }
}

// ── DSL Function 4: MultiBootstrap ───────────────────────────────────────────

/**
 * Appends a self-contained section reporting a [MultiBootstrap] container: first the
 * container configuration (name, estimator, per-factor n and B), then a
 * [bootstrapEstimates] section for all internal [Bootstrap] instances.
 *
 * **Produces (inside a section titled `caption` or `"Multi-Bootstrap — <name>"`):**
 * 1. **Multi-Bootstrap Configuration** `DataTable` — container name, estimator type,
 *    number of factors m
 * 2. **Per-Factor Summary** `DataTable` — Factor | n | B (one row per named dataset)
 * 3. [bootstrapEstimates] section for all factors, with [showDetail] and [showDensityPlot]
 *    passed through
 *
 * @param mb              the [MultiBootstrap] to report
 * @param caption         optional section title
 * @param confidenceLevel confidence level for all CI computations; must be in (0, 1)
 * @param showDetail      when `true`, appends a full [bootstrapEstimate] sub-section
 *                        per factor inside the [bootstrapEstimates] section
 * @param showDensityPlot when `true` and [showDetail] is `true`, appends a histogram
 *                        of replicates for each factor
 */
fun ReportBuilder.multiBootstrap(
    mb: MultiBootstrap,
    caption: String? = null,
    confidenceLevel: Double = 0.95,
    showDetail: Boolean = false,
    showDensityPlot: Boolean = false
) {
    val myTitle = caption ?: "Multi-Bootstrap \u2014 ${mb.name}"
    section(myTitle) {

        // ── Container configuration ───────────────────────────────────────────
        val myEstimatorName = mb.estimator.javaClass.simpleName
            .ifBlank { mb.estimator.javaClass.name }
        val myContainerRows = listOf(
            listOf("Name",               mb.name),
            listOf("Estimator",          myEstimatorName),
            listOf("Number of Factors",  mb.getNumberFactors().toString())
        )
        dataTable(listOf("Parameter", "Value"), myContainerRows,
            caption = "Multi-Bootstrap Configuration")

        // ── Per-factor summary ────────────────────────────────────────────────
        val myFactorRows = mb.bootstrapList().map { bs ->
            listOf(
                bs.name,
                bs.originalDataSampleSize.toString(),
                bs.numberOfBootstraps.toString()
            )
        }
        dataTable(
            headers = listOf("Factor", "n", "B"),
            rows    = myFactorRows,
            caption = "Per-Factor Summary"
        )

        // ── Estimates for all factors ─────────────────────────────────────────
        bootstrapEstimates(
            estimates         = mb.bootstrapList(),
            caption           = "Bootstrap Results by Factor",
            confidenceLevel   = confidenceLevel,
            showDetail        = showDetail,
            showDensityPlot   = showDensityPlot
        )
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a single-estimate bootstrap report via
 * [bootstrapEstimate]. Does not include configuration metadata; use
 * [Bootstrap.toReport] for [Bootstrap] instances.
 *
 * Zero-code path:
 * ```kotlin
 * val be = BootstrapEstimate(name, n, estimate, replicates)
 * be.toReport().showInBrowser()
 * ```
 *
 * @param title           document title
 * @param confidenceLevel confidence level for CI computations; must be in (0, 1)
 * @param showDensityPlot when `true`, appends a histogram of the bootstrap replicates
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun BootstrapEstimateIfc.toReport(
    title: String = "Bootstrap Analysis \u2014 $name",
    confidenceLevel: Double = 0.95,
    showDensityPlot: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        bootstrapEstimate(this@toReport, confidenceLevel = confidenceLevel,
            showDensityPlot = showDensityPlot)
    }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full bootstrap report (configuration
 * + results) via [bootstrap].
 *
 * Zero-code path:
 * ```kotlin
 * val bs = Bootstrap(data, BSEstimatorIfc.Average())
 * bs.generateSamples(999)
 * bs.toReport().showInBrowser()
 * ```
 *
 * Custom block:
 * ```kotlin
 * bs.toReport("Mean Bootstrap — Waiting Time") {
 *     bootstrap(bs, showDensityPlot = true)
 *     paragraph("The BCa interval is preferred for skewed estimators.")
 * }
 * ```
 *
 * @param title           document title
 * @param confidenceLevel confidence level for CI computations; must be in (0, 1)
 * @param showDensityPlot when `true`, appends a histogram of the bootstrap replicates
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun Bootstrap.toReport(
    title: String = "Bootstrap Analysis \u2014 $name",
    confidenceLevel: Double = 0.95,
    showDensityPlot: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        bootstrap(this@toReport, confidenceLevel = confidenceLevel,
            showDensityPlot = showDensityPlot)
    }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a bootstrap estimates list report via
 * [bootstrapEstimates]. Intended for the output of
 * [ksl.utilities.statistic.BootstrapSampler.bootStrapEstimates] and
 * [ksl.utilities.statistic.CaseBootstrapSampler.bootStrapEstimates].
 *
 * Zero-code path:
 * ```kotlin
 * val estimates = BootstrapSampler(data, BasicStatistics()).bootStrapEstimates(999)
 * estimates.toReport("Basic Statistics Bootstrap").showInBrowser()
 * ```
 *
 * @param title           document title
 * @param confidenceLevel confidence level for CI computations; must be in (0, 1)
 * @param showDetail      when `true`, appends a full [bootstrapEstimate] sub-section per estimate
 * @param showDensityPlot passed through to each [bootstrapEstimate] when [showDetail] is `true`
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun List<BootstrapEstimateIfc>.toReport(
    title: String = "Bootstrap Estimates",
    confidenceLevel: Double = 0.95,
    showDetail: Boolean = false,
    showDensityPlot: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        bootstrapEstimates(this@toReport, confidenceLevel = confidenceLevel,
            showDetail = showDetail, showDensityPlot = showDensityPlot)
    }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full multi-bootstrap report via
 * [multiBootstrap].
 *
 * Zero-code path:
 * ```kotlin
 * val mb = MultiBootstrap(dataMap = mapOf("System A" to dataA, "System B" to dataB))
 * mb.generateSamples(999)
 * mb.toReport().showInBrowser()
 * ```
 *
 * Custom block:
 * ```kotlin
 * mb.toReport("Throughput Comparison") {
 *     multiBootstrap(mb, showDetail = true, showDensityPlot = true)
 *     paragraph("Both systems have overlapping percentile CIs at the 95% level.")
 * }
 * ```
 *
 * @param title           document title
 * @param confidenceLevel confidence level for CI computations; must be in (0, 1)
 * @param showDetail      when `true`, appends per-factor [bootstrapEstimate] sub-sections
 * @param showDensityPlot when `true` and [showDetail] is `true`, appends replicate histograms
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun MultiBootstrap.toReport(
    title: String = "Multi-Bootstrap Analysis \u2014 $name",
    confidenceLevel: Double = 0.95,
    showDetail: Boolean = false,
    showDensityPlot: Boolean = false,
    block: ReportBuilder.() -> Unit = {
        multiBootstrap(this@toReport, confidenceLevel = confidenceLevel,
            showDetail = showDetail, showDensityPlot = showDensityPlot)
    }
): ReportNode.Document = report(title, block)

