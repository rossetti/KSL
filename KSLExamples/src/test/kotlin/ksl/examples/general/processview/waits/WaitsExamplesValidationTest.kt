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
    @DisplayName("hospital beds: a pool of beds, alone and with a step-down pool, agrees with the exact M/M/c+M answers")
    fun hospitalBeds() {
        lateinit var ward: Ward
        run(20, 20_000.0, 2_000.0) { ward = Ward(it, name = "Ward") }
        val exactWard = ErlangA(lambda = 1.4, mu = 1.0 / 8.0, theta = 1.0 / 4.0, c = 12)
        assertInside("ward: probability of overflow", ward.probOverflow.acrossReplicationStatistic, exactWard.probAbandon)
        assertInside("ward: mean number waiting", ward.numWaiting.acrossReplicationStatistic, exactWard.meanNumberWaiting)
        assertInside("ward: mean wait", ward.timeWaiting.acrossReplicationStatistic, exactWard.meanWait)
        lateinit var both: WardWithStepDown
        run(20, 20_000.0, 2_000.0) { both = WardWithStepDown(it, name = "Ward") }
        val exactBoth = ErlangA(lambda = 1.4, mu = 1.0 / 8.0, theta = 1.0 / 4.0, c = 16)
        assertInside("both: probability of overflow", both.probOverflow.acrossReplicationStatistic, exactBoth.probAbandon)
        assertInside("both: mean number waiting", both.numWaiting.acrossReplicationStatistic, exactBoth.meanNumberWaiting)
        assertInside("both: mean wait", both.timeWaiting.acrossReplicationStatistic, exactBoth.meanWait)
        assertTrue(both.probStepDown.acrossReplicationStatistic.average > 0.0, "the step-down pool must be used for this to test anything")
    }

    private fun bin(build: (Model) -> TwoColourBin): TwoColourBin {
        lateinit var b: TwoColourBin
        run(20, 100_000.0, 10_000.0) { b = build(it) }
        return b
    }

    @Test
    @DisplayName("two-colour bin: with FirstFillableRequest, a part of either colour agrees with the exact M/M/1 answers; the default selector does not")
    fun twoColourBinSelectors() {
        val first = bin { TwoColourBin(it, name = "Cell") }
        val lambda = 1.0 / first.meanBetweenRed + 1.0 / first.meanBetweenBlue
        val rho = lambda * first.meanWork
        val exactNumber = rho * rho / (1.0 - rho)
        assertInside("parts in the bin", first.numInBin.acrossReplicationStatistic, exactNumber)
        assertInside("time in the bin", first.timeInBin.acrossReplicationStatistic, exactNumber / lambda)
        // the default selector leaves the picker waiting for red beside a blue part, so more parts wait
        val head = bin { TwoColourBin(it, firstFillable = false, name = "Cell") }
        val ci = head.numInBin.acrossReplicationStatistic.confidenceInterval(0.99)
        assertTrue(ci.lowerLimit > exactNumber, "the default selector: $ci against $exactNumber")
    }

    @Test
    @DisplayName("two-colour bin: with a time limit, two triggers and one request for any part give identical results, under either selector")
    fun twoColourBinTimed() {
        val names = listOf("Bin:ChannelQ:NumInQ", "PartsTaken", "TimesTidied")
        val twoTriggers = statistics(bin { TwoColourBin(it, patience = 10.0, name = "Cell") }.model)
        val oneRequest = statistics(bin { TwoColourBin(it, picking = Picking.ONE_REQUEST, patience = 10.0, name = "Cell") }.model)
        val oneRequestHead = statistics(bin { TwoColourBin(it, picking = Picking.ONE_REQUEST, firstFillable = false, patience = 10.0, name = "Cell") }.model)
        assertTrue(twoTriggers.getValue("TimesTidied").average > 0.0, "the time limit must run out for this to test anything")
        assertIdentical(twoTriggers, oneRequest, names)
        assertIdentical(oneRequest, oneRequestHead, names)
    }

    @Test
    @DisplayName("two-colour bin: the painter's batches take every part of their colour, and every part is taken or still in the bin")
    fun twoColourBinBatches() {
        for (firstFillable in listOf(true, false)) {
            val painter = bin { TwoColourBin(it, picking = Picking.BATCHES, firstFillable = firstFillable, name = "Cell") }
            assertEquals(0.0, painter.unaccounted.acrossReplicationStatistic.min, "firstFillable $firstFillable")
            assertEquals(0.0, painter.unaccounted.acrossReplicationStatistic.max, "firstFillable $firstFillable")
            assertEquals(0.0, painter.incompleteBatches.acrossReplicationStatistic.max, "firstFillable $firstFillable")
            assertTrue(painter.batchSize.acrossReplicationStatistic.average > 1.0, "batches of more than one part must form")
        }
    }

    private fun auction(closing: Closing): TimedAuction {
        lateinit var a: TimedAuction
        run(20, 60_001.0) { a = TimedAuction(it, closing = closing, name = "Auction") }
        return a
    }

    @Test
    @DisplayName("timed auction: sales, winning bids, wins, withdrawals and waits agree with the exact answers, and every bidder has one outcome")
    fun timedAuctionExact() {
        val a = auction(Closing.SIGNAL)
        val exact = AuctionExact(1.0 / a.meanBetweenBidders, 1.0 / a.meanPatience, a.auctionLength)
        assertInside("probability sold", a.probSold.acrossReplicationStatistic, exact.probSold)
        assertInside("mean winning bid", a.winningBid.acrossReplicationStatistic, exact.meanWinningBid)
        assertInside("probability won", a.probWon.acrossReplicationStatistic, exact.probWon)
        assertInside("probability withdrew", a.probWithdrew.acrossReplicationStatistic, exact.probWithdrew)
        assertInside("mean wait", a.timeWaiting.acrossReplicationStatistic, exact.meanWait)
        assertEquals(0.0, a.unaccounted.acrossReplicationStatistic.max, "every bidder has one outcome and every lot one winner")
    }

    @Test
    @DisplayName("timed auction: a close broadcast and rejections with receiveMessageOrTimeout give identical results")
    fun timedAuctionTwoWays() {
        val names = listOf("ProbSold", "WinningBid", "ProbWon", "ProbWithdrew", "TimeWaiting", "Unaccounted")
        val bySignal = statistics(auction(Closing.SIGNAL).model)
        val byRejections = statistics(auction(Closing.REJECTIONS).model)
        assertIdentical(bySignal, byRejections, names)
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
