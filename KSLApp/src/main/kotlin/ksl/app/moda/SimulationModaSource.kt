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

import ksl.utilities.Interval
import ksl.utilities.io.dbutil.WithinRepViewData
import ksl.utilities.moda.MODAAnalyzer
import ksl.utilities.statistic.Statistic

/**
 *  An alternative and response for which observations were expected but none were found.
 *
 *  Told apart from a score that is simply absent, because the two mean different things: nothing
 *  recorded at all usually means the response was never collected or the name is wrong, whereas a
 *  gap in otherwise present data usually means a replication failed.
 */
data class EmptySeries(
    val alternative: String,
    val metric: String
) {
    val message: String
        get() = "No observations at all for alternative '$alternative' on response '$metric'."
}

/**
 *  Scores taken from the replications of a set of simulated alternatives.
 *
 *  Each alternative is an experiment, each metric a response, and each replication one observation
 *  of that response. Comparing the alternatives means reducing each series to a single score, which
 *  is what [aggregation] decides.
 *
 *  Observations are matched by replication number rather than by position, because a study usually
 *  drives every alternative with the same random numbers so that replication 7 means the same
 *  conditions for each of them. That is what makes comparing them replication by replication
 *  meaningful, and it survives an alternative missing a replication in the middle.
 *
 *  Alternatives with different numbers of replications are aggregated as they stand, each over
 *  whatever it has. This differs from [MODAAnalyzer], which discards records from any experiment
 *  not matching the smallest replication count it finds. Aggregating what is there answers the
 *  question that was asked; the stricter rule belongs to comparing replication by replication,
 *  where it is enforced separately.
 *
 *  @param responseData the within-replication records to read
 *  @param aggregation how a series becomes a score
 *  @param percentile which percentile to take, when [aggregation] is
 *  [ReplicationAggregation.PERCENTILE]. Must be within (0, 1).
 */
class SimulationModaSource(
    responseData: List<WithinRepViewData>,
    override val aggregation: ReplicationAggregation = ReplicationAggregation.MEAN,
    private val percentile: Double = 0.5
) : ReplicatedModaSourceIfc {

    init {
        if (aggregation == ReplicationAggregation.PERCENTILE) {
            require(percentile > 0.0 && percentile < 1.0) {
                "A percentile must be within (0, 1). It was $percentile."
            }
        }
    }

    /**
     *  The observations, by alternative, then response, then replication number.
     *
     *  Keyed by replication rather than held as a list so that matching alternatives up by
     *  replication is a lookup rather than a search, and so a repeated record cannot lengthen a
     *  series.
     */
    private val observations: Map<String, Map<String, Map<Int, Double>>> =
        responseData
            .filter { it.rep_value != null }
            .groupBy { it.exp_name }
            .mapValues { (_, forExperiment) ->
                forExperiment.groupBy { it.stat_name }
                    .mapValues { (_, forResponse) ->
                        // A replication recorded more than once keeps the first, so re-reading the
                        // same data gives the same study rather than depending on record order.
                        val byReplication = LinkedHashMap<Int, Double>()
                        for (observation in forResponse) {
                            byReplication.putIfAbsent(observation.rep_id, observation.rep_value!!)
                        }
                        byReplication
                    }
            }

    /** The alternatives this source holds anything for. */
    val alternativeNames: Set<String>
        get() = observations.keys

    /** The responses this source holds anything for. */
    val responseNames: Set<String>
        get() = observations.values.flatMap { it.keys }.toSet()

    /** Series that were asked for and turned out to hold nothing, from the last call to [scores]. */
    var emptySeries: List<EmptySeries> = emptyList()
        private set

    override fun replicationIds(alternative: String): List<Int> =
        observations[alternative]?.values?.flatMap { it.keys }?.distinct()?.sorted() ?: emptyList()

    override fun replicationScores(alternative: String, metric: String): DoubleArray? {
        val series = observations[alternative]?.get(metric) ?: return null
        if (series.isEmpty()) return null
        return series.entries.sortedBy { it.key }.map { it.value }.toDoubleArray()
    }

    override fun scores(alternatives: Set<String>, metrics: Set<String>): ScoreTable {
        val values = mutableMapOf<String, Map<String, Double>>()
        val missing = mutableListOf<MissingScore>()
        val empty = mutableListOf<EmptySeries>()
        for (alternative in alternatives) {
            val complete = mutableMapOf<String, Double>()
            var whole = true
            for (metric in metrics.sorted()) {
                val series = replicationScores(alternative, metric)
                if (series == null || series.isEmpty()) {
                    missing.add(MissingScore(alternative, metric))
                    empty.add(EmptySeries(alternative, metric))
                    whole = false
                    continue
                }
                val score = reduce(series)
                if (!score.isFinite()) {
                    // A series that reduces to something that is not a number cannot be scored
                    // against, and treating it as a value would put that non-number into the result.
                    missing.add(MissingScore(alternative, metric))
                    whole = false
                    continue
                }
                complete[metric] = score
            }
            if (whole) values[alternative] = complete
        }
        emptySeries = empty
        return ScoreTable(values, missing)
    }

    /** Reduces one series to the single score a study compares on. */
    private fun reduce(series: DoubleArray): Double = when (aggregation) {
        ReplicationAggregation.MEAN -> Statistic(series).average
        // Taken as the fiftieth percentile rather than through the median helper, so that asking
        // for the median and asking for the fiftieth percentile cannot give different answers.
        ReplicationAggregation.MEDIAN -> Statistic.percentile(series.copyOf(), 0.5)
        ReplicationAggregation.PERCENTILE -> Statistic.percentile(series.copyOf(), percentile)
        // Ordered by replication, so the last element is the last replication.
        ReplicationAggregation.LAST -> series.last()
    }

    /**
     *  How many replications each of the named alternatives holds.
     */
    fun replicationCounts(alternatives: Collection<String>): Map<String, Int> =
        alternatives.associateWith { replicationIds(it).size }

    /**
     *  Whether every named alternative was observed over the same number of replications.
     *
     *  Comparing alternatives replication by replication needs this; aggregating them does not.
     */
    fun hasEqualReplicationCounts(alternatives: Collection<String>): Boolean =
        replicationCounts(alternatives).values.distinct().size <= 1

    companion object {

        /**
         *  Ranges for the named responses, wide enough to hold what the runs produced.
         *
         *  Worked out the same way [MODAAnalyzer] works them out, by handing the job to it, so that
         *  a study built here and one built there cannot disagree about the range a response was
         *  scored over. Ranges matter because a value function is defined against one, so two
         *  studies over the same runs with different ranges give different values.
         */
        fun recommendedDomains(
            responseNames: Set<String>,
            responseData: List<WithinRepViewData>
        ): Map<String, Interval> =
            MODAAnalyzer.recommendMetricDomainIntervals(responseNames, responseData)

        /**
         *  Builds the metric specifications for a study over simulated responses, with ranges fitted
         *  to what the runs produced and then held fixed.
         *
         *  Held fixed because the ranges already come from the data; fitting them a second time
         *  inside the study would narrow them again to the aggregated scores, which are less spread
         *  out than the replications they came from, and the alternatives would be pushed apart by
         *  more than the runs justify.
         *
         *  @param responseWeights the weight for each response, which also names the responses used
         *  @param responseData the records the ranges are taken from
         *  @param directions how each response is read, defaulting to smaller being better
         */
        fun metricSpecsFor(
            responseWeights: Map<String, Double>,
            responseData: List<WithinRepViewData>,
            directions: Map<String, String> = emptyMap(),
            declaredDomains: Map<String, Interval> = emptyMap()
        ): List<MetricSpec> {
            require(responseWeights.isNotEmpty()) { "At least one response must be given a weight." }
            val recommended = recommendedDomains(responseWeights.keys, responseData)
            return responseWeights.map { (response, weight) ->
                val suggested = recommended[response]
                    ?: throw IllegalArgumentException("No data was found for response '$response'.")
                val declared = declaredDomains[response] ?: DEFAULT_RESPONSE_DOMAIN
                val domain = fillInUnbounded(declared, suggested)
                MetricSpec(
                    name = response,
                    direction = directions[response] ?: ksl.utilities.moda.MetricIfc.Direction.SmallerIsBetter.name,
                    weight = weight,
                    lowerLimit = domain.lowerLimit,
                    upperLimit = domain.upperLimit,
                    allowLowerLimitAdjustment = false,
                    allowUpperLimitAdjustment = false
                )
            }
        }

        /**
         *  What a response is declared to range over, when nothing was said. Responses are counts
         *  and averages of times and quantities, so zero is the usual floor and the ceiling is the
         *  part nobody can state in advance.
         */
        val DEFAULT_RESPONSE_DOMAIN: Interval
            get() = Interval(0.0, Double.MAX_VALUE)

        /**
         *  Keeps whichever limits were actually stated and fills in only the ones left open.
         *
         *  A limit someone stated is a fact about the response, such as a utilization never
         *  exceeding one, and is worth more than anything inferred from a handful of runs. A limit
         *  left open is exactly what the runs can speak to. This is the same rule [MODAAnalyzer]
         *  applies, and it is followed here so that a study built either way scores over the same
         *  range.
         */
        fun fillInUnbounded(declared: Interval, suggested: Interval): Interval {
            val lower = if (declared.lowerLimit.isInfinite() || declared.lowerLimit == -Double.MAX_VALUE) {
                suggested.lowerLimit
            } else {
                declared.lowerLimit
            }
            val upper = if (declared.upperLimit.isInfinite() || declared.upperLimit == Double.MAX_VALUE) {
                suggested.upperLimit
            } else {
                declared.upperLimit
            }
            return Interval(lower, upper)
        }

        /**
         *  Builds a study over simulated responses, ready to run.
         *
         *  The ranges come from the runs and are then held as declared, which is what makes a study
         *  built this way agree with one built through [MODAAnalyzer] over the same records.
         *
         *  @param name what to call the study
         *  @param alternatives the experiments being compared
         *  @param responseWeights the weight for each response
         *  @param responseData the records to read
         *  @param directions how each response is read, defaulting to smaller being better
         */
        fun documentFor(
            name: String,
            alternatives: List<String>,
            responseWeights: Map<String, Double>,
            responseData: List<WithinRepViewData>,
            directions: Map<String, String> = emptyMap(),
            declaredDomains: Map<String, Interval> = emptyMap()
        ): ModaDocument = ModaDocument(
            name = name,
            metrics = metricSpecsFor(responseWeights, responseData, directions, declaredDomains),
            alternatives = alternatives,
            // The scores come from the source handed to the runner, not from the document. A study
            // over simulated runs is not self-contained: re-running it means re-reading the runs.
            source = ModaSourceReference.RegisteredProvider(SIMULATION_PROVIDER_ID),
            rescalePolicy = RescalePolicy.NONE
        )

        /** The name a study uses to refer to the simulated runs handed to the runner. */
        const val SIMULATION_PROVIDER_ID: String = "simulation"
    }
}
