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

import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.result.DataSummaryDTO
import ksl.app.dist.result.DistributionFitDTO
import ksl.app.dist.result.FitResultData
import ksl.app.dist.result.ModaResultDTO
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report

/**
 * Builds a human-facing `ReportNode.Document` from a serializable
 * `FitResultData` — and, for plots, the client's own raw data — without any
 * live engine objects. This is the presentation-site counterpart to the
 * orchestration layer's extraction step: a remote client holding only the
 * DTO can reconstruct the same report a co-located caller would, reusing the
 * report framework's renderers, leaf builders, and `FitDistPlot`.
 *
 * Tables are built from the DTO fields via the reused leaf builders. Plots
 * are reconstructed from each fit's family + parameters + shift (rebuilding
 * the fitted distribution and aligning the supplied data), so they appear
 * only when the caller provides `rawData`. A client without distribution
 * math (or without its data) renders tables only — nothing breaks.
 */

private fun fmt(v: Double, digits: Int = 4): String =
    if (v.isNaN() || v.isInfinite()) "—" else "%.${digits}f".format(v)

private fun fmtOrDash(v: Double?, digits: Int = 4): String = if (v == null) "—" else fmt(v, digits)

private fun paramString(parameters: Map<String, Double>): String =
    parameters.entries.joinToString(", ") { (k, v) -> "$k=${fmt(v)}" }

// ── Section builders (DTO-driven) ──────────────────────────────────────────────

/** Appends the data statistical summary as a Property/Value table. */
fun ReportBuilder.dataSummarySection(dto: DataSummaryDTO) {
    section("Data Summary") {
        dataTable(
            headers = listOf("Property", "Value"),
            rows = listOf(
                listOf("n", dto.n.toString()),
                listOf("Min", fmt(dto.min)),
                listOf("Max", fmt(dto.max)),
                listOf("Average", fmt(dto.average)),
                listOf("Variance", fmt(dto.variance)),
                listOf("Std Dev", fmt(dto.standardDeviation)),
                listOf("Skewness", fmt(dto.skewness)),
                listOf("Kurtosis", fmt(dto.kurtosis)),
                listOf("Zeros", dto.zeroCount.toString()),
                listOf("Negatives", dto.negativeCount.toString()),
                listOf("Positives", dto.positiveCount.toString()),
                listOf("Shift", fmt(dto.shift))
            ),
            caption = "Data Statistical Summary"
        )
    }
}

/** Appends the ranked list of fitted distributions as a single table. */
fun ReportBuilder.fitRankingSection(fits: List<DistributionFitDTO>) {
    section("Fitted Distributions") {
        val rows = fits.map { f ->
            listOf(
                f.rank.toString(),
                f.familyId,
                f.estimatorId,
                paramString(f.parameters),
                if (f.success) "yes" else "no",
                fmtOrDash(f.weightedValue),
                fmtOrDash(f.averageRanking),
                fmtOrDash(f.chiSquaredPValue),
                f.message ?: ""
            )
        }
        dataTable(
            headers = listOf(
                "Rank", "Family", "Estimator", "Parameters", "Success",
                "Weighted Value", "Avg Rank", "Chi-Sq p-value", "Message"
            ),
            rows = rows,
            caption = "Distributions Ranked by Fit Quality"
        )
    }
}

/** Appends the full MODA scoring detail as a set of flat tables. */
fun ReportBuilder.modaSection(dto: ModaResultDTO) {
    section("MODA Scoring (${dto.modelName})") {
        paragraph("Ranking method: ${dto.rankingMethod}  |  Metrics: ${dto.metrics.size}")

        section("Metric Definitions") {
            dataTable(
                headers = listOf("Metric", "Direction", "Weight", "Domain Lower", "Domain Upper", "Units", "Description"),
                rows = dto.metrics.map {
                    listOf(
                        it.metricName, it.direction, fmt(it.weight),
                        fmt(it.domainLowerLimit), fmt(it.domainUpperLimit),
                        it.unitsOfMeasure ?: "—", it.description ?: "—"
                    )
                },
                caption = "Metric Definitions and Weights"
            )
        }
        section("Raw Scores") {
            dataTable(
                headers = listOf("Alternative", "Metric", "Score"),
                rows = dto.scores.map { listOf(it.alternative, it.scoreName, fmt(it.scoreValue)) },
                caption = "Raw Metric Scores by Alternative"
            )
        }
        section("Transformed Values and Ranks") {
            dataTable(
                headers = listOf("Alternative", "Metric", "Value", "Rank"),
                rows = dto.values.map { listOf(it.alternative, it.metricName, fmt(it.metricValue), fmt(it.rank, 1)) },
                caption = "Transformed Values (0-1) and Metric Ranks"
            )
        }
        if (dto.rankFrequencies.isNotEmpty()) {
            section("Rank Frequencies") {
                dataTable(
                    headers = listOf("Alternative", "Rank", "Count", "Proportion", "Cum Proportion"),
                    rows = dto.rankFrequencies.map {
                        listOf(it.alternative, it.rankValue.toString(), fmt(it.count, 1), fmt(it.proportion), fmt(it.cumProportion))
                    },
                    caption = "Metric Rank Frequencies by Alternative"
                )
            }
        }
    }
}

/** Appends the goodness-of-fit detail for one fit (no-op when absent). */
fun ReportBuilder.goodnessOfFitSection(fit: DistributionFitDTO) {
    val gof = fit.goodnessOfFit ?: return
    section("Goodness of Fit — ${fit.displayName}") {
        val testRows = mutableListOf(
            listOf("Chi-Squared (DOF=${gof.chiSquaredDOF})", fmt(gof.chiSquaredStatistic), fmt(gof.chiSquaredPValue))
        )
        if (gof.ksStatistic != null) testRows += listOf("Kolmogorov-Smirnov", fmt(gof.ksStatistic!!), fmtOrDash(gof.ksPValue))
        if (gof.andersonDarlingStatistic != null) testRows += listOf("Anderson-Darling", fmt(gof.andersonDarlingStatistic!!), fmtOrDash(gof.andersonDarlingPValue))
        if (gof.cramerVonMisesStatistic != null) testRows += listOf("Cramer-von Mises", fmt(gof.cramerVonMisesStatistic!!), fmtOrDash(gof.cramerVonMisesPValue))
        if (gof.indexOfDispersion != null) testRows += listOf("Index of Dispersion", fmt(gof.indexOfDispersion!!), "—")
        dataTable(listOf("Test", "Statistic", "p-value"), testRows, caption = "Goodness of Fit Tests")

        val binRows = gof.binProbabilities.indices.map { i ->
            listOf(
                (i + 1).toString(),
                fmt(gof.binProbabilities[i]),
                fmt(gof.expectedCounts.getOrElse(i) { Double.NaN }),
                fmt(gof.observedCounts.getOrElse(i) { Double.NaN }, 1)
            )
        }
        dataTable(listOf("Bin", "P(bin)", "Expected", "Observed"), binRows, caption = "Chi-Squared Bin Table")
    }
}

/** Appends the bootstrap parameter summaries for one fit (no-op when absent). */
fun ReportBuilder.bootstrapSection(fit: DistributionFitDTO) {
    val boots = fit.bootstrap ?: return
    if (boots.isEmpty()) return
    section("Bootstrap Parameter Estimates — ${fit.displayName}") {
        dataTable(
            headers = listOf(
                "Parameter", "Original", "Bootstrap Avg", "Bias", "MSE", "Std Err",
                "Normal CI", "Basic CI", "Percentile CI"
            ),
            rows = boots.map { b ->
                listOf(
                    b.parameterName,
                    fmt(b.originalEstimate), fmt(b.bootstrapAverage), fmt(b.bias),
                    fmt(b.mse), fmt(b.stdError),
                    "[${fmt(b.normalCILower)}, ${fmt(b.normalCIUpper)}]",
                    "[${fmt(b.basicCILower)}, ${fmt(b.basicCIUpper)}]",
                    "[${fmt(b.percentileCILower)}, ${fmt(b.percentileCIUpper)}]"
                )
            },
            caption = "Bootstrap Summary (level ${fmt(boots.first().ciLevel, 2)})"
        )
    }
}

/**
 * Appends the four fit-quality plots for one fit, reconstructed from the
 * fitted distribution and the supplied raw data. No-op when the fit failed,
 * the family cannot be reconstructed, or the distribution build returns null.
 */
fun ReportBuilder.fitPlotsSection(
    fit: DistributionFitDTO,
    rawData: DoubleArray,
    catalog: FittingCatalog
) {
    val fitPlot = reconstructFitPlot(fit, rawData, catalog) ?: return
    section("Distribution Fit Plots — ${fit.displayName}") {
        plot(fitPlot.densityPlot, caption = "Density — Empirical vs Theoretical PDF")
        plot(fitPlot.qqPlot, caption = "Q-Q Plot")
        plot(fitPlot.ecdfPlot, caption = "ECDF vs Theoretical CDF")
        plot(fitPlot.ppPlot, caption = "P-P Plot")
    }
}

// ── Top-level assembly ─────────────────────────────────────────────────────────

/**
 * Builds the full report document from this result. Tables are always
 * included; the goodness-of-fit detail and bootstrap summaries are added for
 * the recommended fit (or every successful fit when [allGoodnessOfFit] is
 * true); the four fit plots are added only when [rawData] is supplied.
 *
 * @param rawData the client's original observations; required to render plots
 * @param title document title; defaults to the dataset name
 * @param catalog used to map a fit's family back to a distribution for plotting
 * @param allGoodnessOfFit when true, GoF/plots cover every successful fit
 */
fun FitResultData.toDocument(
    rawData: DoubleArray? = null,
    title: String? = null,
    catalog: FittingCatalog = FittingCatalog,
    allGoodnessOfFit: Boolean = false
): ReportNode.Document {
    val docTitle = title ?: "Distribution Fitting — $datasetName"
    val detailFits: List<DistributionFitDTO> = if (allGoodnessOfFit) {
        fits.filter { it.success && it.goodnessOfFit != null }
    } else {
        listOfNotNull(fits.firstOrNull { it.success && it.goodnessOfFit != null })
    }
    return report(docTitle) {
        dataSummarySection(dataSummary)
        fitRankingSection(fits)
        scoring?.let { modaSection(it) }
        for (fit in detailFits) {
            goodnessOfFitSection(fit)
            bootstrapSection(fit)
            if (rawData != null) fitPlotsSection(fit, rawData, catalog)
        }
    }
}

/**
 * Builds a lightweight, plot-free summary document: data summary, the ranked
 * fits table, and the MODA scoring tables. Renders deterministically from the
 * DTO with no plot files. Use for an immediate in-app or console preview.
 */
fun FitResultData.toSummaryDocument(title: String? = null): ReportNode.Document {
    val docTitle = title ?: "Distribution Fitting Summary — $datasetName"
    return report(docTitle) {
        dataSummarySection(dataSummary)
        fitRankingSection(fits)
        scoring?.let { modaSection(it) }
    }
}

// ── Plot reconstruction (DTO + raw data -> FitDistPlot; no engine objects) ──────

private fun reconstructFitPlot(
    fit: DistributionFitDTO,
    rawData: DoubleArray,
    catalog: FittingCatalog
): FitDistPlot? {
    if (!fit.success) return null
    val rvType = catalog.familyOrNull(fit.familyId)?.rvType ?: return null
    val params = rvType.rvParameters
    fit.parameters.forEach { (name, value) -> runCatching { params.changeParameter(name, value) } }
    val distribution = PDFModeler.createDistribution(params) ?: return null
    // The fit was performed on left-shifted data; align the client's data the
    // same way so the empirical series match the (unshifted) fitted distribution.
    val data = if (fit.shift != 0.0) DoubleArray(rawData.size) { rawData[it] - fit.shift } else rawData
    return FitDistPlot(data, distribution, distribution)
}
