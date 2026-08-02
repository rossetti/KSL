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
import ksl.utilities.moda.AggregationMethod
import ksl.utilities.moda.ElicitationRecord
import ksl.utilities.moda.ElicitedRange
import ksl.utilities.moda.Metric
import ksl.utilities.moda.MetricIfc
import ksl.utilities.statistic.Statistic
import kotlinx.serialization.Serializable

/**
 *  Whether a study fits its metric domains to the scores that turn up, and how strictly.
 */
@Serializable
enum class RescalePolicy {

    /** Use the declared limits exactly as given. */
    NONE,

    /**
     *  Fit each metric's limits to the scores the alternatives realized, so the value functions
     *  discriminate over the range that matters. The usual choice.
     */
    FROM_SCORES,

    /**
     *  Use the declared limits exactly as given, and require them to be real bounds rather than
     *  placeholders.
     *
     *  Stricter than [NONE] in what it demands of the document rather than in what it does. Weights
     *  are tied to ranges, so a study whose weights were elicited from someone needs ranges that
     *  cannot move afterwards and that were meaningful when they were shown.
     */
    FIXED;

    /** Whether metric limits may be fitted to the realized scores under this policy. */
    val allowsRescaling: Boolean
        get() = this == FROM_SCORES
}

/**
 *  The range a weight was elicited against, as recorded in a document.
 */
@Serializable
data class ElicitedRangeSpec(
    val lowerLimit: Double,
    val upperLimit: Double
)

/**
 *  Weights obtained by asking someone, together with what they were shown when asked.
 *
 *  The ranges are kept because a swing weight is tied to the range it was given against, so a study
 *  that later changes a range is no longer the study those weights describe.
 */
@Serializable
data class ElicitationSpec(
    val order: List<String>,
    val ratings: Map<String, Double>,
    val elicitedAgainst: Map<String, ElicitedRangeSpec>,
    val adjustableRanges: List<String> = emptyList()
) {
    fun toRecord(): ElicitationRecord = ElicitationRecord(
        order = order,
        ratings = ratings,
        elicitedAgainst = elicitedAgainst.mapValues { ElicitedRange(it.value.lowerLimit, it.value.upperLimit) },
        adjustableRanges = adjustableRanges
    )
}

/**
 *  One metric of a study, as written down.
 *
 *  The defaults describe a metric nobody has thought about yet: unbounded above, adjustable at both
 *  ends, smaller being better, transformed linearly. That is deliberately a workable starting point
 *  and deliberately something validation remarks on, since an unbounded range is almost never what
 *  a considered study means.
 */
@Serializable
data class MetricSpec(
    val name: String,
    val direction: String = MetricIfc.Direction.SmallerIsBetter.name,
    val weight: Double = 1.0,
    val lowerLimit: Double = 0.0,
    val upperLimit: Double = Double.MAX_VALUE,
    val allowLowerLimitAdjustment: Boolean = true,
    val allowUpperLimitAdjustment: Boolean = true,
    val valueFunctionId: String = ValueFunctionRegistry.LINEAR,
    val parameters: Map<String, Double> = emptyMap(),
    val unitsOfMeasure: String? = null,
    val description: String? = null
) {

    /** The direction this spec names, or null when it names none that exist. */
    fun directionOrNull(): MetricIfc.Direction? =
        MetricIfc.Direction.entries.firstOrNull { it.name == direction }

    /** Whether the declared limits form a range with room in it. */
    val hasUsableLimits: Boolean
        get() = upperLimit > lowerLimit && (upperLimit - lowerLimit).isFinite()

    /** Whether both declared limits are real bounds rather than placeholders. */
    val hasFiniteLimits: Boolean
        get() = lowerLimit.isFinite() && upperLimit.isFinite() &&
                lowerLimit != -Double.MAX_VALUE && upperLimit != Double.MAX_VALUE

    /**
     *  Builds the metric this spec describes.
     *
     *  A fresh instance every time, and never shared between runs, because a model matches metrics
     *  by identity and reusing one across studies would tie them together invisibly.
     */
    fun toMetric(): Metric {
        val metric = Metric(
            name,
            Interval(lowerLimit, upperLimit),
            allowLowerLimitAdjustment,
            allowUpperLimitAdjustment
        )
        directionOrNull()?.let { metric.direction = it }
        metric.unitsOfMeasure = unitsOfMeasure
        metric.description = description
        return metric
    }
}

/**
 *  A study, written down: what is being compared, on what, how the comparison is made, and where
 *  the numbers come from.
 *
 *  This is the thing a person edits, a file holds, and a service is sent. It says everything needed
 *  to run the study and nothing about the outcome, so the same document run twice against the same
 *  data gives the same answer. Results are recorded separately, and a recorded result can be read
 *  without the document, though re-running one needs it.
 */
@Serializable
data class ModaDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val name: String,
    val metrics: List<MetricSpec>,
    val alternatives: List<String>,
    val source: ModaSourceReference,
    val rescalePolicy: RescalePolicy = RescalePolicy.FROM_SCORES,
    val rankingMethod: String = Statistic.Companion.Ranking.Ordinal.name,
    val aggregationMethod: String = AggregationMethod.WEIGHTED_VALUE.name,
    val elicitation: ElicitationSpec? = null
) {

    /** The ranking method this document names, or null when it names none that exist. */
    fun rankingMethodOrNull(): Statistic.Companion.Ranking? =
        Statistic.Companion.Ranking.entries.firstOrNull { it.name == rankingMethod }

    /** The aggregation method this document names, or null when it names none that exist. */
    fun aggregationMethodOrNull(): AggregationMethod? =
        AggregationMethod.entries.firstOrNull { it.name == aggregationMethod }

    /**
     *  The weights this document gives, against the supplied metric instances.
     *
     *  Weights are matched to metrics by name, since that is what a document can refer to, and the
     *  model needs them against the instances it was built with.
     */
    fun weightsBy(builtMetrics: List<MetricIfc>): Map<MetricIfc, Double> {
        val byName = metrics.associate { it.name to it.weight }
        return builtMetrics.associateWith { byName[it.name] ?: 0.0 }
    }

    /** The weights this document gives, when elicited weights are not being used. */
    val declaredWeights: Map<String, Double>
        get() = metrics.associate { it.name to it.weight }

    companion object {

        /**
         *  The version of this document format. Raise it when a change would stop an older reader
         *  from making sense of a document, so a reader can refuse rather than misread it.
         */
        const val SCHEMA_VERSION: Int = 1
    }
}
