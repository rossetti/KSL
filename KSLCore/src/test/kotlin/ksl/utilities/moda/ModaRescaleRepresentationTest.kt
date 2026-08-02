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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Tests for how a MODA model represents a metric whose domain it has fitted to the realized
 *  scores.
 *
 *  Fitting used to be done by writing to the domain of the metric the caller supplied, so
 *  evaluating a model altered an object the caller still owned, and evaluating it twice gave
 *  different answers. These tests pin the properties that replacement has to have: the caller's
 *  metric is left alone, the fitted domain is what the values were actually computed over, one
 *  metric is never evaluated against two domains at once, and none of it asks anything of metric
 *  implementations from outside this library.
 */
class ModaRescaleRepresentationTest {

    // ------------------------------------------------------------------------------------------
    // The caller's metric is not modified
    // ------------------------------------------------------------------------------------------

    @Test
    fun `evaluating a model does not modify the metric the caller supplied`() {
        for (adjustLower in listOf(true, false)) {
            for (adjustUpper in listOf(true, false)) {
                val metric = Metric("Cost", Interval(0.0, 100.0), adjustLower, adjustUpper)
                val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
                model.defineAlternatives(
                    mapOf(
                        "A" to listOf(Score(metric, 20.0)),
                        "B" to listOf(Score(metric, 40.0)),
                        "C" to listOf(Score(metric, 60.0))
                    )
                )
                val flags = "adjustLower=$adjustLower, adjustUpper=$adjustUpper"
                assertEquals(0.0, metric.domain.lowerLimit, "declared lower limit moved ($flags)")
                assertEquals(100.0, metric.domain.upperLimit, "declared upper limit moved ($flags)")
            }
        }
    }

    @Test
    fun `a metric that permits no adjustment is evaluated against its declared domain`() {
        val metric = Metric("Cost", Interval(0.0, 100.0), allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = false)
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf("A" to listOf(Score(metric, 20.0)), "B" to listOf(Score(metric, 60.0)))
        )
        assertFalse(model.wasRescaled(metric), "a metric that refuses adjustment was rescaled anyway")
        assertEquals(Interval(0.0, 100.0), model.effectiveDomainOf(metric))
        // 20 of a 0..100 range, and smaller is better, so the value is 1 - 0.2.
        assertEquals(0.8, model.valuesByAlternative("A")[metric]!!, 1.0e-12)
    }

    // ------------------------------------------------------------------------------------------
    // The fitted domain is arrived at the same way it always was
    // ------------------------------------------------------------------------------------------

    /**
     *  Where the domain is fitted, it must be fitted to exactly the same interval as before, since
     *  moving it would move every value computed from it. Comparing against the range estimate
     *  directly pins the arithmetic regardless of what data a fixture happens to use.
     */
    @Test
    fun `a fitted domain is the same range estimate as before`() {
        val metric = Metric("Cost", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        val scores = listOf(20.0, 35.0, 60.0, 75.0)
        model.defineAlternatives(
            scores.withIndex().associate { (i, score) -> "Alt$i" to listOf(Score(metric, score)) }
        )
        val expected = PDFModeler.rangeEstimate(scores.min(), scores.max(), scores.size)
        assertEquals(expected, model.effectiveDomainOf(metric), "the fitted domain is no longer the range estimate")
    }

    @Test
    fun `fitting only the upper limit keeps the declared lower limit and the estimated upper limit`() {
        val metric = Metric("Cost", Interval(0.0, 100.0), allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = true)
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        val scores = listOf(20.0, 35.0, 60.0, 75.0)
        model.defineAlternatives(
            scores.withIndex().associate { (i, score) -> "Alt$i" to listOf(Score(metric, score)) }
        )
        val estimate = PDFModeler.rangeEstimate(scores.min(), scores.max(), scores.size)
        assertEquals(
            Interval(0.0, estimate.upperLimit), model.effectiveDomainOf(metric),
            "a one-sided fit no longer combines the declared limit with the estimated one"
        )
    }

    /**
     *  With only two scores the range estimate does not apply, and the limits are rounded outward
     *  instead. That path is kept as it was.
     */
    @Test
    fun `two differing scores round the domain outward as before`() {
        val metric = Metric("Cost", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf("A" to listOf(Score(metric, 20.4)), "B" to listOf(Score(metric, 60.7)))
        )
        assertEquals(Interval(20.0, 61.0), model.effectiveDomainOf(metric))
    }

    // ------------------------------------------------------------------------------------------
    // One domain per metric, never a mixture
    // ------------------------------------------------------------------------------------------

    @Test
    fun `adjusting one limit keeps the other at its declared value`() {
        val lowerFixed = Metric("Cost", Interval(0.0, 100.0), allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = true)
        val model = AdditiveMODAModel(mapOf(lowerFixed to LinearValueFunction()))
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(lowerFixed, 20.0)),
                "B" to listOf(Score(lowerFixed, 40.0)),
                "C" to listOf(Score(lowerFixed, 60.0))
            )
        )
        val effective = model.effectiveDomainOf(lowerFixed)
        assertEquals(0.0, effective.lowerLimit, "the limit that may not be adjusted was adjusted")
        assertTrue(effective.upperLimit < 100.0, "the limit that may be adjusted was not fitted")
        assertTrue(effective.upperLimit >= 60.0, "the fitted domain excludes a realized score")
    }

    /**
     *  When a proposed domain cannot hold every realized score, it is not applied to any of them.
     *  Applying it to only those it fits would evaluate one metric against two domains within a
     *  single study, and the values would not be comparable.
     *
     *  Reaching this needs a metric that reports a domain narrower than the scores already created
     *  against it, together with a refusal to adjust the limit that would have to move. A metric
     *  whose domain never changes cannot get here, which is why the case is built explicitly.
     */
    @Test
    fun `a domain that excludes a realized score is not applied at all`() {
        val metric = MutableDomainMetric("Cost", Interval(0.0, 100.0), adjustLower = true, adjustUpper = false)
        val scores = mapOf(
            "A" to listOf(Score(metric, 10.0)),
            "B" to listOf(Score(metric, 50.0)),
            "C" to listOf(Score(metric, 90.0))
        )
        // Narrow the declared domain after the scores exist. The upper limit may not be adjusted,
        // so any proposed domain is capped at 60 and cannot contain the score of 90.
        metric.declared = Interval(0.0, 60.0)
        val model = AdditiveMODAModel(mapOf<MetricIfc, ValueFunctionIfc>(metric to LinearValueFunction()))
        model.defineAlternatives(scores)

        assertFalse(model.wasRescaled(metric), "a domain that excludes a score was applied")
        assertEquals(Interval(0.0, 60.0), model.effectiveDomainOf(metric), "the declared domain was not kept")
        val warning = model.warnings.filterIsInstance<ModaWarning.DomainNotApplied>().singleOrNull()
        assertNotNull(warning, "no warning explained why the domain was not applied")
        assertEquals("Cost", warning.metric)
    }

    // ------------------------------------------------------------------------------------------
    // Tied scores, across every combination of the adjustment flags
    // ------------------------------------------------------------------------------------------

    /**
     *  Every alternative scoring the same is ordinary rather than exceptional. Whatever the metric
     *  permits, the outcome has to be a real number: it used to be either a thrown exception or a
     *  value that was not a number, depending only on how many alternatives there were.
     */
    @Test
    fun `tied scores always produce a finite value whatever the metric permits`() {
        for (count in listOf(1, 2, 3, 5)) {
            for (score in listOf(0.0, 7.0, 7.5, 42.0)) {
                for (adjustLower in listOf(true, false)) {
                    for (adjustUpper in listOf(true, false)) {
                        val metric = Metric("Tied", Interval(0.0, 100.0), adjustLower, adjustUpper)
                        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
                        val alternatives = (1..count).associate { "Alt$it" to listOf(Score(metric, score)) }
                        model.defineAlternatives(alternatives)
                        val case = "count=$count, score=$score, adjustLower=$adjustLower, adjustUpper=$adjustUpper"
                        for (alternative in model.alternatives) {
                            val value = model.multiObjectiveValue(alternative)
                            assertTrue(value.isFinite(), "value was not finite for $case")
                            assertTrue(value in 0.0..1.0, "value $value outside [0,1] for $case")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `tied scores leave every alternative equal so the ranking is decided elsewhere`() {
        val tied = Metric("Tied", Interval(0.0, 100.0))
        val deciding = Metric("Deciding", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(
            mapOf(tied to LinearValueFunction(), deciding to LinearValueFunction())
        )
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(tied, 5.0), Score(deciding, 10.0)),
                "B" to listOf(Score(tied, 5.0), Score(deciding, 50.0)),
                "C" to listOf(Score(tied, 5.0), Score(deciding, 90.0))
            )
        )
        val onTied = model.alternatives.map { model.valuesByAlternative(it)[tied]!! }
        assertTrue(onTied.all { it == 0.5 }, "a metric everything ties on did not give every alternative the same value")
        // Smaller is better on the deciding metric, so A is best and C is worst.
        val ordered = model.alternatives.sortedByDescending { model.multiObjectiveValue(it) }
        assertContentEquals(listOf("A", "B", "C"), ordered, "the deciding metric did not determine the order")
    }

    @Test
    fun `scores differing only by floating point noise count as tied`() {
        val metric = Metric("Noisy", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf(
                "A" to listOf(Score(metric, 7.0)),
                "B" to listOf(Score(metric, 7.0 + 1.0e-15)),
                "C" to listOf(Score(metric, 7.0 - 1.0e-15))
            )
        )
        assertTrue(
            model.warnings.any { it is ModaWarning.TiedScores },
            "scores differing only by floating point noise were not treated as tied"
        )
        for (alternative in model.alternatives) {
            assertTrue(model.multiObjectiveValue(alternative).isFinite(), "a near-tied score gave a non-finite value")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Repeated definition converges
    // ------------------------------------------------------------------------------------------

    /**
     *  Fitting the domain used to narrow the caller's metric, so defining alternatives a second
     *  time refitted against the already-narrowed domain and narrowed it again. Building the same
     *  set of alternatives in stages now ends up where supplying them all at once does.
     */
    @Test
    fun `defining alternatives in stages gives the same result as defining them at once`() {
        fun scoresFor(metric: MetricIfc, names: List<String>) =
            names.withIndex().associate { (i, name) -> name to listOf(Score(metric, 10.0 + 20.0 * i)) }

        val all = listOf("A", "B", "C", "D")

        val atOnceMetric = Metric("Cost", Interval(0.0, 100.0))
        val atOnce = AdditiveMODAModel(mapOf(atOnceMetric to LinearValueFunction()))
        atOnce.defineAlternatives(scoresFor(atOnceMetric, all))

        val inStagesMetric = Metric("Cost", Interval(0.0, 100.0))
        val inStages = AdditiveMODAModel(mapOf(inStagesMetric to LinearValueFunction()))
        inStages.defineAlternatives(scoresFor(inStagesMetric, all.take(2)))
        inStages.defineAlternatives(scoresFor(inStagesMetric, all).filterKeys { it !in all.take(2) })

        assertEquals(
            atOnce.effectiveDomainOf(atOnceMetric), inStages.effectiveDomainOf(inStagesMetric),
            "building the alternatives in stages produced a different domain"
        )
        for (alternative in all) {
            assertEquals(
                atOnce.multiObjectiveValue(alternative), inStages.multiObjectiveValue(alternative), 1.0e-12,
                "alternative $alternative valued differently when built in stages"
            )
        }
    }

    @Test
    fun `defining the same alternatives repeatedly does not keep narrowing the domain`() {
        val metric = Metric("Cost", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        val scores = mapOf(
            "A" to listOf(Score(metric, 20.0)),
            "B" to listOf(Score(metric, 40.0)),
            "C" to listOf(Score(metric, 60.0))
        )
        model.defineAlternatives(scores)
        val first = model.effectiveDomainOf(metric)
        repeat(3) { model.defineAlternatives(scores) }
        assertEquals(first, model.effectiveDomainOf(metric), "repeating the definition kept moving the domain")
    }

    @Test
    fun `redefining the metrics discards a domain fitted to the previous ones`() {
        val original = Metric("Cost", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(original to LinearValueFunction()))
        model.defineAlternatives(
            mapOf("A" to listOf(Score(original, 20.0)), "B" to listOf(Score(original, 60.0)))
        )
        assertTrue(model.wasRescaled(original), "the setup did not actually fit a domain")

        val replacement = Metric("Cost", Interval(0.0, 100.0))
        model.defineMetrics(mapOf(replacement to LinearValueFunction()))
        assertFalse(model.wasRescaled(replacement), "a domain fitted to the previous metrics was left behind")
        assertEquals(Interval(0.0, 100.0), model.effectiveDomainOf(replacement))
        assertTrue(model.warnings.isEmpty(), "warnings about the previous metrics were left behind")
    }

    @Test
    fun `clearing the alternatives discards the domain fitted to them`() {
        val metric = Metric("Cost", Interval(0.0, 100.0))
        val model = AdditiveMODAModel(mapOf(metric to LinearValueFunction()))
        model.defineAlternatives(
            mapOf("A" to listOf(Score(metric, 20.0)), "B" to listOf(Score(metric, 60.0)))
        )
        assertTrue(model.wasRescaled(metric), "the setup did not actually fit a domain")
        model.clearAlternatives()
        assertFalse(model.wasRescaled(metric), "the domain fitted to the cleared alternatives was kept")
        assertEquals(Interval(0.0, 100.0), model.effectiveDomainOf(metric))
    }

    // ------------------------------------------------------------------------------------------
    // Metric implementations from outside this library
    // ------------------------------------------------------------------------------------------

    /**
     *  Fitting a domain must not require anything of a metric beyond the interface as it already
     *  stands, so that implementations outside this library keep working without being changed.
     *  [MutableDomainMetric] implements the interface directly and adds no member for this purpose.
     */
    @Test
    fun `a metric implemented outside this library is fitted without any new member`() {
        val metric = MutableDomainMetric("Throughput", Interval(0.0, 1000.0), adjustLower = true, adjustUpper = true)
        val model = AdditiveMODAModel(mapOf<MetricIfc, ValueFunctionIfc>(metric to LinearValueFunction()))
        val scores = listOf(100.0, 150.0, 200.0, 250.0, 300.0)
        model.defineAlternatives(
            scores.withIndex().associate { (i, score) -> "Alt$i" to listOf(Score(metric, score)) }
        )
        assertTrue(model.wasRescaled(metric), "a metric from outside this library was not fitted")
        val effective = model.effectiveDomainOf(metric)
        assertTrue(effective.upperLimit < 1000.0, "the domain was not narrowed towards the realized scores")
        assertTrue(effective.lowerLimit > 0.0, "the domain was not narrowed towards the realized scores")
        assertTrue(scores.all { effective.contains(it) }, "the fitted domain excludes a realized score")
        assertEquals(Interval(0.0, 1000.0), metric.declared, "the caller's own metric was modified")
        for (alternative in model.alternatives) {
            val value = model.multiObjectiveValue(alternative)
            assertTrue(value.isFinite() && value in 0.0..1.0, "value $value out of contract for $alternative")
        }
    }

    /**
     *  A metric implemented against the interface directly rather than by extending [Metric], with a
     *  domain the test can change. Standing outside the library's own class is the point: it shows
     *  the model asks nothing of a metric that the published interface does not already offer.
     */
    private class MutableDomainMetric(
        override val name: String,
        var declared: Interval,
        private val adjustLower: Boolean,
        private val adjustUpper: Boolean
    ) : MetricIfc {
        override val domain: Interval
            get() = declared
        override val direction: MetricIfc.Direction = MetricIfc.Direction.SmallerIsBetter
        override val unitsOfMeasure: String? = null
        override val description: String? = null
        override val allowLowerLimitAdjustment: Boolean
            get() = adjustLower
        override val allowUpperLimitAdjustment: Boolean
            get() = adjustUpper

        override fun newInstance(): MetricIfc =
            MutableDomainMetric(name, Interval(declared.lowerLimit, declared.upperLimit), adjustLower, adjustUpper)
    }
}
