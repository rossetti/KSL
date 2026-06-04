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

package ksl.app.dist.result

import kotlinx.serialization.Serializable

/**
 * Wire-safe MODA scoring result for a continuous fit, capturing the FULL
 * multi-objective decision analysis (metrics, raw scores, transformed
 * values, ranks, rank frequencies).
 *
 * The member DTOs mirror, field for field, the record classes the engine's
 * `AdditiveMODAModel` already emits (`MetricData`, `ScoreData`, `ValueData`,
 * `AlternativeRankFrequencyData`) — minus their `DbTableData` baggage (the
 * non-deterministic global-counter `id` and the repeated `modaName`). The
 * result extractor populates these by calling the model's own producers and
 * copying the primitive fields, so the mapping is lossless.
 *
 * Lists are flat/normalized and keyed by `alternative`, which equals the
 * `displayName` of the corresponding `DistributionFitDTO`. The overall
 * weighted value / first-rank count / average ranking (the engine's
 * `OverallValueData`) are intentionally NOT duplicated here — they are the
 * headline fields on each `DistributionFitDTO`.
 *
 * Populated in a later phase; null on the result until then.
 */
@Serializable
data class ModaResultDTO(
    val modelName: String,
    val rankingMethod: String,
    val metrics: List<MetricDTO>,
    val scores: List<ModaScoreDTO>,
    val values: List<ModaValueDTO>,
    val rankFrequencies: List<RankFrequencyDTO>
)

/** Mirrors `ksl.utilities.moda.MetricData` (definition + swing weight of one metric). */
@Serializable
data class MetricDTO(
    val metricName: String,
    val direction: String,            // "BiggerIsBetter" | "SmallerIsBetter"
    val weight: Double,
    val domainLowerLimit: Double,
    val domainUpperLimit: Double,
    val unitsOfMeasure: String? = null,
    val description: String? = null
)

/** Mirrors `ksl.utilities.moda.ScoreData` (one raw metric score for one alternative). */
@Serializable
data class ModaScoreDTO(
    val alternative: String,
    val scoreName: String,
    val scoreValue: Double
)

/** Mirrors `ksl.utilities.moda.ValueData` (transformed value + rank for one metric/alternative). */
@Serializable
data class ModaValueDTO(
    val alternative: String,
    val metricName: String,
    val metricValue: Double,
    val rank: Double
)

/**
 * Mirrors `ksl.utilities.moda.AlternativeRankFrequencyData` (how often an
 * alternative achieved a given rank). `rankValue` mirrors that class's
 * `value` field, renamed for clarity.
 */
@Serializable
data class RankFrequencyDTO(
    val alternative: String,
    val rankValue: Int,
    val count: Double,
    val proportion: Double,
    val cumProportion: Double
)
