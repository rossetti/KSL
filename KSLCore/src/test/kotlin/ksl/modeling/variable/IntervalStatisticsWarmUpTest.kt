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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A warm-up resets each response's within-replication statistic and each counter's count. An
 *  interval that is already in progress snapshotted those quantities at its start, so after the
 *  reset it is differencing against something that no longer exists.
 *
 *  Before this was handled, the reported figures were not merely imprecise. A `TWResponse` interval
 *  could difference its observation count to exactly zero and take the no-observations branch,
 *  reporting the current height as though the variable had been constant across a window in which it
 *  demonstrably changed. A `Counter` interval could report a **negative** count — the one symptom in
 *  this whole defect that is impossible on its face.
 *
 *  Such an interval spans data the run is meant to forget, so there is no honest value to report and
 *  it is discarded: the interval observes nothing, and a time-series period records a null value.
 *
 *  The flag is set by `warmUp()` and cleared when an interval starts, which makes the outcome
 *  independent of the order in which model elements receive the warm-up. The last two tests pin the
 *  ordering cases.
 */
class IntervalStatisticsWarmUpTest {

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

    // ── The two cases from the issue report ─────────────────────────────────

    /**
     *  Case A: a change before the interval and a change inside it. Level 2.0, becoming 3.0 at
     *  t = 10 and 5.0 at t = 27, interval [20, 30], warm-up at t = 25.
     *
     *  Before the fix the reset drove the differenced observation count to exactly zero, so the
     *  no-observations branch reported 5.0 — the height at the end — for a window whose true average
     *  is 3.6 and in which the level plainly changed.
     */
    @Test
    @DisplayName("A warm-up inside the interval discards it: change before and inside")
    fun warmUpWithPriorChangeIsDiscarded() {
        val model = Model("warmUpCaseA")
        val tank = Tank(model, listOf(10.0 to 3.0, 27.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 20.0
        val avg = interval.addResponseToInterval(tank.level, intervalEmptyStatOption = true)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 25.0
        model.simulate()
        assertEquals(
            0.0, avg.withinReplicationStatistic.count, 0.0,
            "the straddling interval must observe nothing; it reported 5.0 for a true 3.6 before",
        )
    }

    /**
     *  Case B: no change before the interval, one inside it. Level 2.0 becoming 5.0 at t = 27.
     *  Before the fix the general branch ran and reported 2.0 — the average over [25, 27] — for a
     *  window whose true average is 2.9.
     */
    @Test
    @DisplayName("A warm-up inside the interval discards it: change only inside")
    fun warmUpWithoutPriorChangeIsDiscarded() {
        val model = Model("warmUpCaseB")
        val tank = Tank(model, listOf(27.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 20.0
        val avg = interval.addResponseToInterval(tank.level)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 25.0
        model.simulate()
        assertEquals(0.0, avg.withinReplicationStatistic.count, 0.0, "reported 2.0 for a true 2.9 before")
    }

    /**
     *  The empty flag is derived from the same differenced observation count, so it is wrong for the
     *  same reason. Discarding the average without discarding the flag would report "this interval
     *  was empty" for an interval in which the variable changed.
     */
    @Test
    @DisplayName("A discarded interval does not report an empty-interval flag either")
    fun discardedIntervalReportsNoEmptyFlag() {
        val model = Model("warmUpEmptyFlag")
        val tank = Tank(model, listOf(10.0 to 3.0, 27.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 20.0
        interval.addResponseToInterval(tank.level, intervalEmptyStatOption = true)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 25.0
        model.simulate()
        val empty = model.responses.firstOrNull { it.name.endsWith("P(Empty)") }
        val flag = assertNotNull(empty, "the empty-interval response should exist")
        assertEquals(
            0.0, flag.withinReplicationStatistic.count, 0.0,
            "the flag is computed from the same count and must be discarded with the average",
        )
    }

    // ── The counter case found in Phase 0 ───────────────────────────────────

    /**
     *  `Counter.warmUp()` resets the count, so an interval straddling it differenced 5 against a
     *  start snapshot of 20 and reported **-15.0** for a true count of 10.
     */
    @Test
    @DisplayName("A warm-up inside the interval discards a counter rather than reporting a negative count")
    fun warmUpDiscardsCounterInterval() {
        val model = Model("warmUpCounter")
        val ticker = Ticker(model, until = 50.0)
        val interval = ResponseInterval(ticker, theDuration = 10.0, label = "Window")
        interval.startTime = 20.0
        val count = interval.addCounterToInterval(ticker.count)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 25.0
        model.simulate()
        assertEquals(
            0.0, count.withinReplicationStatistic.count, 0.0,
            "the straddling interval must observe nothing; it reported -15.0 before",
        )
    }

    // ── TimeSeriesResponse, the other implementation ────────────────────────

    /**
     *  Periods are back to back, so exactly one straddles the warm-up. That one records a null value;
     *  the periods on either side are unaffected and keep reporting.
     */
    @Test
    @DisplayName("The time-series period straddling a warm-up records a null value; its neighbours do not")
    fun timeSeriesDiscardsOnlyTheStraddlingPeriod() {
        val model = Model("warmUpTimeSeries")
        val tank = Tank(model, listOf(27.0 to 5.0), initial = 2.0, name = "T")
        val series = TimeSeriesResponse(
            tank, periodLength = 10.0, numPeriods = 5, responses = setOf(tank.level)
        )
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 25.0
        model.simulate()
        val periods = series.responsePeriodDataAsList(tank.level).associateBy { it.period }
        assertNotNull(periods[2]?.value, "[10,20] ends before the warm-up and is unaffected")
        assertNull(periods[3]?.value, "[20,30] straddles the warm-up at 25 and must be discarded")
        assertNotNull(periods[4]?.value, "[30,40] begins after the warm-up and is unaffected")
    }

    @Test
    @DisplayName("A time-series counter period straddling a warm-up records a null value")
    fun timeSeriesDiscardsStraddlingCounterPeriod() {
        val model = Model("warmUpTimeSeriesCounter")
        val ticker = Ticker(model, until = 50.0)
        val series = TimeSeriesResponse(
            ticker, periodLength = 10.0, numPeriods = 5, counters = setOf(ticker.count)
        )
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 25.0
        model.simulate()
        val periods = series.counterPeriodDataAsList(ticker.count).associateBy { it.period }
        assertNotNull(periods[2]?.value, "[10,20] ends before the warm-up")
        assertNull(periods[3]?.value, "[20,30] straddles the warm-up and must be discarded")
        assertEquals(10.0, periods[4]?.value ?: Double.NaN, 1e-9, "[30,40] counts ten ticks as usual")
    }

    // ── Ordering: the flag must not over- or under-fire ─────────────────────

    /**
     *  A warm-up exactly at an interval boundary is the case where model-element ordering could
     *  decide the answer. Warm-up at t = 20, interval [20, 30]: whichever runs first, the interval
     *  collects entirely after the reset and there is nothing to discard.
     */
    @Test
    @DisplayName("A warm-up exactly at the interval start does not discard the interval")
    fun warmUpAtIntervalStartDoesNotDiscard() {
        val model = Model("warmUpAtStart")
        val tank = Tank(model, listOf(27.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 20.0
        val avg = interval.addResponseToInterval(tank.level)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 20.0
        model.simulate()
        assertEquals(1.0, avg.withinReplicationStatistic.count, 0.0, "the interval must still report")
        assertEquals(2.9, avg.withinReplicationStatistic.weightedAverage, 1e-9, "(2.0 x 7 + 5.0 x 3) / 10")
    }

    /**
     *  A warm-up strictly before the interval must not leave the flag set and silently discard the
     *  next interval. The flag is cleared when an interval starts, which this pins.
     */
    @Test
    @DisplayName("A warm-up before the interval leaves the following interval reporting")
    fun warmUpBeforeIntervalDoesNotDiscardIt() {
        val model = Model("warmUpBefore")
        val tank = Tank(model, listOf(27.0 to 5.0), initial = 2.0, name = "T")
        val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
        interval.startTime = 20.0
        val avg = interval.addResponseToInterval(tank.level)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 5.0
        model.simulate()
        assertEquals(1.0, avg.withinReplicationStatistic.count, 0.0, "a stale flag would discard this")
        assertEquals(2.9, avg.withinReplicationStatistic.weightedAverage, 1e-9, "(2.0 x 7 + 5.0 x 3) / 10")
    }

    // ── The warning ─────────────────────────────────────────────────────────

    /**
     *  A discarded interval is silent in the output — the interval simply observes nothing, which is
     *  indistinguishable from an interval that never ran. A log warning is therefore the only signal
     *  a modeller gets, so it must actually fire.
     *
     *  Captured by attaching an appender to the logger rather than by inspecting statistics, because
     *  the whole point is that the statistics show nothing.
     */
    private fun captureWarnings(block: () -> Unit): List<String> {
        val logger = org.slf4j.LoggerFactory.getLogger(Model::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
            .filter { it.level == ch.qos.logback.classic.Level.WARN }
            .map { it.formattedMessage }
    }

    @Test
    @DisplayName("A discarded interval logs a warning, once, naming the interval")
    fun discardedIntervalWarnsOnce() {
        val warnings = captureWarnings {
            val model = Model("warnDiscard")
            val tank = Tank(model, listOf(27.0 to 5.0), initial = 2.0, name = "T")
            val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
            interval.startTime = 20.0
            interval.addResponseToInterval(tank.level)
            model.numberOfReplications = 3
            model.lengthOfReplication = 50.0
            model.lengthOfReplicationWarmUp = 25.0
            model.simulate()
        }
        val discardWarnings = warnings.filter { it.contains("is discarded") }
        assertEquals(
            1, discardWarnings.size,
            "three replications each discard the interval, but it is reported once: $discardWarnings",
        )
        assertTrue(discardWarnings.first().contains("Window"), "the warning should name the interval")
    }

    @Test
    @DisplayName("A warm-up on an interval boundary logs no discard warning")
    fun boundaryWarmUpDoesNotWarn() {
        val warnings = captureWarnings {
            val model = Model("warnNoDiscard")
            val tank = Tank(model, listOf(27.0 to 5.0), initial = 2.0, name = "T")
            val interval = ResponseInterval(tank, theDuration = 10.0, label = "Window")
            interval.startTime = 20.0
            interval.addResponseToInterval(tank.level)
            model.numberOfReplications = 3
            model.lengthOfReplication = 50.0
            model.lengthOfReplicationWarmUp = 20.0
            model.simulate()
        }
        assertEquals(
            0, warnings.count { it.contains("is discarded") },
            "nothing straddles a warm-up that lands on the boundary, so nothing is discarded",
        )
    }
}
