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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Tests for eliciting weights by the swing method.
 *
 *  Two things are being pinned. The first is that the steps cannot be taken out of order or left
 *  half done, since a rating means nothing before there is a top swing to rate against and weights
 *  mean nothing while swings are unrated. The second, and the more important, is that weights
 *  elicited against one set of ranges are detected as no longer applying if those ranges are later
 *  refitted, rather than being applied silently to ranges nobody was asked about.
 */
class SwingWeightElicitationTest {

    private fun metrics(
        adjustable: Boolean = true
    ): List<Metric> = listOf(
        Metric("Cost", Interval(0.0, 100.0), adjustable, adjustable),
        Metric("Delay", Interval(0.0, 60.0), adjustable, adjustable),
        Metric("Risk", Interval(0.0, 10.0), adjustable, adjustable)
    )

    private fun completed(metrics: List<Metric> = metrics()): SwingWeightElicitation {
        val elicitation = SwingWeightElicitation(metrics)
        elicitation.rankSwings(listOf("Cost", "Delay", "Risk"))
        elicitation.rateSwing("Delay", 50.0)
        elicitation.rateSwing("Risk", 25.0)
        return elicitation
    }

    // ------------------------------------------------------------------------------------------
    // The steps have to be taken in order
    // ------------------------------------------------------------------------------------------

    @Test
    fun `swings cannot be rated before they have been ranked`() {
        val elicitation = SwingWeightElicitation(metrics())
        assertFailsWith<IllegalStateException> { elicitation.rateSwing("Delay", 50.0) }
    }

    @Test
    fun `weights cannot be taken while swings are still unrated`() {
        val elicitation = SwingWeightElicitation(metrics())
        elicitation.rankSwings(listOf("Cost", "Delay", "Risk"))
        assertFalse(elicitation.isComplete)
        assertContentEquals(listOf("Delay", "Risk"), elicitation.missing())
        val error = assertFailsWith<IllegalStateException> { elicitation.weights() }
        assertTrue(error.message!!.contains("Delay"), "the error does not say what is still unrated")
    }

    @Test
    fun `ranking must cover every metric exactly once`() {
        val elicitation = SwingWeightElicitation(metrics())
        assertFailsWith<IllegalArgumentException> { elicitation.rankSwings(listOf("Cost", "Delay")) }
        assertFailsWith<IllegalArgumentException> { elicitation.rankSwings(listOf("Cost", "Delay", "Cost")) }
        assertFailsWith<IllegalArgumentException> { elicitation.rankSwings(listOf("Cost", "Delay", "Nope")) }
    }

    @Test
    fun `the top swing defines the scale and cannot be rated against itself`() {
        val elicitation = SwingWeightElicitation(metrics())
        elicitation.rankSwings(listOf("Cost", "Delay", "Risk"))
        assertEquals(SwingWeightElicitation.TOP_SWING_RATING, elicitation.currentRatings["Cost"])
        val error = assertFailsWith<IllegalArgumentException> { elicitation.rateSwing("Cost", 80.0) }
        assertTrue(error.message!!.contains("Cost"))
    }

    @Test
    fun `a rating outside the scale is refused`() {
        val elicitation = SwingWeightElicitation(metrics())
        elicitation.rankSwings(listOf("Cost", "Delay", "Risk"))
        assertFailsWith<IllegalArgumentException> { elicitation.rateSwing("Delay", -1.0) }
        assertFailsWith<IllegalArgumentException> { elicitation.rateSwing("Delay", 101.0) }
        assertFailsWith<IllegalArgumentException> { elicitation.rateSwing("Nope", 50.0) }
    }

    /**
     *  A rating is relative to whichever swing was top when it was given, so ranking again has to
     *  discard the ratings rather than keep answers to a question that is no longer the one asked.
     */
    @Test
    fun `ranking again starts the ratings over`() {
        val elicitation = completed()
        assertTrue(elicitation.isComplete)
        elicitation.rankSwings(listOf("Risk", "Cost", "Delay"))
        assertFalse(elicitation.isComplete)
        assertEquals(SwingWeightElicitation.TOP_SWING_RATING, elicitation.currentRatings["Risk"])
        assertContentEquals(listOf("Cost", "Delay"), elicitation.missing())
    }

    @Test
    fun `metrics sharing a name cannot be elicited for`() {
        val duplicated = listOf(Metric("Cost", Interval(0.0, 100.0)), Metric("Cost", Interval(0.0, 50.0)))
        assertFailsWith<IllegalArgumentException> { SwingWeightElicitation(duplicated) }
    }

    @Test
    fun `there must be something to elicit weights for`() {
        assertFailsWith<IllegalArgumentException> { SwingWeightElicitation(emptyList()) }
    }

    // ------------------------------------------------------------------------------------------
    // The weights that come out
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the ratings become weights in proportion, summing to one`() {
        val weights = completed().weights()
        // 100, 50 and 25 out of 175.
        assertEquals(100.0 / 175.0, weights["Cost"]!!, 1.0e-12)
        assertEquals(50.0 / 175.0, weights["Delay"]!!, 1.0e-12)
        assertEquals(25.0 / 175.0, weights["Risk"]!!, 1.0e-12)
        assertEquals(1.0, weights.values.sum(), 1.0e-12)
    }

    @Test
    fun `the most valuable swing gets the largest weight`() {
        val weights = completed().weights()
        assertEquals("Cost", weights.maxByOrNull { it.value }!!.key)
    }

    @Test
    fun `a swing rated zero is allowed and takes no weight`() {
        val elicitation = SwingWeightElicitation(metrics())
        elicitation.rankSwings(listOf("Cost", "Delay", "Risk"))
        elicitation.rateSwing("Delay", 0.0)
        elicitation.rateSwing("Risk", 0.0)
        val weights = elicitation.weights()
        assertEquals(1.0, weights["Cost"]!!, 1.0e-12)
        assertEquals(0.0, weights["Delay"]!!)
    }

    @Test
    fun `a single metric takes all of the weight`() {
        val elicitation = SwingWeightElicitation(listOf(Metric("Only", Interval(0.0, 100.0))))
        elicitation.rankSwings(listOf("Only"))
        assertTrue(elicitation.isComplete)
        assertEquals(1.0, elicitation.weights()["Only"]!!, 1.0e-12)
    }

    // ------------------------------------------------------------------------------------------
    // Weights stop applying when the ranges they were given against move
    // ------------------------------------------------------------------------------------------

    private fun studyOver(metricList: List<Metric>, rescale: Boolean): ModaSnapshot {
        val definitions: Map<MetricIfc, ValueFunctionIfc> =
            metricList.associate { metric -> metric as MetricIfc to LinearValueFunction() }
        val model = AdditiveMODAModel(definitions, name = "Study")
        model.defineAlternatives(
            mapOf(
                "A" to metricList.map { Score(it, it.domain.upperLimit * 0.2) },
                "B" to metricList.map { Score(it, it.domain.upperLimit * 0.5) },
                "C" to metricList.map { Score(it, it.domain.upperLimit * 0.8) }
            ),
            allowRescalingByMetrics = rescale
        )
        return ModaSnapshot.of(model)
    }

    /**
     *  This is the case the record exists for. A swing weight is tied to the range it was given
     *  against, so a study that refits its ranges to the realized scores is no longer the study the
     *  weights describe, even though every number the person gave is unchanged.
     */
    @Test
    fun `weights stop applying once the ranges are refitted`() {
        val metricList = metrics(adjustable = true)
        val record = completed(metricList).record()
        val snapshot = studyOver(metricList, rescale = true)

        assertFalse(record.isStillValidFor(snapshot), "refitted ranges did not invalidate the weights")
        val moved = record.rangesThatMoved(snapshot)
        assertContentEquals(listOf("Cost", "Delay", "Risk"), moved)
        val reason = record.invalidationReason(snapshot)
        assertNotNull(reason)
        assertTrue(reason.contains("Cost"), "the reason does not name the metrics whose ranges moved")
    }

    @Test
    fun `weights keep applying when the ranges are held as declared`() {
        val metricList = metrics(adjustable = false)
        val record = completed(metricList).record()
        val snapshot = studyOver(metricList, rescale = false)

        assertTrue(record.isStillValidFor(snapshot), "unchanged ranges should not invalidate the weights")
        assertTrue(record.rangesThatMoved(snapshot).isEmpty())
        assertNull(record.invalidationReason(snapshot))
    }

    /**
     *  A metric that permits no adjustment keeps its range even in a study that refits, so only the
     *  weights that were actually given against a range that moved should stop applying.
     */
    @Test
    fun `only the metrics whose ranges actually moved are reported`() {
        val metricList = listOf(
            Metric("Cost", Interval(0.0, 100.0), allowLowerLimitAdjustment = true, allowUpperLimitAdjustment = true),
            Metric("Delay", Interval(0.0, 60.0), allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = false),
            Metric("Risk", Interval(0.0, 10.0), allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = false)
        )
        val record = completed(metricList).record()
        val snapshot = studyOver(metricList, rescale = true)
        assertContentEquals(listOf("Cost"), record.rangesThatMoved(snapshot))
    }

    @Test
    fun `the record says which ranges could move before anything is run`() {
        val record = completed(metrics(adjustable = true)).record()
        assertContentEquals(listOf("Cost", "Delay", "Risk"), record.adjustableRanges)

        val fixed = completed(metrics(adjustable = false)).record()
        assertTrue(fixed.adjustableRanges.isEmpty(), "metrics that permit no adjustment are not at risk")
    }

    @Test
    fun `a metric missing from the study counts as a range that moved`() {
        val record = completed(metrics()).record()
        val other = listOf(Metric("Cost", Interval(0.0, 100.0), false, false))
        val snapshot = studyOver(other, rescale = false)
        assertContentEquals(listOf("Delay", "Risk"), record.rangesThatMoved(snapshot))
    }

    @Test
    fun `the record carries the same weights the elicitation gives`() {
        val elicitation = completed()
        assertEquals(elicitation.weights(), elicitation.record().weights())
    }

    @Test
    fun `an incomplete elicitation cannot be recorded`() {
        val elicitation = SwingWeightElicitation(metrics())
        elicitation.rankSwings(listOf("Cost", "Delay", "Risk"))
        assertFailsWith<IllegalStateException> { elicitation.record() }
    }

    @Test
    fun `the record keeps the ranges the answers were given against`() {
        val record = completed().record()
        assertEquals(ElicitedRange(0.0, 100.0), record.elicitedAgainst["Cost"])
        assertEquals(ElicitedRange(0.0, 60.0), record.elicitedAgainst["Delay"])
        assertContentEquals(listOf("Cost", "Delay", "Risk"), record.order)
    }
}
