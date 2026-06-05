/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.dist.reporting

import ksl.app.dist.result.BatchFitResultData
import ksl.app.dist.result.FitResultData
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report

private fun fmtNullable(v: Double?, digits: Int = 4): String =
    if (v == null || v.isNaN() || v.isInfinite()) "—" else "%.${digits}f".format(v)

private fun topParamsString(result: FitResultData): String {
    val top = result.fits.firstOrNull { it.success } ?: return "—"
    if (top.parameters.isEmpty()) return "—"
    return top.parameters.entries.joinToString(", ") { (k, v) -> "$k=${fmtNullable(v)}" }
}

/**
 * Appends the cross-dataset summary: one row per dataset giving the
 * recommended family, the recommended fit's parameters, and the
 * kind-appropriate quality statistic (MODA weighted value for continuous,
 * chi-squared p-value for discrete), plus a failures table when any entry
 * failed. This is the single new report the batch path contributes; all
 * per-dataset content reuses the DTO-driven sections.
 */
fun ReportBuilder.crossDatasetSummarySection(batch: BatchFitResultData) {
    section("Cross-Dataset Summary") {
        paragraph(
            "Datasets fit: ${batch.results.size}" +
                if (batch.failures.isNotEmpty()) "  |  Failed: ${batch.failures.size}" else ""
        )
        val rows = batch.results.map { r ->
            val top = r.fits.firstOrNull { it.success }
            listOf(
                r.datasetName,
                r.kind.name,
                r.recommendedFamilyId ?: "—",
                topParamsString(r),
                fmtNullable(top?.weightedValue),
                fmtNullable(top?.chiSquaredPValue),
                r.dataSummary.statistics.count.toInt().toString()
            )
        }
        dataTable(
            headers = listOf(
                "Dataset", "Kind", "Recommended Family", "Top Parameters",
                "Weighted Value", "Chi-Sq p-value", "n"
            ),
            rows = rows,
            caption = "Recommended Distribution by Dataset"
        )
        if (batch.failures.isNotEmpty()) {
            dataTable(
                headers = listOf("Dataset", "Error"),
                rows = batch.failures.map { listOf(it.name, it.message) },
                caption = "Failed Datasets"
            )
        }
    }
}

/**
 * Builds the batch report document: the cross-dataset summary, and — when
 * [includePerDataset] is true — a section per successful dataset reusing the
 * single-result DTO-driven sections (data summary, ranked fits, MODA, and the
 * recommended fit's goodness of fit). Per-dataset plots are not rendered here
 * (the batch result carries no raw data); a caller holding a dataset's data
 * can build that dataset's full document via `FitResultData.toDocument`.
 */
fun BatchFitResultData.toDocument(
    title: String? = null,
    includePerDataset: Boolean = false
): ReportNode.Document {
    val docTitle = title ?: "Batch Distribution Fitting"
    return report(docTitle) {
        crossDatasetSummarySection(this@toDocument)
        if (includePerDataset) {
            for (r in results) {
                section("Dataset: ${r.datasetName}") {
                    dataSummarySection(r.dataSummary)
                    fitRankingSection(r.fits)
                    r.scoring?.let { modaSection(it) }
                    r.fits.firstOrNull { it.success && it.goodnessOfFit != null }
                        ?.let { goodnessOfFitSection(it) }
                }
            }
        }
    }
}
