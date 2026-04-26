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

import ksl.utilities.io.report.extensions.bootstrap
import ksl.utilities.io.report.extensions.bootstrapEstimates
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.statistic.*
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV

/**
 * Demonstrates use of the bootstrap reporting framework extensions.
 *
 * Four demos:
 * 1. [demoBootstrapSingle]   — full config + estimate report via [Bootstrap.toReport]
 *                              (mean estimator, bootstrap-t CI, density plot)
 * 2. [demoBootstrapSampler]  — multivariate bootstrap via [BootstrapSampler],
 *                              rendered with [bootstrapEstimates] (showDetail = true)
 * 3. [demoBootstrapCase]     — OLS regression case bootstrap via [CaseBootstrapSampler],
 *                              rendered with [bootstrapEstimates]
 * 4. [demoBootstrapMulti]    — independent multi-dataset bootstrap via [MultiBootstrap.toReport]
 *                              (showDetail = true, density plots per factor)
 */
fun main() {
//    demoBootstrapSingle()
   demoBootstrapSampler()
//    demoBootstrapCase()
//    demoBootstrapMulti()
}

// ── Shared helpers ────────────────────────────────────────────────────────────

/** Small integer sample used for the single-mean demo (n = 10). */
private val bsSmallSample = doubleArrayOf(6.0, 7.0, 5.0, 1.0, 0.0, 4.0, 6.0, 0.0, 6.0, 1.0)

// ── Demo 1: Single Bootstrap (full config + estimate + density plot) ──────────

/**
 * Demo 1 — zero-code full bootstrap report.
 *
 * [Bootstrap.toReport] produces a document with two sections:
 * - Bootstrap Configuration (estimator, n, B requested/generated, stream, antithetic)
 * - Bootstrap estimate (statistics, bias, SE, MSE, all four CI methods, density plot)
 *
 * The bootstrap-t CI is requested by setting `numBootstrapTSamples = 399`.
 */
fun demoBootstrapSingle() {
    val myBs = Bootstrap(bsSmallSample, estimator = BSEstimatorIfc.Average(), streamNumber = 3)
    myBs.generateSamples(numBootstrapSamples = 400, numBootstrapTSamples = 399)

    println("=== Demo 1: Single Bootstrap ===")
    println(myBs)

    myBs.toReport(
        title           = "Bootstrap Mean — Small Sample",
        showDensityPlot = true
    ).showInBrowser()
}

// ── Demo 2: BootstrapSampler (multiple statistics from one dataset) ───────────

/**
 * Demo 2 — multivariate bootstrap using [BootstrapSampler] and [BasicStatistics].
 *
 * [BasicStatistics] computes 8 statistics per resample (average, variance, min, max,
 * skewness, kurtosis, lag-1 correlation, lag-1 covariance). The returned
 * `List<BootstrapEstimate>` is passed to [bootstrapEstimates] with `showDetail = true`
 * so that a per-estimate CI section is appended below the summary table.
 */
fun demoBootstrapSampler() {
    val myData   = ExponentialRV(10.0, streamNum = 1).sample(50)
    val myStat   = Statistic(myData)
    println("=== Demo 2: BootstrapSampler ===")
    println(myStat)

    val mySampler   = BootstrapSampler(myData, BasicStatistics())
    val myEstimates: List<BootstrapEstimateIfc> = mySampler.bootStrapEstimates(300)
    for (e in myEstimates) println(e.asString())

    myEstimates.toReport(
        title      = "Exponential(10) — Multivariate Bootstrap",
        showDetail = true
    ).showInBrowser()
}

// ── Demo 3: CaseBootstrapSampler (OLS regression) ────────────────────────────

/**
 * Demo 3 — OLS regression case bootstrap using [CaseBootstrapSampler].
 *
 * A 3-parameter linear model (intercept + 2 predictors) is simulated and fitted.
 * The case bootstrap resamples rows of the design matrix to produce bootstrap
 * confidence intervals on each regression coefficient. The result is a
 * `List<BootstrapEstimate>` (one per parameter) rendered via [bootstrapEstimates].
 */
fun demoBootstrapCase() {
    val myN1 = NormalRV(10.0, 3.0, streamNum = 1)
    val myN2 = NormalRV(5.0, 1.5, streamNum = 2)
    val myE  = NormalRV(streamNum = 3)
    // Y = 10 + 2·X1 + 5·X2 + ε
    val myData = Array(100) { _ ->
        val x1 = myN1.value
        val x2 = myN2.value
        doubleArrayOf(10.0 + 2.0 * x1 + 5.0 * x2 + myE.value, x1, x2)
    }

    println("=== Demo 3: CaseBootstrapSampler (OLS) ===")
    val myCbs = CaseBootstrapSampler(MatrixBootEstimator(myData, OLSBootEstimator))
    val myEstimates: List<BootstrapEstimateIfc> = myCbs.bootStrapEstimates(399)
    for (be in myEstimates) println(be)

    myEstimates.toReport(
        title      = "OLS Regression — Case Bootstrap CIs",
        showDetail = true
    ).showInBrowser()
}

// ── Demo 4: MultiBootstrap (independent datasets sharing one estimator) ───────

/**
 * Demo 4 — zero-code multi-bootstrap report.
 *
 * Three independent Exponential datasets (differing means: 5, 10, 20) are bootstrapped
 * with the same average estimator. [MultiBootstrap.toReport] produces a document
 * covering container configuration, per-factor n/B table, summary table (one row per
 * factor), and per-factor detail sections with density plots.
 */
fun demoBootstrapMulti() {
    val myDataMap = mapOf(
        "Exp(mean=5)"  to ExponentialRV(5.0,  streamNum = 1).sample(60),
        "Exp(mean=10)" to ExponentialRV(10.0, streamNum = 2).sample(60),
        "Exp(mean=20)" to ExponentialRV(20.0, streamNum = 3).sample(60)
    )

    println("=== Demo 4: MultiBootstrap ===")
    val myMb = MultiBootstrap(
        estimator = BSEstimatorIfc.Average(),
        dataMap   = myDataMap,
        name      = "Exponential Mean Comparison"
    )
    myMb.generateSamples(500)
    println(myMb)

    val myDoc = myMb.toReport(
        title           = "Multi-Bootstrap — Exponential Mean Comparison",
        showDetail      = true,
        showDensityPlot = true
    )
    myDoc.showInBrowser()
    myDoc.writeHtml()
}
