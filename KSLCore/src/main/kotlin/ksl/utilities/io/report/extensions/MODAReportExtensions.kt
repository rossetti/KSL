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
import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.moda.MetricIfc
import ksl.utilities.moda.MODAAnalyzer

/**
 * DSL extension functions on [ReportBuilder] for rendering [AdditiveMODAModel] and
 * [MODAAnalyzer] results.
 *
 * **[AdditiveMODAModel]** — single deterministic analysis (e.g., distribution fitting,
 * hand-crafted score tables). Use [moda] or [AdditiveMODAModel.toReport].
 *
 * **[MODAAnalyzer]** — multi-replication simulation analysis. Wraps the average MODA
 * model results with statistical comparison (MCB) and rank frequency distributions.
 * Use [modaAnalysis] or [MODAAnalyzer.toReport].
 */

// ── AdditiveMODAModel DSL extension ──────────────────────────────────────────

/**
 * Appends a self-contained section reporting the results of an [AdditiveMODAModel].
 *
 * **Produces (inside a section titled [caption] or [model.name][AdditiveMODAModel.name]):**
 *
 * 1. **Metric Definitions** — `DataTable` with columns
 *    `Metric | Direction | Weight | Domain Lower | Domain Upper | Units | Description`
 * 2. **Scores and Values** — two `DataTable`s:
 *    - *Raw Scores* — metric values before value-function transformation
 *    - *Transformed Values (0–1) and Overall Weighted Value* — value-function outputs
 *      plus the additive composite overall value per alternative
 * 3. **Rankings** — `DataTable` with per-metric rank, 1st-rank count, average rank,
 *    overall rank, and top-alternative flag; rows sorted by overall value (best first)
 *
 * Usage:
 * ```kotlin
 * val doc = report("Distribution Fitting Evaluation") {
 *     moda(pdfModeler.evaluateScoringResults(scoringResults))
 * }
 * doc.showInBrowser()
 * ```
 *
 * @param model   the additive MODA model whose results will be rendered
 * @param caption optional section title; defaults to [model.name][AdditiveMODAModel.name]
 *                or `"MODA Results"` when the name is blank
 */
fun ReportBuilder.moda(
    model: AdditiveMODAModel,
    caption: String? = null
) {
    val myTitle = caption ?: model.name.ifBlank { "MODA Results" }
    val myMetrics = model.metrics
    val myAlts = model.alternatives

    section(myTitle) {
        paragraph(
            "Alternatives: ${myAlts.size}  |  Metrics: ${myMetrics.size}"
        )

        // ── 1. Metric definitions ─────────────────────────────────────────────
        section("Metric Definitions") {
            val myHeaders = listOf(
                "Metric", "Direction", "Weight",
                "Domain Lower", "Domain Upper", "Units", "Description"
            )
            val myRows = model.metricData().map { md ->
                listOf(
                    md.metricName,
                    md.direction,
                    fmtD(md.weight),
                    fmtD(md.domainLowerLimit),
                    fmtD(md.domainUpperLimit),
                    md.unitsOfMeasure ?: "—",
                    md.description ?: "—"
                )
            }
            dataTable(myHeaders, myRows, caption = "Metric Definitions and Weights")
        }

        // ── 2. Raw scores and transformed values ──────────────────────────────
        section("Scores and Values") {
            val myScoresByMetric = model.scoresByMetric()   // Map<MetricIfc, List<Double>>
            val myValuesByMetric = model.valuesByMetric()   // Map<MetricIfc, List<Double>>
            val myMetricNames = myMetrics.map { it.name }

            // Raw scores — one column per metric
            val myScoreHeaders = listOf("Alternative") + myMetricNames
            val myScoreRows = myAlts.mapIndexed { idx, alt ->
                listOf(alt) + myMetrics.map { m ->
                    fmtD(myScoresByMetric[m]?.getOrNull(idx) ?: Double.NaN)
                }
            }
            dataTable(myScoreHeaders, myScoreRows,
                caption = "Raw Scores by Alternative and Metric")

            // Transformed values (0–1) + overall weighted value — sorted best-first
            val myValueHeaders = listOf("Alternative") + myMetricNames + listOf("Overall Value")
            val myValueRows = model.sortedMultiObjectiveValuesByAlternative().map { (alt, overallValue) ->
                val idx = myAlts.indexOf(alt)
                listOf(alt) +
                myMetrics.map { m -> fmtD(myValuesByMetric[m]?.getOrNull(idx) ?: Double.NaN) } +
                listOf(fmtD(overallValue))
            }
            dataTable(myValueHeaders, myValueRows,
                caption = "Transformed Values (0–1) and Overall Weighted Value (sorted by overall value)")
        }

        // ── 3. Rankings ───────────────────────────────────────────────────────
        section("Rankings") {
            val myRanksByMetric   = model.ranksByMetric()   // Map<MetricIfc, List<Double>>
            val mySortedAvgRanks  = model.alternativeAverageRanking(sortByAvgRanking = true)
            val myFirstRankCounts = model.alternativeFirstRankCounts().toMap()

            val myRankHeaders = listOf("Alternative") +
                myMetrics.map { it.name } +
                listOf("1st Rank Count", "Avg Rank")

            // Rows sorted ascending by average rank (lowest avg rank = most consistently top-ranked)
            val myRankRows = mySortedAvgRanks.map { (alt, avgRank) ->
                val idx = myAlts.indexOf(alt)
                listOf(alt) +
                myMetrics.map { m ->
                    (myRanksByMetric[m]?.getOrNull(idx)?.toInt() ?: 0).toString()
                } +
                listOf(
                    (myFirstRankCounts[alt] ?: 0).toString(),
                    fmtD(avgRank)
                )
            }
            dataTable(myRankHeaders, myRankRows, caption = "Alternative Rankings (sorted by average rank)")
        }
    }
}

// ── MODAAnalyzer DSL extension ────────────────────────────────────────────────

/**
 * Appends a self-contained section reporting a [MODAAnalyzer] result.
 *
 * **[MODAAnalyzer.analyze] must be called before invoking this function.**
 *
 * **Produces (inside a section titled [caption] or `"MODA Analysis"`):**
 *
 * 1. **Average Performance** — `DataTable` of mean observed values per alternative and response
 * 2. **Average MODA Model** — full [moda] section for the model built from averaged data,
 *    including metric definitions, scores/values, and rankings
 * 3. **MCB for Overall MODA Value** *(when available)* — [multipleComparison] with
 *    `direction = MAX` (a higher MODA composite value is always better)
 * 4. **MCB for Response Performance** *(when available)* — one [multipleComparison] sub-section
 *    per response; direction follows the metric's [MetricIfc.Direction]
 * 5. **MCB for Response MODA Values** *(when available)* — one [multipleComparison] sub-section
 *    per response with `direction = MAX` (value-function outputs are always bigger-is-better)
 * 6. **Overall Rank Frequencies** *(when replications exist)* — one [integerFrequency] section
 *    per alternative showing how often each overall rank was achieved across replications
 *
 * Usage:
 * ```kotlin
 * moda.analyze()
 * val doc = report("System Comparison Study") {
 *     modaAnalysis(moda, confidenceLevel = 0.95)
 * }
 * doc.showInBrowser()
 * ```
 *
 * @param moda            the analyzer whose results will be reported;
 *                        [MODAAnalyzer.analyze] must have been called first
 * @param caption         optional section title; defaults to `"MODA Analysis"`
 * @param confidenceLevel confidence level for all MCB and screening tables; must be in (0, 1)
 */
fun ReportBuilder.modaAnalysis(
    moda: MODAAnalyzer,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "MODA Analysis"
    section(myTitle) {
        val myAvgModel = moda.averageMODA()

        // Build metric direction lookup from the average model's metrics.
        // (responseDefinitions is private on MODAAnalyzer; metrics carry the same information.)
        val myDirByName: Map<String, MetricIfc.Direction> =
            myAvgModel.metrics.associate { it.name to it.direction }

        paragraph(
            "Alternatives: ${myAvgModel.alternatives.size}  |  " +
            "Responses: ${moda.responseNames.size}  |  " +
            "Replications: ${moda.modaByReplication.size}"
        )

        // ── 1. Average performance across replications ────────────────────────
        section("Average Performance") {
            val myPerfMap  = moda.averagePerformance()            // alt → response → mean
            val myResponses = moda.responseNames.toList().sorted()
            val myAlts      = myPerfMap.keys.toList().sorted()
            val myHeaders   = listOf("Alternative") + myResponses
            val myRows      = myAlts.map { alt ->
                val myAltPerf = myPerfMap[alt] ?: emptyMap()
                listOf(alt) + myResponses.map { r -> fmtD(myAltPerf[r] ?: Double.NaN) }
            }
            dataTable(myHeaders, myRows,
                caption = "Average Performance by Alternative and Response")
        }

        // ── 2. Average MODA model results (reuses moda() extension) ──────────
        this.moda(myAvgModel, caption = "Average MODA Model")

        // ── 3. MCB for overall MODA value — always MAX ────────────────────────
        // MODA composite values are bigger-is-better by construction.
        val myMcbOverall = moda.mcbForOverallValue()
        if (myMcbOverall != null) {
            multipleComparison(
                myMcbOverall,
                direction            = MCBDirection.MAX,
                altConfidenceLevel   = confidenceLevel,
                diffConfidenceLevel  = confidenceLevel,
                probCorrectSelection = confidenceLevel
            )
        }

        // ── 4. MCB per response — raw performance (direction follows metric) ──
        val myMcbPerf = moda.mcbForResponsePerformance()
        if (myMcbPerf.isNotEmpty()) {
            section("MCB for Response Performance") {
                for ((responseName, myMca) in myMcbPerf) {
                    val myDir = when (myDirByName[responseName]) {
                        MetricIfc.Direction.BiggerIsBetter -> MCBDirection.MAX
                        else                               -> MCBDirection.MIN
                    }
                    multipleComparison(
                        myMca,
                        direction            = myDir,
                        altConfidenceLevel   = confidenceLevel,
                        diffConfidenceLevel  = confidenceLevel,
                        probCorrectSelection = confidenceLevel
                    )
                }
            }
        }

        // ── 5. MCB per response — MODA values (always MAX) ───────────────────
        // Value-function outputs are in [0, 1] where 1 is always best,
        // regardless of the original metric direction.
        val myMcbModa = moda.mcbForResponseMODAValues()
        if (myMcbModa.isNotEmpty()) {
            section("MCB for Response MODA Values") {
                for ((_, myMca) in myMcbModa) {
                    multipleComparison(
                        myMca,
                        direction            = MCBDirection.MAX,
                        altConfidenceLevel   = confidenceLevel,
                        diffConfidenceLevel  = confidenceLevel,
                        probCorrectSelection = confidenceLevel
                    )
                }
            }
        }

        // ── 6. Overall rank frequencies across replications ───────────────────
        val myRankFreqs = moda.overallRankFrequenciesByAlternative()
        if (myRankFreqs.isNotEmpty()) {
            section("Overall Rank Frequencies") {
                paragraph(
                    "Distribution of overall MODA rankings across " +
                    "${moda.modaByReplication.size} replications. " +
                    "Rank 1 means the alternative had the highest overall MODA value in that replication."
                )
                for ((altName, myFreq) in myRankFreqs) {
                    integerFrequency(myFreq, caption = altName)
                }
            }
        }
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] whose default content is the full MODA results
 * section for this model (metric definitions, scores/values, rankings).
 *
 * Zero-code path:
 * ```kotlin
 * val model = pdfModeler.evaluateScoringResults(scoringResults)
 * model.toReport().showInBrowser()
 * model.toReport().writeMarkdown()
 * model.toReport().printText()
 * ```
 *
 * Supply a [block] to customise or extend the content:
 * ```kotlin
 * val model = pdfModeler.evaluateScoringResults(scoringResults)
 * model.toReport("Distribution Fitting Study") {
 *     moda(model)
 *     paragraph("Recommended: ${model.sortedMultiObjectiveValuesByAlternative().first().first}")
 * }
 * ```
 *
 * @param title  document title; defaults to [AdditiveMODAModel.name] or `"MODA Results"`
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun AdditiveMODAModel.toReport(
    title: String = name.ifBlank { "MODA Results" },
    block: ReportBuilder.() -> Unit = { moda(this@toReport) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] whose default content is the full MODA analysis
 * (average performance, average model results, MCB comparisons, rank frequencies).
 *
 * **[MODAAnalyzer.analyze] must be called before invoking this function.**
 *
 * Zero-code path:
 * ```kotlin
 * modaAnalyzer.analyze()
 * modaAnalyzer.toReport().showInBrowser()
 * modaAnalyzer.toReport().writeMarkdown()
 * modaAnalyzer.toReport().printText()
 * ```
 *
 * Supply a [block] to customise or extend the content:
 * ```kotlin
 * modaAnalyzer.analyze()
 * modaAnalyzer.toReport("System Comparison") {
 *     modaAnalysis(modaAnalyzer, confidenceLevel = 0.90)
 *     paragraph("Conclusion: prefer ${modaAnalyzer.averageMODA().sortedMultiObjectiveValuesByAlternative().first().first}.")
 * }
 * ```
 *
 * @param title           document title; defaults to `"MODA Analysis"`
 * @param confidenceLevel confidence level for all MCB and screening tables; must be in (0, 1)
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun MODAAnalyzer.toReport(
    title: String = "MODA Analysis",
    confidenceLevel: Double = 0.95,
    block: ReportBuilder.() -> Unit = {
        modaAnalysis(this@toReport, confidenceLevel = confidenceLevel)
    }
): ReportNode.Document = report(title, block)

// ── Private formatting helper ─────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtD(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.4f".format(value)
}
