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

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Tests what the padding around a fitted domain does, and does not, do to a study's conclusions.
 *
 *  Fitting a metric's domain to the realized scores does not fit it to their exact range. It leaves
 *  room beyond both ends, on the grounds that the extremes actually observed understate the extremes
 *  achievable, and that a score is itself an uncertain quantity rather than an exact one. That is
 *  deliberate, and it also stops the best alternative in the set from scoring exactly one merely for
 *  being the best of the ones considered, which would be a claim about the comparison rather than
 *  about the alternative.
 *
 *  The room left is proportional to each metric's own range, and that turns out to matter a great
 *  deal. It means every metric is stretched by the same factor, so the padding is a common
 *  rescaling of the overall value rather than a reweighting between metrics, and no ranking can move
 *  because of it. These tests establish that, and — just as importantly — establish where it stops
 *  being true, because those are the cases where a change to the padding could change what a study
 *  recommends.
 */
class ModaDomainPaddingTest {

    // ------------------------------------------------------------------------------------------
    // Building the same study with and without the padding
    // ------------------------------------------------------------------------------------------

    /**
     *  A domain wide enough that fitting never reaches either end of it, so the tests below see the
     *  padding on its own. Holding the fitting within a declared domain is a separate thing with a
     *  separate effect, tested separately.
     */
    private val unconstrained = Interval(-1.0e9, 1.0e9)

    private fun studyOf(
        scores: Map<String, Map<String, Double>>,
        weights: Map<String, Double>,
        declared: Interval = Interval(0.0, 1000.0),
        adjustLower: Boolean = true,
        adjustUpper: Boolean = true,
        valueFunction: (String) -> ValueFunctionIfc = { LinearValueFunction() },
        allowRescaling: Boolean = true
    ): AdditiveMODAModel {
        val metricNames = scores.values.first().keys.toList()
        val metrics = metricNames.associateWith {
            Metric(it, Interval(declared.lowerLimit, declared.upperLimit), adjustLower, adjustUpper)
        }
        val definitions: Map<MetricIfc, ValueFunctionIfc> =
            metricNames.associate { metrics[it]!! as MetricIfc to valueFunction(it) }
        val model = AdditiveMODAModel(definitions, weights.mapKeys { metrics[it.key]!! as MetricIfc })
        model.defineAlternatives(
            scores.mapValues { (_, byMetric) -> metricNames.map { Score(metrics[it]!!, byMetric[it]!!) } },
            allowRescalingByMetrics = allowRescaling
        )
        return model
    }

    /**
     *  The same study evaluated against each metric's exact realized range, which is what fitting
     *  would give with no room left over. Used only as the thing the padded study is compared
     *  against; it is not how the engine behaves.
     */
    private fun unpaddedOrdering(
        scores: Map<String, Map<String, Double>>,
        weights: Map<String, Double>,
        directions: Map<String, MetricIfc.Direction> = emptyMap()
    ): List<String> {
        val metricNames = scores.values.first().keys.toList()
        val totalWeight = weights.values.sum()
        val overall = scores.mapValues { (_, byMetric) ->
            metricNames.sumOf { metric ->
                val values = scores.values.map { it[metric]!! }
                val low = values.min()
                val high = values.max()
                val span = high - low
                val normalized = if (span <= 0.0) 0.5 else (byMetric[metric]!! - low) / span
                val directed =
                    if (directions[metric] == MetricIfc.Direction.BiggerIsBetter) normalized else 1.0 - normalized
                (weights[metric]!! / totalWeight) * directed
            }
        }
        return overall.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    private fun ordering(model: AdditiveMODAModel): List<String> =
        model.alternatives
            .map { it to model.multiObjectiveValue(it) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .map { it.first }

    private fun randomScores(
        rng: RNStreamIfc,
        alternatives: Int,
        metrics: Int
    ): Map<String, Map<String, Double>> =
        (1..alternatives).associate { alternative ->
            "Alt$alternative" to (1..metrics).associate { metric -> "M$metric" to rng.randU01() * 900.0 }
        }

    // ------------------------------------------------------------------------------------------
    // The property: with the room left proportional, no ranking moves
    // ------------------------------------------------------------------------------------------

    /**
     *  The room left beyond each end is a fixed fraction of that metric's own range, so every metric
     *  is stretched by the same factor and the overall value is stretched by that factor too. A
     *  common stretch cannot reorder anything, so leaving the room costs nothing in what the study
     *  concludes.
     */
    @Test
    fun `leaving room around the realized range changes no ranking`() {
        val rng = RNStreamProvider().rnStream(11)
        repeat(80) {
            val alternatives = 3 + (rng.randU01() * 5).toInt()   // 3..7, above the rounding path
            val metrics = 1 + (rng.randU01() * 4).toInt()        // 1..4
            val scores = randomScores(rng, alternatives, metrics)
            val weights = (1..metrics).associate { "M$it" to 0.1 + rng.randU01() }

            val padded = ordering(studyOf(scores, weights, declared = unconstrained))
            val exact = unpaddedOrdering(scores, weights)
            assertEquals(exact, padded, "the padding reordered a study of $alternatives alternatives")
        }
    }

    @Test
    fun `it changes no ranking when metrics are read in different directions`() {
        val rng = RNStreamProvider().rnStream(12)
        repeat(60) {
            val alternatives = 3 + (rng.randU01() * 5).toInt()
            val metrics = 2 + (rng.randU01() * 3).toInt()
            val scores = randomScores(rng, alternatives, metrics)
            val weights = (1..metrics).associate { "M$it" to 0.1 + rng.randU01() }
            val directions = (1..metrics).associate {
                "M$it" to if (it % 2 == 0) MetricIfc.Direction.BiggerIsBetter else MetricIfc.Direction.SmallerIsBetter
            }

            val metricNames = scores.values.first().keys.toList()
            val built = metricNames.associateWith {
                Metric(it, Interval(unconstrained.lowerLimit, unconstrained.upperLimit))
            }
            built.forEach { (name, metric) -> metric.direction = directions[name]!! }
            val definitions: Map<MetricIfc, ValueFunctionIfc> =
                metricNames.associate { built[it]!! as MetricIfc to LinearValueFunction() }
            val model = AdditiveMODAModel(definitions, weights.mapKeys { built[it.key]!! as MetricIfc })
            model.defineAlternatives(
                scores.mapValues { (_, byMetric) -> metricNames.map { Score(built[it]!!, byMetric[it]!!) } }
            )

            assertEquals(
                unpaddedOrdering(scores, weights, directions), ordering(model),
                "the padding reordered a study with mixed directions"
            )
        }
    }

    /** The fraction of the realized range left beyond each end, for a given number of alternatives. */
    private fun marginFraction(alternatives: Int): Double =
        minOf(1.0 / (alternatives - 1.0), MODAModel.maximumDomainMarginFraction)

    /**
     *  What the padding does instead of reordering: it pulls every value towards the middle by a
     *  known amount. With a margin fraction f the values can only occupy f/(1+2f) to (1+f)/(1+2f),
     *  so under the default cap nothing can score above about 0.833 however good it is relative to
     *  the others. Worth pinning, because it is the part a reader of a report is most likely to
     *  misread.
     */
    @Test
    fun `the padding compresses the values by a known and predictable amount`() {
        for (alternatives in listOf(3, 4, 5, 9, 17)) {
            val scores = (1..alternatives).associate { index ->
                "Alt$index" to mapOf("M1" to 100.0 * index)
            }
            val model = studyOf(scores, mapOf("M1" to 1.0), declared = unconstrained)
            val metric = model.metrics.first()
            // Smaller is better by default, so the smallest score takes the largest value.
            val values = model.alternatives.map { model.valuesByAlternative(it)[metric]!! }
            val f = marginFraction(alternatives)
            assertEquals((1.0 + f) / (1.0 + 2.0 * f), values.max(), 1.0e-12, "for $alternatives alternatives")
            assertEquals(f / (1.0 + 2.0 * f), values.min(), 1.0e-12, "for $alternatives alternatives")
            assertTrue(values.all { it > 0.0 && it < 1.0 }, "a value reached an endpoint for $alternatives")
        }
    }

    /**
     *  Left uncapped, a study of three alternatives would be spread over a domain built mostly out
     *  of room left over — half the realized range at each end, on the evidence of three
     *  observations. The cap holds that back, and the values spread further as a result.
     */
    @Test
    fun `the margin is capped so a handful of alternatives are not spread on a handful of observations`() {
        val scores = mapOf(
            "A" to mapOf("M1" to 100.0),
            "B" to mapOf("M1" to 300.0),
            "C" to mapOf("M1" to 500.0)
        )
        val model = studyOf(scores, mapOf("M1" to 1.0), declared = unconstrained)
        val metric = model.metrics.first()
        // Realized range 400. Uncapped the margin would be 200 each side, giving 1200 of width for
        // 400 of alternatives; the cap holds it to 100, for 600.
        assertEquals(Interval(0.0, 600.0), model.effectiveDomainOf(metric))
        assertEquals(600.0, model.effectiveDomainOf(metric).width)
    }

    /**
     *  With enough alternatives the estimate is already modest and the cap does nothing, so a study
     *  large enough to speak for itself is fitted exactly as the estimate says.
     */
    @Test
    fun `with enough alternatives the cap does nothing and the estimate stands`() {
        for (alternatives in listOf(5, 9, 17, 33)) {
            val scores = (1..alternatives).associate { index ->
                "Alt$index" to mapOf("M1" to 100.0 * index)
            }
            val model = studyOf(scores, mapOf("M1" to 1.0), declared = unconstrained)
            val realized = (1..alternatives).map { 100.0 * it }
            assertEquals(
                PDFModeler.rangeEstimate(realized.min(), realized.max(), alternatives),
                model.effectiveDomainOf(model.metrics.first()),
                "the cap should not bind for $alternatives alternatives"
            )
        }
    }

    /**
     *  Capping a fraction of each metric's own range keeps the stretch common to every metric, so it
     *  can no more reorder a study than the margin itself can. The neutrality tests above run over
     *  three to seven alternatives, which spans where the cap binds and where it does not, so they
     *  establish this for both.
     */
    @Test
    fun `raising the cap beyond a half turns it off`() {
        val scores = mapOf(
            "A" to mapOf("M1" to 100.0),
            "B" to mapOf("M1" to 300.0),
            "C" to mapOf("M1" to 500.0)
        )
        val original = MODAModel.maximumDomainMarginFraction
        try {
            MODAModel.maximumDomainMarginFraction = 1.0
            val model = studyOf(scores, mapOf("M1" to 1.0), declared = unconstrained)
            assertEquals(
                PDFModeler.rangeEstimate(100.0, 500.0, 3),
                model.effectiveDomainOf(model.metrics.first()),
                "a cap above a half should never bind"
            )
        } finally {
            MODAModel.maximumDomainMarginFraction = original
        }
    }

    /**
     *  The reason for leaving the room in the first place: no alternative should be called perfect,
     *  or worthless, merely for being the extreme of the set that happened to be compared.
     */
    @Test
    fun `no alternative is scored perfect or worthless for being the extreme of the set`() {
        val rng = RNStreamProvider().rnStream(13)
        repeat(40) {
            val alternatives = 3 + (rng.randU01() * 5).toInt()
            val metrics = 1 + (rng.randU01() * 3).toInt()
            val scores = randomScores(rng, alternatives, metrics)
            val model = studyOf(scores, (1..metrics).associate { "M$it" to 1.0 }, declared = unconstrained)
            for (alternative in model.alternatives) {
                for ((_, value) in model.valuesByAlternative(alternative)) {
                    assertTrue(value > 0.0 && value < 1.0, "a value reached an endpoint: $value")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Where the property stops holding
    // ------------------------------------------------------------------------------------------

    /**
     *  Holding one limit while fitting the other breaks the common stretch: the width then depends
     *  on where the held limit happens to sit, which differs between metrics. This is not a fault,
     *  but it does mean a study with limits held on one side is one where changing the padding could
     *  change what is recommended, so such a study is not covered by the property above.
     */
    @Test
    fun `holding a limit on one side stretches metrics by different factors`() {
        // Both metrics keep a declared lower limit of zero, but their realized ranges sit at very
        // different distances from it, so holding that limit stretches them unequally.
        val scores = mapOf(
            "A" to mapOf("Near" to 1.0, "Far" to 600.0),
            "B" to mapOf("Near" to 5.0, "Far" to 500.0),
            "C" to mapOf("Near" to 9.0, "Far" to 400.0)
        )
        val model = studyOf(
            scores, mapOf("Near" to 1.0, "Far" to 1.0),
            adjustLower = false, adjustUpper = true
        )
        val near = model.metrics.first { it.name == "Near" }
        val far = model.metrics.first { it.name == "Far" }
        // Near: realized 1..9, so a proposal of -1..11 held at zero gives 0..11.
        assertEquals(Interval(0.0, 11.0), model.effectiveDomainOf(near))
        // Far: realized 400..600, so a proposal of 350..650 held at zero gives 0..650.
        assertEquals(Interval(0.0, 650.0), model.effectiveDomainOf(far))

        val nearStretch = model.effectiveDomainOf(near).width / 8.0
        val farStretch = model.effectiveDomainOf(far).width / 200.0
        assertTrue(
            farStretch > nearStretch * 1.5,
            "holding a limit should stretch these metrics unequally: $nearStretch vs $farStretch"
        )
        // Unequal stretching is a reweighting between the metrics, so a study of this shape is one
        // where a change to the padding could change what is recommended. It is therefore not
        // covered by the property established above, which is the point of recording it here.
    }

    /**
     *  With only two alternatives the domain is rounded outward to whole numbers instead, which is
     *  an absolute adjustment rather than one proportional to the range. Two metrics measured on
     *  very different scales are then stretched by very different factors.
     */
    @Test
    fun `with two alternatives the rounding is absolute rather than proportional`() {
        val scores = mapOf(
            "A" to mapOf("Small" to 1.2, "Large" to 500.4),
            "B" to mapOf("Small" to 1.8, "Large" to 900.6)
        )
        val model = studyOf(scores, mapOf("Small" to 1.0, "Large" to 1.0))
        val small = model.metrics.first { it.name == "Small" }
        val large = model.metrics.first { it.name == "Large" }
        // Small is stretched from a range of 0.6 to a width of 1; Large from 400.2 to 401.
        assertEquals(Interval(1.0, 2.0), model.effectiveDomainOf(small))
        assertEquals(Interval(500.0, 901.0), model.effectiveDomainOf(large))
        val smallStretch = model.effectiveDomainOf(small).width / 0.6
        val largeStretch = model.effectiveDomainOf(large).width / 400.2
        assertTrue(
            smallStretch > largeStretch * 1.5,
            "the two metrics should be stretched by very different factors: $smallStretch vs $largeStretch"
        )
    }

    /**
     *  Holding the fitting within a declared domain also breaks the common stretch, because how much
     *  is trimmed depends on where each metric's realized scores sit relative to its own limits.
     *
     *  This is the price of the holding, and it is recorded here rather than left to be discovered:
     *  a study with real limits declared can reach a different conclusion than the same study with
     *  those limits left open. That is the intended outcome — values computed over a range the
     *  metric cannot occupy were answering a question nobody asked — but it is a change, not a
     *  refinement, and studies with declared bounds should be expected to move.
     */
    @Test
    fun `holding the fitting within declared limits can change what a study recommends`() {
        // Both metrics run 0 to 100 and are nearly balanced, but their realized scores sit at
        // different distances from the limits, so the trimming falls unevenly between them.
        val scores = mapOf(
            "A" to mapOf("Bounded" to 5.0, "Roomy" to 46.0),
            "B" to mapOf("Bounded" to 50.0, "Roomy" to 44.0),
            "C" to mapOf("Bounded" to 95.0, "Roomy" to 42.0)
        )
        val weights = mapOf("Bounded" to 1.0, "Roomy" to 1.0)
        val held = studyOf(scores, weights, declared = Interval(0.0, 100.0))
        val open = studyOf(scores, weights, declared = unconstrained)

        val bounded = held.metrics.first { it.name == "Bounded" }
        // Realized 5..95, so a quarter of that range at each end proposes -17.5..117.5, which the
        // declared limits trim back to 0..100.
        assertEquals(Interval(0.0, 100.0), held.effectiveDomainOf(bounded))
        assertEquals(Interval(-17.5, 117.5), open.effectiveDomainOf(open.metrics.first { it.name == "Bounded" }))

        // Both are well defined; whether they agree depends on the data, which is the point.
        assertEquals(held.alternatives.toSet(), open.alternatives.toSet())
        for (alternative in held.alternatives) {
            assertTrue(held.multiObjectiveValue(alternative).isFinite())
            assertTrue(open.multiObjectiveValue(alternative).isFinite())
        }
    }

    /**
     *  Where the holding does its work: the values spread further, because none of the value range
     *  is spent on scores the metric could never take.
     */
    @Test
    fun `holding the fitting within declared limits sharpens the separation`() {
        // A utilization, which cannot exceed one however the fitting is done.
        val scores = mapOf(
            "A" to mapOf("Utilization" to 0.60),
            "B" to mapOf("Utilization" to 0.80),
            "C" to mapOf("Utilization" to 0.95)
        )
        val model = studyOf(scores, mapOf("Utilization" to 1.0), declared = Interval(0.0, 1.0))
        val metric = model.metrics.first()

        // Realized 0.60..0.95, so a quarter of that range at each end proposes 0.5125..1.0375; the
        // upper limit trims it to 1.0.
        val effective = model.effectiveDomainOf(metric)
        assertEquals(0.5125, effective.lowerLimit, 1.0e-12)
        assertEquals(1.0, effective.upperLimit, 1.0e-12, "the fitting reached past what the metric can be")

        val values = model.alternatives.associateWith { model.valuesByAlternative(it)[metric]!! }
        val spread = values.values.max() - values.values.min()
        // Untrimmed the same study spreads over 0.5 of the value range; trimmed it spreads further.
        assertTrue(spread > 0.5, "the trimming should sharpen the separation, not blunt it: $spread")
        assertTrue(values.values.all { it > 0.0 && it < 1.0 }, "a value reached an endpoint: $values")
    }

    /**
     *  An alternative sitting exactly on a natural limit does take the endpoint value, and that is
     *  the one case where an endpoint is meaningful: it says the alternative achieves the best the
     *  metric allows, which stays true however many other alternatives are added. That is quite
     *  unlike an endpoint earned by being the extreme of whoever happened to be compared.
     */
    @Test
    fun `an alternative on a natural limit takes the endpoint, and keeps it as others are added`() {
        val metric = Metric("Utilization", Interval(0.0, 1.0))
        metric.direction = MetricIfc.Direction.BiggerIsBetter
        val model = AdditiveMODAModel(mapOf<MetricIfc, ValueFunctionIfc>(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf(
                "Saturated" to listOf(Score(metric, 1.0)),
                "Busy" to listOf(Score(metric, 0.8)),
                "Idle" to listOf(Score(metric, 0.3))
            )
        )
        assertEquals(1.0, model.valuesByAlternative("Saturated")[metric]!!, 1.0e-12)

        // Adding another alternative does not take that away, because it was never a statement
        // about the comparison.
        model.defineAlternatives(
            mapOf(
                "Saturated" to listOf(Score(metric, 1.0)),
                "Busy" to listOf(Score(metric, 0.8)),
                "Idle" to listOf(Score(metric, 0.3)),
                "Dormant" to listOf(Score(metric, 0.05))
            )
        )
        assertEquals(1.0, model.valuesByAlternative("Saturated")[metric]!!, 1.0e-12)
    }

    /**
     *  The domain a metric gets when nothing is said about it is open above but floored at zero, so
     *  it is not a metric with no limits — it is one that has been declared non-negative. That floor
     *  now holds the fitting, which is the right reading of it: costs, durations and counts do not
     *  go below zero, and a fitted range reaching under it was describing scores the metric could
     *  never take.
     *
     *  Nothing is newly forbidden by this. That floor already bound the scores themselves, since a
     *  score cannot be built outside its metric's domain, so a study with genuinely negative values
     *  always had to declare a domain admitting them.
     */
    @Test
    fun `the default domain floors the fitting at zero, because it declares the metric non-negative`() {
        val metric = Metric("Cost")
        val model = AdditiveMODAModel(mapOf<MetricIfc, ValueFunctionIfc>(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(metric, 50.0)),
                "B" to listOf(Score(metric, 300.0)),
                "C" to listOf(Score(metric, 500.0))
            )
        )
        // Realized 50..500, so a quarter of that range at each end proposes -62.5..612.5.
        val margin = MODAModel.maximumDomainMarginFraction * 450.0
        assertTrue(50.0 - margin < 0.0, "the setup does not exercise the floor")
        assertEquals(Interval(0.0, 500.0 + margin), model.effectiveDomainOf(metric))
    }

    /**
     *  A metric declared able to go below zero is fitted below zero, so the floor is a consequence
     *  of what the default says rather than a rule imposed on every metric.
     */
    @Test
    fun `a metric declared able to go negative is fitted without a floor`() {
        val metric = Metric("Profit", Interval(-1.0e6, 1.0e6))
        metric.direction = MetricIfc.Direction.BiggerIsBetter
        val model = AdditiveMODAModel(mapOf<MetricIfc, ValueFunctionIfc>(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(metric, 50.0)),
                "B" to listOf(Score(metric, 300.0)),
                "C" to listOf(Score(metric, 500.0))
            )
        )
        val margin = MODAModel.maximumDomainMarginFraction * 450.0
        assertEquals(Interval(50.0 - margin, 500.0 + margin), model.effectiveDomainOf(metric))
    }

    /**
     *  The interval placed around tied scores is not a claim about the metric's range, so it is not
     *  held within the declared limits. Holding it would move the tied scores off the centre and
     *  destroy the only property it was built to have.
     */
    @Test
    fun `tied scores keep their centred interval even against a limit`() {
        val metric = Metric("Utilization", Interval(0.0, 1.0))
        val model = AdditiveMODAModel(mapOf<MetricIfc, ValueFunctionIfc>(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(metric, 0.0)),
                "B" to listOf(Score(metric, 0.0)),
                "C" to listOf(Score(metric, 0.0))
            )
        )
        // Everything ties at the very bottom of the declared range, and still lands at the middle
        // of the value range rather than at an end of it.
        for (alternative in model.alternatives) {
            assertEquals(
                0.5, model.valuesByAlternative(alternative)[metric]!!, 1.0e-12,
                "a tied metric should contribute equally to every alternative"
            )
        }
        assertTrue(model.warnings.any { it is ModaWarning.TiedScores })
    }

    /**
     *  A logistic value function is defined by its own location and scale and never consults the
     *  metric's domain, so fitting the domain leaves its values untouched. A study mixing one with a
     *  linear metric therefore has only some of its metrics moved by the padding.
     */
    @Test
    fun `a logistic value function ignores the fitted domain entirely`() {
        val scores = mapOf(
            "A" to mapOf("M1" to 100.0),
            "B" to mapOf("M1" to 300.0),
            "C" to mapOf("M1" to 500.0)
        )
        val fitted = studyOf(
            scores, mapOf("M1" to 1.0),
            valueFunction = { LogisticFunction(300.0, 100.0) },
            allowRescaling = true
        )
        val asDeclared = studyOf(
            scores, mapOf("M1" to 1.0),
            valueFunction = { LogisticFunction(300.0, 100.0) },
            allowRescaling = false
        )
        assertTrue(fitted.wasRescaled(fitted.metrics.first()), "the setup did not actually fit a domain")
        for (alternative in listOf("A", "B", "C")) {
            assertEquals(
                asDeclared.multiObjectiveValue(alternative), fitted.multiObjectiveValue(alternative), 1.0e-12,
                "fitting the domain changed a logistic value for $alternative"
            )
        }
    }
}
