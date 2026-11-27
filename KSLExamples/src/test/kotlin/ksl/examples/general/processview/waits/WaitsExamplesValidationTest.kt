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

package ksl.examples.general.processview.waits

import ksl.examples.book.chapter7.WalkInHealthClinic
import ksl.simulation.Model
import ksl.utilities.statistic.StatisticIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The waits tutorial's examples checked against answers found another way: the exact results of a queueing
 *  model, or the same system written another way. Each runs as its example's `main` runs, with the same
 *  settings and seeds, so a pass here is a pass for the numbers the tutorial reports.
 *
 *  An exact value is required to lie inside the 99% confidence interval of the estimate. The run is seeded,
 *  so the check is deterministic: if it ever fails, the model or the library changed, and the tutorial's
 *  numbers with it.
 */
class WaitsExamplesValidationTest {

    private fun run(reps: Int, length: Double, warmUp: Double = 0.0, build: (Model) -> Unit): Model {
        val m = Model("Validation${System.nanoTime()}", autoCSVReports = false)
        build(m)
        m.numberOfReplications = reps
        m.lengthOfReplication = length
        m.lengthOfReplicationWarmUp = warmUp
        m.simulate()
        return m
    }

    private fun statistics(m: Model): Map<String, StatisticIfc> =
        m.responses.associate { it.name to it.acrossReplicationStatistic } +
            m.counters.associate { it.name to it.acrossReplicationStatistic }

    private fun assertInside(label: String, stat: StatisticIfc, exact: Double) {
        val ci = stat.confidenceInterval(0.99)
        assertTrue(exact in ci, "$label: the exact value $exact is outside the 99% interval [${ci.lowerLimit}, ${ci.upperLimit}]")
    }

    private fun assertIdentical(a: Map<String, StatisticIfc>, b: Map<String, StatisticIfc>, names: Collection<String>) {
        for (name in names) {
            val x = a.getValue(name)
            val y = b.getValue(name)
            assertEquals(x.count, y.count, "$name: count")
            assertEquals(x.average, y.average, "$name: average")
            assertEquals(x.variance, y.variance, "$name: variance")
        }
    }

    @Test
    @DisplayName("impatient callers: abandonment, number waiting and wait agree with the exact M/M/c+M answers")
    fun impatientCallers() {
        val exact = ErlangA(lambda = 2.4, mu = 1.0 / 4.0, theta = 1.0 / 3.0, c = 10)
        lateinit var center: CallCenter
        run(20, 20_000.0, 2_000.0) { center = CallCenter(it, name = "CallCenter") }
        assertInside("probability of abandoning", center.probAbandon.acrossReplicationStatistic, exact.probAbandon)
        assertInside("mean number waiting", center.numWaiting.acrossReplicationStatistic, exact.meanNumberWaiting)
        assertInside("mean wait", center.timeWaiting.acrossReplicationStatistic, exact.meanWait)
    }

    @Test
    @DisplayName("impatient callers: Little's law holds with abandoned waits recorded, and not without them")
    fun littlesLaw() {
        lateinit var recorded: CallCenter
        lateinit var servedOnly: CallCenter
        run(20, 20_000.0, 2_000.0) { recorded = CallCenter(it, name = "CallCenter") }
        run(20, 20_000.0, 2_000.0) { servedOnly = CallCenter(it, recordRenegedWait = false, name = "CallCenter") }
        fun gap(c: CallCenter): Double {
            val l = c.numWaiting.acrossReplicationStatistic.average
            return abs(l - c.arrivalRate * c.timeWaiting.acrossReplicationStatistic.average) / l
        }
        assertTrue(gap(recorded) < 0.01, "with the abandoned waits recorded, L and lambda W differ by ${gap(recorded)}")
        assertTrue(gap(servedOnly) > 0.05, "without them, L and lambda W should differ, but differ by ${gap(servedOnly)}")
    }

    @Test
    @DisplayName("the walk-in clinic gives identical results reneging by seizeOrTimeout as by an event")
    fun walkInClinic() {
        val byEvent = statistics(run(30, 600.0) { WalkInHealthClinic(it, "Walk-In Clinic") })
        val byTimedSeize = statistics(run(30, 600.0) { WalkInHealthClinicViaTimedSeize(it, "Walk-In Clinic") })
        assertEquals(byEvent.keys, byTimedSeize.keys)
        assertTrue(byEvent.getValue("Walk-In Clinic:NumReneged").average > 0.0, "the clinic must renege for this to test anything")
        assertIdentical(byEvent, byTimedSeize, byEvent.keys)
    }

    @Test
    @DisplayName("first free of two: leaving and the number waiting agree with the exact M/M/2+M answers")
    fun firstFreeOfTwo() {
        val exact = ErlangA(lambda = 1.0 / 12.0, mu = 1.0 / 20.0, theta = 1.0 / 30.0, c = 2)
        lateinit var clinic: TwoProviderClinic
        run(20, 100_000.0, 10_000.0) { clinic = TwoProviderClinic(it, name = "Clinic") }
        assertInside("probability of leaving", clinic.probLeftUnseen.acrossReplicationStatistic, exact.probAbandon)
        assertInside("mean number waiting", clinic.numWaiting.acrossReplicationStatistic, exact.meanNumberWaiting)
    }

    @Test
    @DisplayName("the shuttle stop gives identical results with its riders in a queue, waiting on one signal, and written with events")
    fun shuttleStop() {
        val byQueue = statistics(run(20, 10_000.0, 1_000.0) { ShuttleStop(it, name = "Stop") })
        val bySignal = statistics(run(20, 10_000.0, 1_000.0) { ShuttleStopBySignal(it, name = "Stop") })
        val byEvents = statistics(run(20, 10_000.0, 1_000.0) { ShuttleStopByEvents(it, name = "Stop") })
        assertTrue(byEvents.getValue("NumFullDepartures").average > 0.0 && byEvents.getValue("NumTimedDepartures").average > 0.0,
            "both ways of departing must happen for this to test anything")
        // each statistic of the events version, and where the queue and the signal versions keep it
        val common = listOf("ProbTaxi", "TripLoad", "NumFullDepartures", "NumTimedDepartures")
        val inQueue = byQueue + mapOf("NumWaiting" to byQueue.getValue("StopQ:NumInQ"), "WaitForShuttle" to byQueue.getValue("StopQ:TimeInQ"))
        val inSignal = bySignal + mapOf("NumWaiting" to bySignal.getValue("Boarding:HoldQ:NumInQ"))
        val shared = common + listOf("NumWaiting", "WaitForShuttle")
        assertIdentical(inQueue, byEvents, shared)
        assertIdentical(inSignal, byEvents, shared)
    }

    @Test
    @DisplayName("kitting: every kit made or expedited is accounted for in every replication")
    fun kitting() {
        val stats = statistics(run(20, 10_000.0) { KittingCell(it, name = "Cell") })
        val unaccounted = stats.getValue("KitsUnaccounted")
        assertEquals(20.0, unaccounted.count)
        assertEquals(0.0, unaccounted.min)
        assertEquals(0.0, unaccounted.max)
        assertTrue(stats.getValue("KitsExpedited").average > 0.0 && stats.getValue("RecallsAnswered").average > 0.0,
            "all three ways of ending the wait must happen for this to test anything")
    }
}
