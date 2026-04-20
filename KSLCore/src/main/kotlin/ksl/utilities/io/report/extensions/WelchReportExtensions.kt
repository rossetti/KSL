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

import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.observers.welch.WelchFileObserver
import ksl.utilities.io.plotting.PartialSumsPlot
import ksl.utilities.io.plotting.WelchPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for warm-up analysis using the
 * `ksl.observers.welch` pipeline.
 *
 * The primary entry points are the two [welchAnalysis] overloads:
 *
 * - **`welchAnalysis(observer)`** — the most common call; takes a
 *   [WelchFileObserver] that was attached before `model.simulate()` and
 *   internally creates the [WelchDataFileAnalyzer].
 * - **`welchAnalysis(analyzer)`** — lower-level entry point for analyses
 *   loaded from persisted `.wdf` / `.json` file pairs.
 *
 * Every report section contains:
 * 1. A metadata paragraph (statistic type, batch size, replications,
 *    minimum observation count, average time per observation).
 * 2. A per-replication summary table (rep number, observation count,
 *    avg time per obs, replication average).
 * 3. A [WelchPlot] showing the Welch average and cumulative average.
 * 4. A [PartialSumsPlot] (when [includePartialSums] is `true`).
 * 5. An MSER deletion-point recommendation table.
 * 6. A Schruben initialization bias test section (when [includeBiasTest] is `true`).
 * 7. A batch-means analysis section (when [includeBatchMeans] is `true`).
 *
 * The [deletionPoint] parameter controls where the post-deletion analyses
 * (bias test and batch means) start:
 * - `-1` (default) — use [WelchDataFileAnalyzer.recommendDeletionPoint]
 *   automatically (MSER rule)
 * - any non-negative integer — use that observation index directly,
 *   overriding the MSER recommendation
 *
 * Zero-code usage:
 * ```kotlin
 * val myObserver = WelchFileObserver(dtp.systemTime, 1.0)
 * model.simulate()
 * myObserver.toReport().showInBrowser()
 * ```
 *
 * Custom report with all optional sections:
 * ```kotlin
 * val myDoc = report("Warm-Up Analysis") {
 *     paragraph("Drive-through pharmacy — system time.")
 *     welchAnalysis(myObserver,
 *         includePartialSums = true,
 *         includeBiasTest    = true,
 *         includeBatchMeans  = true)
 * }
 * myDoc.showInBrowser()
 * myDoc.writeMarkdown()
 * ```
 */

// ── Private formatting helper ─────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtD(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.4f".format(value)
}

// ── welchAnalysis(WelchFileObserver) ─────────────────────────────────────────

/**
 * Appends a warm-up analysis section for a [WelchFileObserver].
 *
 * This overload creates the [WelchDataFileAnalyzer] internally from [observer]
 * and delegates to [welchAnalysis(WelchDataFileAnalyzer)].
 *
 * @param observer           the observer attached before simulation
 * @param includePartialSums `true` (default) includes a [PartialSumsPlot]
 * @param includeBatchMeans  `true` appends a batch-means analysis section
 *                           using the welch averages after the deletion point
 * @param includeBiasTest    `true` appends a Schruben initialization bias test
 *                           section between the MSER table and the batch-means
 *                           section; the test is applied to Welch averages
 *                           batched with [WelchDataFileAnalyzer.BIAS_TEST_BATCH_SIZE]
 * @param deletionPoint      observation index to use as the start of the
 *                           post-deletion analysis; `-1` (default) means use
 *                           the MSER-recommended deletion point automatically
 */
fun ReportBuilder.welchAnalysis(
    observer: WelchFileObserver,
    includePartialSums: Boolean = true,
    includeBatchMeans:  Boolean = false,
    includeBiasTest:    Boolean = false,
    deletionPoint:      Int     = -1
) {
    welchAnalysis(
        analyzer           = observer.createWelchDataFileAnalyzer(),
        includePartialSums = includePartialSums,
        includeBatchMeans  = includeBatchMeans,
        includeBiasTest    = includeBiasTest,
        deletionPoint      = deletionPoint
    )
}

// ── welchAnalysis(WelchDataFileAnalyzer) ─────────────────────────────────────

/**
 * Appends a warm-up analysis section for a [WelchDataFileAnalyzer].
 *
 * @param analyzer           the analyzer to report from
 * @param includePartialSums `true` (default) includes a [PartialSumsPlot]
 * @param includeBatchMeans  `true` appends a batch-means analysis section
 * @param includeBiasTest    `true` appends a Schruben initialization bias test
 *                           section; the test is applied to Welch averages
 *                           batched with [WelchDataFileAnalyzer.BIAS_TEST_BATCH_SIZE]
 * @param deletionPoint      observation index for post-deletion analysis;
 *                           `-1` means use the MSER recommendation
 */
fun ReportBuilder.welchAnalysis(
    analyzer: WelchDataFileAnalyzer,
    includePartialSums: Boolean = true,
    includeBatchMeans:  Boolean = false,
    includeBiasTest:    Boolean = false,
    deletionPoint:      Int     = -1
) {
    val myEffectiveDeletionPt   = if (deletionPoint < 0) {
        analyzer.recommendDeletionPoint()
    } else {
        deletionPoint
    }
    val myEffectiveDeletionTime = myEffectiveDeletionPt * analyzer.averageTimePerObservation

    section(analyzer.responseName) {

        // ── 1. Metadata paragraph ─────────────────────────────────────────
        val myBean = analyzer.welchFileMetaDataBean
        paragraph(
            "Response: ${analyzer.responseName}  |  " +
            "Type: ${myBean.statisticType}  |  " +
            "Batch size: ${myBean.batchSize}  |  " +
            "Replications: ${analyzer.numberOfReplications}  |  " +
            "Min observations: ${analyzer.minNumObservationsInReplications}  |  " +
            "Avg time per obs: ${fmtD(analyzer.averageTimePerObservation)}"
        )

        // ── 2. Per-replication summary table ──────────────────────────────
        val mySummaries = analyzer.replicationSummaries()
        dataTable(
            headers = listOf("Replication", "Observations", "Avg Time Per Obs", "Replication Average"),
            rows    = mySummaries.map { s ->
                listOf(
                    s.replicationNumber.toString(),
                    s.observationCount.toString(),
                    fmtD(s.avgTimePerObservation),
                    fmtD(s.replicationAverage)
                )
            },
            caption = "Per-Replication Summary"
        )

        // ── 3. Welch plot ─────────────────────────────────────────────────
        plot(WelchPlot(analyzer))

        // ── 4. Partial sums plot (optional) ───────────────────────────────
        if (includePartialSums) {
            plot(PartialSumsPlot(analyzer))
        }

        // ── 5. MSER deletion-point recommendation ─────────────────────────
        dataTable(
            headers = listOf("Property", "Value"),
            rows    = listOf(
                listOf("Recommended Deletion Observation", myEffectiveDeletionPt.toString()),
                listOf("Recommended Deletion Time (approx.)",  fmtD(myEffectiveDeletionTime))
            ),
            caption = if (deletionPoint < 0) "MSER Deletion-Point Recommendation"
                      else "User-Supplied Deletion Point"
        )

        // ── 6. Initialization bias test (optional) ────────────────────────
        if (includeBiasTest) {
            section("Initialization Bias Test") {
                val myBiasBS     = analyzer.batchWelchAverages(
                    myEffectiveDeletionPt,
                    WelchDataFileAnalyzer.BIAS_TEST_BATCH_SIZE
                )
                val myBatchMeans = myBiasBS.batchMeans

                val myFPos = Statistic.positiveBiasTestStatistic(myBatchMeans)
                val myFNeg = Statistic.negativeBiasTestStatistic(myBatchMeans)
                val myPPos = Statistic.welchBiasTestPValue(myFPos)
                val myPNeg = Statistic.welchBiasTestPValue(myFNeg)

                val myRejPos   = !myPPos.isNaN() && myPPos < 0.05
                val myRejNeg   = !myPNeg.isNaN() && myPNeg < 0.05
                val myDecision = when {
                    myFPos.isNaN() || myFNeg.isNaN() ->
                        "Insufficient batch means for test (\u2265 4 required)"
                    myRejPos && myRejNeg ->
                        "Both positive and negative bias detected"
                    myRejPos ->
                        "Positive initialization bias detected"
                    myRejNeg ->
                        "Negative initialization bias detected"
                    else ->
                        "No initialization bias detected at \u03B1 = 0.05"
                }

                paragraph(
                    "The Schruben initialization bias test is applied to Welch averages " +
                    "from observation ${myEffectiveDeletionPt + 1} onward, batched with " +
                    "batch size ${WelchDataFileAnalyzer.BIAS_TEST_BATCH_SIZE}. " +
                    "H\u2080: no initialization bias. " +
                    "Reject H\u2080 when p-value < 0.05 " +
                    "(F distribution with 3 and 3 degrees of freedom)."
                )

                dataTable(
                    headers = listOf("Property", "Value"),
                    rows    = listOf(
                        listOf("Batch size for test",        WelchDataFileAnalyzer.BIAS_TEST_BATCH_SIZE.toString()),
                        listOf("Number of batch means",      myBiasBS.numBatches.toString()),
                        listOf("Positive bias F statistic",  fmtD(myFPos)),
                        listOf("Positive bias p-value",      fmtD(myPPos)),
                        listOf("Negative bias F statistic",  fmtD(myFNeg)),
                        listOf("Negative bias p-value",      fmtD(myPNeg)),
                        listOf("Decision (\u03B1 = 0.05)",   myDecision)
                    ),
                    caption = "Schruben Initialization Bias Test"
                )
            }
        }

        // ── 7. Batch-means analysis (optional) ────────────────────────────
        if (includeBatchMeans) {
            section("Batch-Means Analysis (post-deletion)") {
                paragraph(
                    "Welch averages from observation ${myEffectiveDeletionPt + 1} onward " +
                    "batched using the default batch size (${WelchDataFileAnalyzer.MIN_BATCH_SIZE})."
                )
                batchStatistic(
                    bs      = analyzer.batchWelchAverages(myEffectiveDeletionPt),
                    caption = "Batch Means — ${analyzer.responseName}"
                )
            }
        }
    }
}

// ── WelchFileObserver.toReport ────────────────────────────────────────────────

/**
 * Builds a [ReportNode.Document] for this [WelchFileObserver].
 *
 * The default block calls [welchAnalysis] with the supplied parameters.
 * A custom [block] replaces the default content entirely.
 *
 * Zero-code usage:
 * ```kotlin
 * myObserver.toReport().showInBrowser()
 * myObserver.toReport(includeBatchMeans = true).writeMarkdown()
 * myObserver.toReport(includeBiasTest = true, includeBatchMeans = true).showInBrowser()
 * ```
 *
 * Custom block:
 * ```kotlin
 * myObserver.toReport("Queue Warm-Up Study") {
 *     paragraph("System time for 5 replications.")
 *     welchAnalysis(myObserver,
 *         includePartialSums = true,
 *         includeBiasTest    = true,
 *         includeBatchMeans  = true)
 * }
 * ```
 *
 * @param title              document title; defaults to [WelchFileObserver.responseName]
 * @param includePartialSums `true` (default) includes a [PartialSumsPlot]
 * @param includeBatchMeans  `true` appends a batch-means analysis section
 * @param includeBiasTest    `true` appends a Schruben initialization bias test section
 * @param deletionPoint      observation index for post-deletion analysis;
 *                           `-1` means use the MSER recommendation
 * @param block              optional DSL block; replaces the default content when provided
 */
fun WelchFileObserver.toReport(
    title:              String  = responseName,
    includePartialSums: Boolean = true,
    includeBatchMeans:  Boolean = false,
    includeBiasTest:    Boolean = false,
    deletionPoint:      Int     = -1,
    block: ReportBuilder.() -> Unit = {
        welchAnalysis(
            observer           = this@toReport,
            includePartialSums = includePartialSums,
            includeBatchMeans  = includeBatchMeans,
            includeBiasTest    = includeBiasTest,
            deletionPoint      = deletionPoint
        )
    }
): ReportNode.Document = report(title, block)
