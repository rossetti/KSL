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

package ksl.app.moda

import ksl.utilities.moda.AggregationMethod
import ksl.utilities.moda.MetricRecord
import ksl.utilities.moda.ModaSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 *  Wire form of one metric within a MODA result. Mirrors [MetricRecord].
 */
@Serializable
data class ModaMetricRecordDTO(
    val name: String,
    val direction: String,
    val weight: Double,
    val declaredLowerLimit: Double,
    val declaredUpperLimit: Double,
    val effectiveLowerLimit: Double,
    val effectiveUpperLimit: Double,
    val domainWasRescaled: Boolean,
    val hadTiedScores: Boolean,
    val valueFunctionId: String,
    val unitsOfMeasure: String? = null,
    val description: String? = null
)

/**
 *  Wire form of a completed MODA study. Mirrors [ModaSnapshot].
 *
 *  This is versioned on its own rather than sharing a version with anything else, because a MODA
 *  result and a distribution-fitting result change for unrelated reasons and tying them together
 *  would force a version bump on one whenever the other moved.
 *
 *  It is a separate type from the MODA result already carried by a distribution fit
 *  (`ksl.app.dist.result.ModaResultDTO`), which stays exactly where it is. Moving that one would
 *  keep its field names on the wire while breaking every Kotlin import of it and its name on the
 *  JVM, which is a real cost for no gain to callers.
 *
 *  Ordering is preserved on the way through: [metrics] and [alternatives] stay in the order the
 *  study declared them, and the maps are built in that same order, so encoding the same result
 *  twice produces the same bytes.
 */
@Serializable
data class ModaSnapshotDTO(
    val schemaVersion: Int = SCHEMA_VERSION,
    val name: String,
    val metrics: List<ModaMetricRecordDTO>,
    val alternatives: List<String>,
    val scores: Map<String, Map<String, Double>>,
    val values: Map<String, Map<String, Double>>,
    val overallValues: Map<String, Double>,
    val firstRankCounts: Map<String, Int>,
    val averageRankings: Map<String, Double>,
    val rankingMethod: String,
    val aggregationMethod: String,
    val primaryRecommendation: String,
    val warnings: List<String>,
    /**
     *  Added after the first version. Optional with a default, so a reader of this version makes
     *  sense of a result written by the previous one and vice versa, which is why adding it did not
     *  need the version raised.
     */
    val replicationAggregation: String? = null
) {
    companion object {

        /**
         *  The version of this wire form. Raise it when a change would stop an older reader from
         *  making sense of the result, so a reader can refuse rather than misread it.
         */
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 *  How to encode and decode these types.
 *
 *  Defaults are written out rather than left implied, so the schema version is always present and
 *  a reader never has to guess which version it was given. Unknown keys are ignored so a reader
 *  built against an earlier version of the same major schema still works.
 */
val ModaJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
}

/** Converts a metric record to its wire form. */
fun MetricRecord.toDTO(): ModaMetricRecordDTO = ModaMetricRecordDTO(
    name = name,
    direction = direction,
    weight = weight,
    declaredLowerLimit = declaredLowerLimit,
    declaredUpperLimit = declaredUpperLimit,
    effectiveLowerLimit = effectiveLowerLimit,
    effectiveUpperLimit = effectiveUpperLimit,
    domainWasRescaled = domainWasRescaled,
    hadTiedScores = hadTiedScores,
    valueFunctionId = valueFunctionId,
    unitsOfMeasure = unitsOfMeasure,
    description = description
)

/** Converts a metric record back from its wire form. */
fun ModaMetricRecordDTO.toRecord(): MetricRecord = MetricRecord(
    name = name,
    direction = direction,
    weight = weight,
    declaredLowerLimit = declaredLowerLimit,
    declaredUpperLimit = declaredUpperLimit,
    effectiveLowerLimit = effectiveLowerLimit,
    effectiveUpperLimit = effectiveUpperLimit,
    domainWasRescaled = domainWasRescaled,
    hadTiedScores = hadTiedScores,
    valueFunctionId = valueFunctionId,
    unitsOfMeasure = unitsOfMeasure,
    description = description
)

/** Converts a completed study to its wire form. */
fun ModaSnapshot.toDTO(): ModaSnapshotDTO = ModaSnapshotDTO(
    name = name,
    metrics = metrics.map { it.toDTO() },
    alternatives = alternatives,
    scores = scores,
    values = values,
    overallValues = overallValues,
    firstRankCounts = firstRankCounts,
    averageRankings = averageRankings,
    rankingMethod = rankingMethod,
    aggregationMethod = aggregationMethod.name,
    primaryRecommendation = primaryRecommendation,
    warnings = warnings,
    replicationAggregation = replicationAggregation
)

/**
 *  Converts a completed study back from its wire form.
 *
 *  @throws IllegalArgumentException if the aggregation method is not one this version knows, which
 *  means the result was written by a later version and cannot be read faithfully.
 */
fun ModaSnapshotDTO.toSnapshot(): ModaSnapshot {
    val aggregation = AggregationMethod.entries.firstOrNull { it.name == aggregationMethod }
    requireNotNull(aggregation) {
        "Unknown aggregation method '$aggregationMethod'. Known methods are " +
                AggregationMethod.entries.joinToString(", ") { it.name } +
                ". This result was probably written by a later version."
    }
    return ModaSnapshot(
        name = name,
        metrics = metrics.map { it.toRecord() },
        alternatives = alternatives,
        scores = scores,
        values = values,
        overallValues = overallValues,
        firstRankCounts = firstRankCounts,
        averageRankings = averageRankings,
        rankingMethod = rankingMethod,
        aggregationMethod = aggregation,
        primaryRecommendation = primaryRecommendation,
        warnings = warnings,
        replicationAggregation = replicationAggregation
    )
}
