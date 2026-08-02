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

import ksl.utilities.statistic.Statistic

/**
 *  How the alternatives are compared to arrive at a single recommendation.
 */
enum class AggregationMethod {

    /**
     *  Compares the weighted sum of each alternative's values across the metrics. This is the
     *  additive model's own answer and uses every metric's value in proportion to its weight.
     */
    WEIGHTED_VALUE,

    /**
     *  Compares how many metrics rank each alternative first.
     *
     *  This counts winners rather than combining values, so an alternative ranked first by a bare
     *  margin on three metrics beats one ranked a close second on all of them. It answers a
     *  different question from [WEIGHTED_VALUE] and the two can disagree.
     */
    FIRST_RANK_COUNT
}

/**
 *  What was known about one metric at the moment a study was evaluated.
 *
 *  Both domains are carried because they answer different questions. The declared domain is what
 *  the study said the metric could range over. The effective domain is what the values were
 *  actually computed against, which differs whenever the domain was fitted to the realized scores.
 *  Reporting only the declared one leaves the values unexplained; reporting only the effective one
 *  hides that any fitting happened.
 */
data class MetricRecord(
    val name: String,
    val direction: String,
    val weight: Double,
    val declaredLowerLimit: Double,
    val declaredUpperLimit: Double,
    val effectiveLowerLimit: Double,
    val effectiveUpperLimit: Double,
    /** Indicates the domain was fitted to the realized scores rather than used as declared. */
    val domainWasRescaled: Boolean,
    /**
     *  The smallest and largest score any alternative actually achieved on this metric.
     *
     *  Recorded because it is the third thing needed to read the other two. The declared limits say
     *  what was thought possible, the effective limits say what the values were computed over, and
     *  these say where the alternatives really fell. Without them a reader cannot tell a domain
     *  fitted tightly around a cluster of alternatives from one left wide because they were spread,
     *  and those mean quite different things about the decision.
     *
     *  Null only where the metric had no scores, which cannot happen for a metric of a study that
     *  ran.
     */
    val realizedLowestScore: Double? = null,
    val realizedHighestScore: Double? = null,
    /** Indicates every alternative scored the same here, so this metric separated nothing. */
    val hadTiedScores: Boolean,
    val valueFunctionId: String,
    val unitsOfMeasure: String?,
    val description: String?
)

/**
 *  The result of evaluating a MODA study, complete and standing on its own.
 *
 *  A model is live and mutable: defining more alternatives refits domains and changes every value
 *  it reports. A snapshot is taken once and does not change afterwards, so results can be held,
 *  compared, serialized, or read long after the model that produced them has been discarded.
 *
 *  Everything here is keyed by name rather than by metric, so nothing in a snapshot depends on
 *  object identity and it can be written out and read back without losing meaning. [metrics] and
 *  [alternatives] are in the order they were declared, so a report built from a snapshot presents
 *  them the way the study set them out rather than in whatever order a map happened to yield.
 */
data class ModaSnapshot(
    val name: String,
    /** The metrics, in the order the study declared them. */
    val metrics: List<MetricRecord>,
    /** The alternatives, in the order the study declared them. */
    val alternatives: List<String>,
    /** Raw scores, by alternative name and then metric name. */
    val scores: Map<String, Map<String, Double>>,
    /** Values after the value functions were applied, by alternative name and then metric name. */
    val values: Map<String, Map<String, Double>>,
    /** The weighted overall value of each alternative. */
    val overallValues: Map<String, Double>,
    /** How many metrics ranked each alternative first. */
    val firstRankCounts: Map<String, Int>,
    /** The average of each alternative's ranks across the metrics. */
    val averageRankings: Map<String, Double>,
    /** How ties were handled when ranking. */
    val rankingMethod: String,
    /** How [primaryRecommendation] was arrived at. */
    val aggregationMethod: AggregationMethod,
    /** The alternative this study points to, under [aggregationMethod]. */
    val primaryRecommendation: String,
    /** Anything worth reporting from evaluating the study. See [ModaWarning]. */
    val warnings: List<String>,
    /**
     *  How repeated observations of each score were reduced to the single score compared here, or
     *  null when the scores were not observed repeatedly.
     *
     *  Recorded because two studies over the same runs can reach different conclusions purely
     *  through this choice, so a result that does not say which was used cannot be interpreted.
     */
    val replicationAggregation: String? = null
) {

    /**
     *  The alternatives ordered best first under [aggregationMethod], with ties broken by name so
     *  the order is the same every time it is asked for.
     */
    val recommendationOrder: List<String>
        get() = when (aggregationMethod) {
            AggregationMethod.WEIGHTED_VALUE -> alternatives.sortedWith(
                compareByDescending<String> { overallValues[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
            )
            AggregationMethod.FIRST_RANK_COUNT -> alternatives.sortedWith(
                compareByDescending<String> { firstRankCounts[it] ?: Int.MIN_VALUE }.thenBy { it }
            )
        }

    /** The record for the named metric, or null when the study had no such metric. */
    fun metric(metricName: String): MetricRecord? = metrics.firstOrNull { it.name == metricName }

    /** Indicates whether any metric turned out not to separate the alternatives. */
    val hasTiedMetric: Boolean
        get() = metrics.any { it.hadTiedScores }

    companion object {

        /**
         *  Takes a snapshot of [model] as it currently stands.
         *
         *  Built from an additive model rather than from any MODA model, because the weights and
         *  the weighted values a snapshot records are that model's, not every model's.
         *
         *  Where alternatives tie on whatever [aggregation] compares, the recommendation is the
         *  first by name. Some alternative has to be named, and choosing by name means the same
         *  study recommends the same one every time rather than depending on map ordering.
         *
         *  @param model the model to record. It is only read.
         *  @param rankTieMethod how to handle ties when ranking, defaulting to the model's own
         *  @param aggregation how to arrive at a single recommendation
         */
        fun of(
            model: AdditiveMODAModel,
            rankTieMethod: Statistic.Companion.Ranking = model.defaultRankingMethod,
            aggregation: AggregationMethod = AggregationMethod.WEIGHTED_VALUE,
            replicationAggregation: String? = null
        ): ModaSnapshot {
            require(model.alternatives.isNotEmpty()) {
                "A snapshot needs at least one alternative. The model '${model.name}' has none, " +
                        "which usually means no alternative could be taken in; use " +
                        "defineAlternativesReporting to find out why."
            }
            val metrics = model.metrics
            val alternatives = model.alternatives
            val warnings = model.warnings

            val realizedScores = model.scoresByMetric()
            val metricRecords = metrics.map { metric ->
                val effective = model.effectiveDomainOf(metric)
                val realized = realizedScores[metric].orEmpty()
                MetricRecord(
                    name = metric.name,
                    direction = metric.direction.name,
                    weight = model.weights[metric] ?: 0.0,
                    declaredLowerLimit = metric.domain.lowerLimit,
                    declaredUpperLimit = metric.domain.upperLimit,
                    effectiveLowerLimit = effective.lowerLimit,
                    effectiveUpperLimit = effective.upperLimit,
                    domainWasRescaled = model.wasRescaled(metric),
                    realizedLowestScore = realized.minOrNull(),
                    realizedHighestScore = realized.maxOrNull(),
                    hadTiedScores = warnings.any { it is ModaWarning.TiedScores && it.metric == metric.name },
                    valueFunctionId = model.valueFunctionIdOf(metric),
                    unitsOfMeasure = metric.unitsOfMeasure,
                    description = metric.description
                )
            }

            // Raw scores come back grouped by metric, one entry per alternative in declaration
            // order, so they are turned around here to be read the way results are reported.
            val scoresByMetric = model.scoresByMetric()
            val scores = alternatives.withIndex().associate { (index, alternative) ->
                alternative to metrics.associate { metric ->
                    metric.name to (scoresByMetric[metric]?.getOrNull(index) ?: Double.NaN)
                }
            }
            val valuesByMetric = model.alternativeValuesByMetric()
            val values = alternatives.associateWith { alternative ->
                val byMetric = valuesByMetric[alternative].orEmpty()
                metrics.associate { metric -> metric.name to (byMetric[metric] ?: Double.NaN) }
            }
            val overallValues = alternatives.associateWith { model.multiObjectiveValue(it) }
            val firstRankCounts = model.alternativeFirstRankCounts(false, rankTieMethod).toMap()
            val averageRankings = model.alternativeAverageRanking(false, rankTieMethod).toMap()

            val primary = when (aggregation) {
                AggregationMethod.WEIGHTED_VALUE -> alternatives.sortedWith(
                    compareByDescending<String> { overallValues[it] ?: Double.NEGATIVE_INFINITY }.thenBy { it }
                ).first()
                AggregationMethod.FIRST_RANK_COUNT -> alternatives.sortedWith(
                    compareByDescending<String> { firstRankCounts[it] ?: Int.MIN_VALUE }.thenBy { it }
                ).first()
            }

            return ModaSnapshot(
                name = model.name,
                metrics = metricRecords,
                alternatives = alternatives.toList(),
                scores = scores,
                values = values,
                overallValues = overallValues,
                firstRankCounts = alternatives.associateWith { firstRankCounts[it] ?: 0 },
                averageRankings = alternatives.associateWith { averageRankings[it] ?: Double.NaN },
                rankingMethod = rankTieMethod.name,
                aggregationMethod = aggregation,
                primaryRecommendation = primary,
                warnings = warnings.map { it.message },
                replicationAggregation = replicationAggregation
            )
        }
    }
}
