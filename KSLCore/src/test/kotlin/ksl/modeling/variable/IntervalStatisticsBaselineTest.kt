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
 *  A baseline for the interval-collection paths that are already exact.
 *
 *  `ResponseInterval` and `TimeSeriesResponse` compute an interval average by snapshotting a
 *  response's within-replication statistic at the interval's start and differencing it at the end.
 *  For a `TWResponse` that is wrong, because a time-weighted variable banks the area of a segment
 *  only when the *next* value arrives, so a read taken at an arbitrary instant lags by everything
 *  accrued since the last change. Fixing that means changing what both classes read at both
 *  boundaries.
 *
 *  Three paths through that same code are exact today and must stay exact:
 *
 *  - a `TWResponse` that does not change inside the interval, which takes the no-observations branch;
 *  - a `Counter`, which differences a live value with nothing in flight;
 *  - a plain `Response`, which collects each observation immediately with weight one.
 *
 *  These tests therefore pass **before** the fix as well as after. A test that only passes afterwards
 *  is a specification, not a baseline, and belongs with the fix instead.
 *
 *  Deliberately absent: any interval in which a `TWResponse` changes, and any interval straddling a
 *  warm-up. Those are the defect, and their numbers are supposed to change.
 */
class IntervalStatisticsBaselineTest {

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

    /** A `Counter` incremented once per unit time, starting at t = 1. */
    private class Ticker(parent: ModelElement, private val until: Double) : ModelElement(parent) {
        val count = Counter(this, name = "Ticks")

        override fun initialize() {
            var t = 1.0
            while (t <= until) {
                schedule(::tick, t)
                t += 1.0
            }
        }

        private fun tick(event: KSLEvent<Nothing>) {
            count.increment()
        }
    }

    /** A plain (observation-based) `Response` given a scripted list of (time, value) observations. */
    private class Sampler(
        parent: ModelElement,
        private val observations: List<Pair<Double, Double>>,
    ) : ModelElement(parent) {
        val reading = Response(this, name = "Reading")

        override fun initialize() {
            for ((t, v) in observations) schedule(::observe, t, message = v)
        }

        private fun observe(event: KSLEvent<Double>) {
            reading.value = event.message!!
        }
    }

    /** The single value an interval response observed, for a one-replication run. */
    private fun observedValue(r: Response): Double = r.withinReplicationStatistic.weightedAverage

    private fun observationCount(r: Response): Double = r.withinReplicationStatistic.count

    // ── A TWResponse that does not change inside the interval ───────────────

    /**
     *  Level 2.0, becoming 5.0 at t = 15, over the interval [30, 40]. Nothing changes inside, so the
     *  no-observations branch reports the current height, which is exactly the time-average of a
     *  constant.
     */
    @Test
    @DisplayName("Baseline: a TWResponse constant across the interval reports its level")
    fun constantTWResponseOverIntervalIsExact() {
        val model = Model("baselineConstantTW")
        val tank = Tank(model, listOf(15.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 30.0
        val avg = interval.addResponseToInterval(tank.level)
        model.numberOfReplications = 1
        model.lengthOfReplication = 45.0
        model.simulate()
        assertEquals(1.0, observationCount(avg), 0.0, "the interval occurs exactly once")
        assertEquals(5.0, observedValue(avg), 1e-9, "constant height 5.0 across [30,40]")
    }

    // ── A Counter over an interval ──────────────────────────────────────────

    /**
     *  One increment per unit time. Over [10, 20] that is exactly ten increments. A `Counter` has
     *  nothing in flight, so differencing its live value is exact — which locates the defect as
     *  being about time-weighted accumulation rather than about differencing across a boundary.
     */
    @Test
    @DisplayName("Baseline: a Counter over an interval reports the exact interval count")
    fun counterOverIntervalIsExact() {
        val model = Model("baselineCounter")
        val ticker = Ticker(model, until = 25.0)
        val interval = ResponseInterval(ticker, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val count = interval.addCounterToInterval(ticker.count)
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        assertEquals(1.0, observationCount(count), 0.0)
        assertEquals(10.0, observedValue(count), 1e-9, "ten increments land inside [10,20]")
    }

    // ── A plain Response over an interval ───────────────────────────────────

    /**
     *  `Response.assignValue` collects immediately with weight one, so nothing is ever in flight and
     *  the differenced sum and count are exactly those of the observations inside the window.
     */
    @Test
    @DisplayName("Baseline: a plain Response over an interval averages the observations inside it")
    fun plainResponseOverIntervalIsExact() {
        val model = Model("baselinePlainResponse")
        val sampler = Sampler(model, listOf(12.0 to 1.0, 14.0 to 2.0, 16.0 to 3.0, 18.0 to 4.0))
        val interval = ResponseInterval(sampler, theDuration = 10.0, label = "Window")
        interval.startTime = 10.0
        val avg = interval.addResponseToInterval(sampler.reading)
        model.numberOfReplications = 1
        model.lengthOfReplication = 25.0
        model.simulate()
        assertEquals(1.0, observationCount(avg), 0.0)
        assertEquals(2.5, observedValue(avg), 1e-9, "(1 + 2 + 3 + 4) / 4")
    }

    /**
     *  With no observations inside the window there is nothing to average, and a plain `Response`
     *  has no height to fall back on — unlike a `TWResponse`. The interval therefore reports nothing
     *  at all, which the fix must not change.
     */
    @Test
    @DisplayName("Baseline: a plain Response with no observations inside the interval reports nothing")
    fun plainResponseWithNoObservationsReportsNothing() {
        val model = Model("baselinePlainResponseEmpty")
        val sampler = Sampler(model, listOf(12.0 to 1.0, 14.0 to 2.0))
        val interval = ResponseInterval(sampler, theDuration = 10.0, label = "Window")
        interval.startTime = 30.0
        val avg = interval.addResponseToInterval(sampler.reading)
        model.numberOfReplications = 1
        model.lengthOfReplication = 45.0
        model.simulate()
        assertEquals(0.0, observationCount(avg), 0.0, "no observation is made for an empty interval")
    }

    // ── TimeSeriesResponse, on periods where it is already exact ────────────

    /**
     *  The same constant-height reasoning, through the other implementation. Periods 1 and 4 contain
     *  no change; period 2 does, and is deliberately not asserted here — it is the defect.
     */
    @Test
    @DisplayName("Baseline: TimeSeriesResponse is exact for periods containing no change")
    fun timeSeriesIsExactForConstantPeriods() {
        val model = Model("baselineTimeSeries")
        val tank = Tank(model, listOf(15.0 to 5.0), initial = 2.0, name = "T")
        val series = TimeSeriesResponse(
            tank, periodLength = 10.0, numPeriods = 4, responses = setOf(tank.level)
        )
        model.numberOfReplications = 1
        model.lengthOfReplication = 45.0
        model.simulate()
        val periods = series.responsePeriodDataAsList(tank.level).associateBy { it.period }
        assertEquals(2.0, periods[1]?.value ?: Double.NaN, 1e-9, "level is 2.0 across [0,10]")
        assertEquals(5.0, periods[4]?.value ?: Double.NaN, 1e-9, "level is 5.0 across [30,40]")
    }
}
