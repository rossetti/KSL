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

package ksl.utilities.moda

/**
 * The plain, name-keyed data an [AdditiveMODAModel] report section needs.
 *
 * [AdditiveMODAModel] produces one of these via [modaReportData]; a
 * reconstructed/serialized result can implement it directly, so the same `moda`
 * report extension renders from either source. Unlike the live model's
 * accessors, the maps here are keyed by metric *name* (not `MetricIfc`) and the
 * ranking projections are already materialized using the model's configured
 * ranking method — so this holder carries no behavior, only data.
 *
 * All per-metric maps are keyed by the entries of [metricNames]; each value list
 * is aligned with [alternatives] by index.
 */
interface MODAReportData {
    /** The model name, used as the default report section title. */
    val name: String

    /** The alternatives, in model order. Indexes the per-metric value lists. */
    val alternatives: List<String>

    /** The metric names, in model order. Keys the per-metric maps. */
    val metricNames: List<String>

    /** The metric definitions (name, direction, weight, domain, units, description). */
    val metricData: List<MetricData>

    /** Raw scores per metric name; each list aligned with [alternatives] by index. */
    val scoresByMetric: Map<String, List<Double>>

    /** Transformed value-function outputs per metric name; aligned with [alternatives]. */
    val valuesByMetric: Map<String, List<Double>>

    /** Per-metric ranks per metric name; aligned with [alternatives]. */
    val ranksByMetric: Map<String, List<Double>>

    /** (alternative, overall weighted value), best-first. */
    val sortedOverallValues: List<Pair<String, Double>>

    /** (alternative, average rank), lowest average rank first. */
    val sortedAvgRanks: List<Pair<String, Double>>

    /** First-rank count per alternative. */
    val firstRankCounts: Map<String, Int>
}

private data class MODAReportDataImpl(
    override val name: String,
    override val alternatives: List<String>,
    override val metricNames: List<String>,
    override val metricData: List<MetricData>,
    override val scoresByMetric: Map<String, List<Double>>,
    override val valuesByMetric: Map<String, List<Double>>,
    override val ranksByMetric: Map<String, List<Double>>,
    override val sortedOverallValues: List<Pair<String, Double>>,
    override val sortedAvgRanks: List<Pair<String, Double>>,
    override val firstRankCounts: Map<String, Int>
) : MODAReportData

/**
 * Projects this model's results into a plain [MODAReportData] holder. The
 * ranking projections use this model's `defaultRankingMethod`, matching the
 * behavior of the no-argument ranking accessors, so the rendered report is
 * unchanged from rendering the live model directly.
 */
fun AdditiveMODAModel.modaReportData(): MODAReportData {
    val metricsList = metrics
    val names = metricsList.map { it.name }
    val scores = scoresByMetric()
    val values = valuesByMetric()
    val ranks = ranksByMetric()
    fun byName(src: Map<MetricIfc, List<Double>>): Map<String, List<Double>> =
        metricsList.associate { it.name to (src[it] ?: emptyList()) }
    return MODAReportDataImpl(
        name = name,
        alternatives = alternatives,
        metricNames = names,
        metricData = metricData(),
        scoresByMetric = byName(scores),
        valuesByMetric = byName(values),
        ranksByMetric = byName(ranks),
        sortedOverallValues = sortedMultiObjectiveValuesByAlternative(),
        sortedAvgRanks = alternativeAverageRanking(sortByAvgRanking = true),
        firstRankCounts = alternativeFirstRankCounts().toMap()
    )
}
