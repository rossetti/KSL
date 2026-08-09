/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.variable

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A weighted statistic collects a value once, with one final weight, and cannot afterwards revise
 *  it. A `TWResponse` segment is therefore not collected until the next assignment fixes its width,
 *  so the statistic, read at an instant that is not a change instant, is missing the segment in
 *  progress.
 *
 *  `ResponseInterval` and `TimeSeriesResponse` measured an interval by snapshotting that statistic at
 *  the start and differencing it at the end. Both boundaries lag, so the two reads bracketed the
 *  window running from the last change before the start to the last change before the end — not the
 *  requested window. Both numerator and denominator were wrong, so the reported figure was not even
 *  an average over some subinterval of what was asked for.
 *
 *  The behavioural tests here all failed before the fix. `bothImplementationsAgree` is the exception
 *  and passes either way, which is the point of it: the two classes are independent copies of one
 *  algorithm and agreed with each other *while both were wrong*. It fails only if one is fixed and
 *  the other is not.
 */
class IntervalStatisticsWindowTest {

    /** A `TWResponse` driven by a scripted list of (time, value) changes. */
    private class Tank(
        parent: ModelElement,
        private val changes: List<Pair<Double, Double>>,
        initial: Double,
        name: String? = null,
    ) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "Level", initialValue = initial)

        override fun initialize() {
            for ((t, v) in changes) schedule(::change, t, message = v)
        }

        private fun change(event: KSLEvent<Double>) {
            level.value = event.message!!
        }
    }

    private fun observedValue(r: Response): Double = r.withinReplicationStatistic.weightedAverage

    // ── One change strictly inside the interval ─────────────────────────────

    /**
     *  Level 2.0 becoming 5.0 at t = 15, over 10 to 20. The true time-average is
     *  (2.0 x 5 + 5.0 x 5) / 10 = 3.5.
     *
     *  Before the fix both classes reported 2.0 — the average over 0 to 15, because the last change
     *  at or before t = 10 was at t = 0 and the last at or before t = 20 was at t = 15. The
     *  denominator was 15 for a window 10 wide.
     */
    @Test
    @DisplayName("A change inside the interval: ResponseInterval reports the true time-average")
    fun responseIntervalHandlesChangeInsideWindow() {
        val model = Model("windowResponseInterval")
        val tank = Tank(model, listOf(15.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val avg = interval.addResponseToInterval(tank.level)
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        assertEquals(1.0, avg.withinReplicationStatistic.count, 0.0, "the interval occurs once")
        assertEquals(3.5, observedValue(avg), 1e-9, "(2.0 x 5 + 5.0 x 5) / 10; was 2.0 before the fix")
    }

    @Test
    @DisplayName("A change inside the period: TimeSeriesResponse reports the true time-average")
    fun timeSeriesHandlesChangeInsidePeriod() {
        val model = Model("windowTimeSeries")
        val tank = Tank(model, listOf(15.0 to 5.0), initial = 2.0, name = "T")
        val series = TimeSeriesResponse(
            tank, periodLength = 10.0, numPeriods = 2, responses = setOf(tank.level)
        )
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        val periods = series.responsePeriodDataAsList(tank.level).associateBy { it.period }
        assertEquals(2.0, periods[1]?.value ?: Double.NaN, 1e-9, "level is 2.0 across [0,10]")
        assertEquals(3.5, periods[2]?.value ?: Double.NaN, 1e-9, "[10,20]; was 2.0 before the fix")
    }

    /**
     *  The two classes are independent copies of the same algorithm. They agreed with each other
     *  while both were wrong; they must still agree now that both are right, or a fix has been
     *  applied to one and not the other.
     */
    @Test
    @DisplayName("ResponseInterval and TimeSeriesResponse agree on the same window")
    fun bothImplementationsAgree() {
        val model = Model("windowAgreement")
        val tank = Tank(model, listOf(15.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val avg = interval.addResponseToInterval(tank.level)
        val series = TimeSeriesResponse(
            tank, periodLength = 10.0, numPeriods = 2, responses = setOf(tank.level)
        )
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        val period2 = series.responsePeriodDataAsList(tank.level).first { it.period == 2 }.value
        assertEquals(
            observedValue(avg), period2 ?: Double.NaN, 1e-9,
            "the two implementations must not disagree about the same window",
        )
    }

    // ── Several changes inside the interval ─────────────────────────────────

    /**
     *  A window with more than one change, so the fix cannot be right by accident on a single
     *  segment. Level 1.0, becoming 2.0 at t = 12, 4.0 at t = 14, 8.0 at t = 18, over 10 to 20:
     *  (1 x 2 + 2 x 2 + 4 x 4 + 8 x 2) / 10 = 3.8.
     */
    @Test
    @DisplayName("Several changes inside the interval are weighted by their own durations")
    fun multipleChangesInsideWindow() {
        val model = Model("windowMultiChange")
        val tank = Tank(model, listOf(12.0 to 2.0, 14.0 to 4.0, 18.0 to 8.0), initial = 1.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val avg = interval.addResponseToInterval(tank.level)
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        assertEquals(3.8, observedValue(avg), 1e-9, "(1x2 + 2x2 + 4x4 + 8x2) / 10")
    }

    /**
     *  Changes on both sides of the window as well as inside it, so a boundary that reads the wrong
     *  side is caught. Level 1.0, becoming 2.0 at t = 5, 6.0 at t = 15, 9.0 at t = 25, over 10 to 20:
     *  (2 x 5 + 6 x 5) / 10 = 4.0.
     */
    @Test
    @DisplayName("Changes outside the interval do not leak into it")
    fun changesOutsideWindowAreExcluded() {
        val model = Model("windowNeighbours")
        val tank = Tank(model, listOf(5.0 to 2.0, 15.0 to 6.0, 25.0 to 9.0), initial = 1.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val avg = interval.addResponseToInterval(tank.level)
        model.numberOfReplications = 1
        model.lengthOfReplication = 35.0
        model.simulate()
        assertEquals(4.0, observedValue(avg), 1e-9, "(2 x 5 + 6 x 5) / 10")
    }

    // ── The accumulators themselves ─────────────────────────────────────────

    /**
     *  The property the fix rests on, asserted directly rather than only through its consumers. At
     *  any instant the elapsed weight of a time-weighted response is the elapsed time, and its
     *  weighted sum is the true area — neither of which is true of the underlying statistic between
     *  changes.
     */
    private class Prober(parent: ModelElement) : ModelElement(parent) {
        val level = TWResponse(this, name = "Level", initialValue = 2.0)
        var areaAt18 = Double.NaN
        var weightAt18 = Double.NaN
        var statSumAt18 = Double.NaN

        override fun initialize() {
            schedule(::raise, 15.0)
            schedule(::probe, 18.0)
        }

        private fun raise(event: KSLEvent<Nothing>) { level.value = 5.0 }

        private fun probe(event: KSLEvent<Nothing>) {
            areaAt18 = level.withinReplicationWeightedSum
            weightAt18 = level.withinReplicationSumOfWeights
            statSumAt18 = level.withinReplicationStatistic.weightedSum
        }
    }

    @Test
    @DisplayName("withinReplicationWeightedSum includes the segment in flight; the raw statistic does not")
    fun accumulatorsIncludeTheInFlightSegment() {
        val model = Model("accumulators")
        val prober = Prober(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        assertEquals(
            30.0, prober.statSumAt18, 1e-9,
            "the raw statistic holds only the banked area 2.0 x 15, nothing since the change at 15",
        )
        assertEquals(45.0, prober.areaAt18, 1e-9, "2.0 x 15 banked, plus 5.0 x 3 still in flight")
        assertEquals(18.0, prober.weightAt18, 1e-9, "elapsed weight equals elapsed time")
    }
}
