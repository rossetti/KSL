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

import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.moda.MODAAnalyzer

/**
 * DSL extension functions on [ReportBuilder] for rendering
 * [MODAAnalyzer] results.
 *
 * [MODAAnalyzer.analyze] must be called before this extension is invoked;
 * results are undefined otherwise.
 *
 * The extension produces a self-contained section covering:
 * 1. Metric definitions (name, direction, weight, domain, units, description)
 * 2. Average performance matrix (alternatives × responses)
 * 3. MODA scores, values, and overall rankings from the average MODA model
 * 4. MCB analysis for overall MODA value (when available)
 * 5. MCB analysis per response on raw performance values (when available)
 * 6. MCB analysis per response on MODA values (when available)
 */

/**
 * Appends a self-contained section reporting a [MODAAnalyzer] result.
 *
 * **`MODAAnalyzer.analyze()` must be called before invoking this function.**
 *
 * **Produces (inside a section titled [caption] or `"MODA Analysis"`):**
 *
 * 1. **Metric Definitions** — `DataTable` with columns
 *    `Metric | Direction | Weight | Domain Lower | Domain Upper | Units | Description`
 * 2. **Average Performance** — `DataTable` with one row per alternative and one
 *    column per response showing the average observed performance value
 * 3. **MODA Scores and Values (Average Model)** — three `DataTable`s:
 *    - `MODA Scores by Alternative and Metric` — raw metric scores before value-function transformation
 *    - `MODA Values and Ranks by Alternative and Metric` — transformed values and per-metric rank
 *    - `Overall MODA Values and Rankings` — additive composite value, overall rank, and top-alternative flag
 * 4. **MCB for Overall MODA Value** — full [multipleComparison] section on the composite
 *    MODA value across replications (omitted when [MODAAnalyzer.mcbForOverallValue] returns `null`)
 * 5. **MCB for Response Performance** — one [multipleComparison] sub-section per response
 *    on raw performance values (omitted when empty)
 * 6. **MCB for Response MODA Values** — one [multipleComparison] sub-section per response
 *    on MODA-transformed values (omitted when empty)
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
 * @param moda            the analyzer whose results will be reported; [MODAAnalyzer.analyze]
 *                        must have been called first
 * @param caption         optional section title; defaults to `"MODA Analysis"`
 * @param confidenceLevel probability of correct selection for all MCB and screening tables;
 *                        must be in (0, 1)
 */
fun ReportBuilder.modaAnalysis(
    moda: MODAAnalyzer,
    caption: String? = null,
    confidenceLevel: Double = 0.95
) {
    val myTitle = caption ?: "MODA Analysis"
    section(myTitle) {
        val myAvgModel = moda.averageMODA()

        // ── Overview paragraph ────────────────────────────────────────────────
        paragraph(
            "Alternatives: ${myAvgModel.alternatives.size}  |  " +
            "Responses: ${moda.responseNames.size}"
        )

        // ── 1. Metric definitions ─────────────────────────────────────────────
        section("Metric Definitions") {
            val myMetricHeaders = listOf(
                "Metric", "Direction", "Weight",
                "Domain Lower", "Domain Upper", "Units", "Description"
            )
            val myMetricRows = myAvgModel.metricData().map { md ->
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
            dataTable(myMetricHeaders, myMetricRows, caption = "Metric Definitions")
        }

        // ── 2. Average performance matrix ─────────────────────────────────────
        section("Average Performance") {
            val myPerfMap = moda.averagePerformance()   // Map<alternative, Map<response, Double>>
            val myResponses = moda.responseNames.toList().sorted()
            val myAlternatives = myPerfMap.keys.toList().sorted()
            val myPerfHeaders = listOf("Alternative") + myResponses
            val myPerfRows = myAlternatives.map { alt ->
                val myAltPerf = myPerfMap[alt] ?: emptyMap()
                listOf(alt) + myResponses.map { resp -> fmtD(myAltPerf[resp] ?: Double.NaN) }
            }
            dataTable(
                myPerfHeaders, myPerfRows,
                caption = "Average Performance by Alternative and Response"
            )
        }

        // ── 3. MODA scores, values, and rankings (average model) ──────────────
        section("MODA Scores and Values (Average Model)") {
            // 3a. Raw scores before value-function transformation
            val myScoreData = myAvgModel.alternativeScoreData()
            if (myScoreData.isNotEmpty()) {
                val myScoreHeaders = listOf("Alternative", "Metric", "Score")
                val myScoreRows = myScoreData.map { sd ->
                    listOf(sd.alternative, sd.scoreName, fmtD(sd.scoreValue))
                }
                dataTable(myScoreHeaders, myScoreRows, caption = "MODA Scores by Alternative and Metric")
            }

            // 3b. Transformed values and per-metric ranks
            val myValueData = myAvgModel.alternativeValueData()
            if (myValueData.isNotEmpty()) {
                val myValueHeaders = listOf("Alternative", "Metric", "Value", "Rank")
                val myValueRows = myValueData.map { vd ->
                    listOf(
                        vd.alternative,
                        vd.metricName,
                        fmtD(vd.metricValue),
                        vd.rank.toInt().toString()
                    )
                }
                dataTable(myValueHeaders, myValueRows, caption = "MODA Values and Ranks by Alternative and Metric")
            }

            // 3c. Overall composite value, overall rank, top-alternative flag
            val myOverallRanks = myAvgModel.alternativeRankedByMultiObjectiveValue()
            val myTopAlternatives = myAvgModel.topAlternativesByMultiObjectiveValue()
            val myOverallHeaders = listOf("Alternative", "Overall Value", "Rank", "Top Alternative")
            val myOverallRows = myAvgModel.sortedMultiObjectiveValuesByAlternative().map { (alt, value) ->
                listOf(
                    alt,
                    fmtD(value),
                    (myOverallRanks[alt] ?: 0).toString(),
                    myTopAlternatives.contains(alt).toString()
                )
            }
            dataTable(myOverallHeaders, myOverallRows, caption = "Overall MODA Values and Rankings")
        }

        // ── 4. MCB for overall MODA value (reuses McaReportExtensions) ────────
        val myMcbOverall = moda.mcbForOverallValue()
        if (myMcbOverall != null) {
            multipleComparison(myMcbOverall, altConfidenceLevel = confidenceLevel)
        }

        // ── 5. MCB per response — raw performance ─────────────────────────────
        val myMcbPerf = moda.mcbForResponsePerformance()
        if (myMcbPerf.isNotEmpty()) {
            section("MCB for Response Performance") {
                for ((_, myMca) in myMcbPerf) {
                    multipleComparison(myMca, altConfidenceLevel = confidenceLevel)
                }
            }
        }

        // ── 6. MCB per response — MODA values ────────────────────────────────
        val myMcbModa = moda.mcbForResponseMODAValues()
        if (myMcbModa.isNotEmpty()) {
            section("MCB for Response MODA Values") {
                for ((_, myMca) in myMcbModa) {
                    multipleComparison(myMca, altConfidenceLevel = confidenceLevel)
                }
            }
        }
    }
}

// ── Private formatting helper ─────────────────────────────────────────────────

/** Formats a [Double] to 4 decimal places; returns `"—"` for NaN or infinite values. */
private fun fmtD(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "—"
    else -> "%.4f".format(value)
}
