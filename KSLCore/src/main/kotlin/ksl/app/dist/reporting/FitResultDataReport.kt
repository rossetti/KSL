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
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.result.DataSummaryDTO
import ksl.app.dist.result.DispersionAnalysisDTO
import ksl.app.dist.result.DistributionFitDTO
import ksl.app.dist.result.FamilyFrequencyResult
import ksl.app.dist.result.FitResultData
import ksl.app.dist.result.IntegerFrequencyDTO
import ksl.app.dist.result.ModaResultDTO
import ksl.app.dist.result.ShiftAnalysisDTO
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.dataStatisticalSummary
import ksl.utilities.io.report.extensions.dataVisualization
import ksl.utilities.io.report.extensions.discreteDataSummary
import ksl.utilities.io.report.extensions.discreteGoodnessOfFit
import ksl.utilities.io.report.extensions.discreteVisualization
import ksl.utilities.io.report.extensions.goodnessOfFit
import ksl.utilities.io.report.extensions.moda
import kotlin.math.roundToInt

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
    val s = dto.statistics
    section("Data Summary") {
        dataTable(
            headers = listOf("Property", "Value"),
            rows = listOf(
                listOf("n", s.count.toInt().toString()),
                listOf("Average", fmt(s.average)),
                listOf("Std Dev", fmt(s.standardDeviation)),
                listOf("Std Err", fmt(s.standardError)),
                listOf("CI (level ${fmt(s.confidenceLevel, 2)})",
                    "${fmt(s.lowerLimit)} to ${fmt(s.upperLimit)}"),
                listOf("Min", fmt(s.min)),
                listOf("Max", fmt(s.max)),
                listOf("Sum", fmt(s.sum)),
                listOf("Variance", fmt(s.variance)),
                listOf("Skewness", fmt(s.skewness)),
                listOf("Kurtosis", fmt(s.kurtosis)),
                listOf("Lag-1 Correlation", fmt(s.lag1Correlation)),
                listOf("Von Neumann Lag-1", fmt(s.vonNeumannLag1TestStatistic)),
                listOf("Zeros", dto.zeroCount.toString()),
                listOf("Negatives", dto.negativeCount.toString()),
                listOf("Positives", dto.positiveCount.toString())
            ),
            caption = "Data Statistical Summary"
        )
    }
}

/** Appends the left-shift analysis (continuous path; no-op when absent). */
fun ReportBuilder.shiftAnalysisSection(dto: ShiftAnalysisDTO?) {
    if (dto == null) return
    section("Shift Parameter Analysis") {
        dataTable(
            headers = listOf("Property", "Value"),
            rows = listOf(
                listOf("Estimated Left Shift", fmt(dto.leftShift)),
                listOf("Has Zeros", dto.hasZeroes.toString()),
                listOf("Has Negatives", dto.hasNegatives.toString()),
                listOf("Zero Tolerance", fmt(dto.zeroTolerance)),
                listOf("CI for Minimum (Lower)", fmt(dto.ciForMinimumLower)),
                listOf("CI for Minimum (Upper)", fmt(dto.ciForMinimumUpper))
            ),
            caption = "Left-Shift Estimation (${fmt(dto.ciForMinimumLevel, 2)} Bootstrap CI for Minimum)"
        )
    }
}

/** Appends the integer-frequency distribution (discrete path; no-op when absent). */
fun ReportBuilder.integerFrequencySection(dto: IntegerFrequencyDTO?) {
    if (dto == null) return
    section("Frequency Distribution") {
        dataTable(
            headers = listOf("Value", "Count", "Cum Count", "Proportion", "Cum Proportion"),
            rows = dto.cells.map {
                listOf(
                    it.value.toString(), fmt(it.count, 1), fmt(it.cumCount, 1),
                    fmt(it.proportion), fmt(it.cumProportion)
                )
            },
            caption = "Integer Frequency Distribution"
        )
    }
}

/** Appends the dataset-level dispersion analysis (discrete path; no-op when absent). */
fun ReportBuilder.dispersionSection(dto: DispersionAnalysisDTO?) {
    if (dto == null) return
    section("Dispersion Analysis") {
        dataTable(
            headers = listOf("Property", "Value"),
            rows = listOf(
                listOf("Index of Dispersion (Var/Mean)", fmt(dto.indexOfDispersion)),
                listOf("Poisson Variance Test T (DOF=${dto.degreesOfFreedom})",
                    fmt(dto.poissonVarianceTestStatistic)),
                listOf("p-value (upper, overdispersion)", fmt(dto.upperPValue)),
                listOf("p-value (lower, underdispersion)", fmt(dto.lowerPValue)),
                listOf("p-value (two-sided)", fmt(dto.twoSidedPValue))
            ),
            caption = "Dispersion Indicators"
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
                fmtOrDash(f.chiSquaredPValue ?: f.goodnessOfFit?.chiSquaredPValue),
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
        if (gof.poissonVarianceTestStatistic != null) testRows += listOf("Poisson Variance Test", fmt(gof.poissonVarianceTestStatistic!!), "—")
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

/**
 * Appends the family-frequency bootstrap result (continuous; no-op when absent) —
 * how often each family was the recommended fit across bootstrap resamples. This
 * is a separate, opt-in analysis, so the section appears only when present.
 */
fun ReportBuilder.bootstrapFamilyFrequencySection(dto: IntegerFrequencyDTO?) {
    if (dto == null || dto.cells.isEmpty()) return
    val total = dto.cells.sumOf { it.count }
    section("Bootstrap Family Frequency") {
        paragraph(
            "How often each family was the recommended fit across " +
                "${total.toInt()} bootstrap resamples of the data."
        )
        dataTable(
            headers = listOf("Family", "Count", "Proportion"),
            rows = dto.cells.sortedByDescending { it.count }.map {
                listOf(it.cellLabel.ifBlank { it.value.toString() }, fmt(it.count, 1), fmt(it.proportion))
            },
            caption = "Recommended-Family Frequency (Bootstrap)"
        )
    }
}

/** Builds a standalone document for a [FamilyFrequencyResult] (the Bootstrap-tab analysis). */
fun FamilyFrequencyResult.toDocument(title: String? = null): ReportNode.Document {
    val docTitle = title ?: "Family-Frequency Bootstrap — $datasetName"
    return report(docTitle) {
        bootstrapFamilyFrequencySection(frequency)
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
        shiftAnalysisSection(shiftAnalysis)
        integerFrequencySection(frequency)
        dispersionSection(dispersion)
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
        shiftAnalysisSection(shiftAnalysis)
        integerFrequencySection(frequency)
        dispersionSection(dispersion)
        fitRankingSection(fits)
        scoring?.let { modaSection(it) }
    }
}

// ── Canonical engine report (DTO + raw data, via the standard extensions) ───────

/**
 * Renders the canonical engine report from this result plus the client's raw
 * data, via the same report extensions a live `PDFModeler`/`PMFModeler` uses —
 * so a remote client holding only the DTO reproduces the standard report. The
 * stochastic bootstrap quantities are served from carried snapshots (the DTO
 * adapters never recompute), so this render is deterministic; it will not
 * byte-match a live render, which still recomputes bootstrap during its build.
 *
 * @param rawData         the client's original observations for this dataset
 * @param title           document title; defaults to the dataset name
 * @param catalog         maps a fit's family back to a distribution for reconstruction
 * @param confidenceLevel confidence level for the property sheet and GOF tests
 * @param allGoodnessOfFit when true, GOF covers every successful fit; otherwise the top one
 */
fun FitResultData.toCanonicalDocument(
    rawData: DoubleArray,
    title: String? = null,
    catalog: FittingCatalog = FittingCatalog,
    confidenceLevel: Double = 0.95,
    allGoodnessOfFit: Boolean = false
): ReportNode.Document {
    val docTitle = title ?: "Distribution Fitting — $datasetName"
    val successful = fits.filter { it.success }
    val detailFits = if (allGoodnessOfFit) successful else listOfNotNull(successful.firstOrNull())
    return when (kind) {
        DistributionKind.CONTINUOUS -> {
            val pdf = DtoPdfData(this, rawData)
            report(docTitle) {
                dataStatisticalSummary(pdf, confidenceLevel = confidenceLevel)
                dataVisualization(pdf)
                scoring?.let { moda(DtoModaData(it, fits), caption = "MODA Scoring Results") }
                for (fit in detailFits) {
                    goodnessOfFit(DtoPdfFitData(fit, rawData, catalog), confidenceLevel = confidenceLevel)
                }
            }
        }
        DistributionKind.DISCRETE -> {
            val intData = IntArray(rawData.size) { rawData[it].roundToInt() }
            val pmf = PMFModeler(intData)
            report(docTitle) {
                discreteDataSummary(pmf, confidenceLevel = confidenceLevel)
                discreteVisualization(pmf)
                for (fit in detailFits) {
                    discreteGoodnessOfFit(DtoPmfFitData(fit, intData, catalog), confidenceLevel = confidenceLevel)
                }
            }
        }
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
    // Reconstruct via the canonical fill API (same path RVData uses); the
    // scalar fit parameters are wrapped as 1-element arrays.
    params.fillFromDoubleArrayMap(fit.parameters.mapValues { doubleArrayOf(it.value) })
    val distribution = PDFModeler.createDistribution(params) ?: return null
    // The fit was performed on left-shifted data; align the client's data the
    // same way so the empirical series match the (unshifted) fitted distribution.
    val data = if (fit.shift != 0.0) DoubleArray(rawData.size) { rawData[it] - fit.shift } else rawData
    return FitDistPlot(data, distribution, distribution)
}
