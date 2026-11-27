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

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentSignal
import ksl.modeling.agent.receiveMessageOrTimeout
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.StatisticIfc
import kotlin.math.abs
import kotlin.math.exp

/** How the auctioneer tells the bidders that an auction has closed. */
enum class Closing {
    /** The winner gets an `Accept`; the others hear the close broadcast, an agent signal. */
    SIGNAL,
    /** The winner gets an `Accept`; every other bidder gets a `Reject`. */
    REJECTIONS
}

/**
 *  A run of timed auctions, one lot each, every `auctionLength` minutes. Bidders arrive every `meanBetweenBidders`
 *  minutes on average, exponential, and bid a uniform amount up to 100. Each waits for the first of: the award of
 *  the lot, the close of the auction without it, or the end of its patience, exponential with mean `meanPatience`,
 *  when it withdraws its bid. At the close the auctioneer awards the lot to the highest bid standing, and tells the
 *  others as [Closing] says. No bidder arrives after the last of `numAuctions` auctions, so every bidder's wait ends.
 */
class TimedAuction(
    parent: ModelElement,
    val closing: Closing = Closing.SIGNAL,
    val meanBetweenBidders: Double = 10.0,
    val meanPatience: Double = 30.0,
    val auctionLength: Double = 60.0,
    val numAuctions: Int = 1000,
    name: String? = null
) : AgentModel(parent, name) {

    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 2), "Patience")
    private val bidAmount = RandomVariable(this, UniformRV(0.0, 100.0, 3), "BidAmount")

    private val auctioneer = PermanentAgent("Auctioneer")
    private val closed = AgentSignal("AuctionClosed")

    private class Bid(val bidder: Bidder, val amount: Double)

    private val book = mutableListOf<Bid>()
    private var lot = 0

    /** Whether each auction sold its lot. */
    val probSold = Response(this, "ProbSold")
    /** The amount of each winning bid. */
    val winningBid = Response(this, "WinningBid")
    /** Whether each bidder won. */
    val probWon = Response(this, "ProbWon")
    /** Whether each bidder withdrew before the close. */
    val probWithdrew = Response(this, "ProbWithdrew")
    /** The time each bidder waited, from its bid to the end of its wait. */
    val timeWaiting = Response(this, "TimeWaiting")
    /** The bidders without exactly one outcome, and the lots sold without exactly one winner, each replication: none. */
    val unaccounted = Response(this, "Unaccounted")

    // counts kept from the start of the replication, for the accounting at its end
    private var arrived = 0
    private var resolved = 0
    private var won = 0
    private var sold = 0

    inner class Bidder(aName: String) : Agent(aName) {
        val bidding: KSLProcess = process(isDefaultProcess = true) {
            val bid = Bid(this@Bidder, bidAmount.value)
            book.add(bid)
            val limit = patience.value
            val outcome: Outcome
            val waited: Double
            when (closing) {
                Closing.SIGNAL -> {
                    val award = mailbox.triggerOfType<AgentMessage.Accept>()
                    val close = closed.trigger()
                    val result = waitForFirst(award, close, timeout = limit)
                    waited = result.timeWaited
                    outcome = when (result.trigger) {
                        award -> Outcome.WON
                        close -> Outcome.LOST
                        else -> Outcome.WITHDREW
                    }
                }
                Closing.REJECTIONS -> {
                    val result = receiveMessageOrTimeout(mailbox, timeout = limit)
                    waited = result.timeWaited
                    outcome = when {
                        result.timedOut -> Outcome.WITHDREW
                        result.value is AgentMessage.Accept -> Outcome.WON
                        else -> Outcome.LOST
                    }
                }
            }
            if (outcome == Outcome.WITHDREW) book.remove(bid)
            if (outcome == Outcome.WON) won++
            resolved++
            probWon.value = if (outcome == Outcome.WON) 1.0 else 0.0
            probWithdrew.value = if (outcome == Outcome.WITHDREW) 1.0 else 0.0
            timeWaiting.value = waited
        }
    }

    private enum class Outcome { WON, LOST, WITHDREW }

    private var created = 0

    private val bidders = AgentGenerator(
        agentFactory = { arrived++; Bidder("Bidder-${++created}") },
        timeUntilFirst = ExponentialRV(meanBetweenBidders, 1),
        timeBetween = ExponentialRV(meanBetweenBidders, 1),
        timeOfLast = numAuctions * auctionLength
    )

    override fun initialize() {
        book.clear()
        lot = 0
        arrived = 0
        resolved = 0
        won = 0
        sold = 0
        schedule(::close, auctionLength)
    }

    private fun close(event: KSLEvent<Nothing>) {
        lot++
        val winner = book.maxByOrNull { it.amount }
        probSold.value = if (winner == null) 0.0 else 1.0
        if (winner != null) {
            sold++
            winningBid.value = winner.amount
            winner.bidder.mailbox.deliver(AgentMessage.Accept(auctioneer, "lot-$lot"))
        }
        when (closing) {
            Closing.SIGNAL -> closed.fire()
            Closing.REJECTIONS -> for (b in book) if (b !== winner) b.bidder.mailbox.deliver(AgentMessage.Reject(auctioneer, "lot-$lot"))
        }
        book.clear()
        if (lot < numAuctions) schedule(::close, auctionLength)
    }

    override fun replicationEnded() {
        unaccounted.value = (abs(arrived - resolved) + abs(sold - won)).toDouble()
    }
}

/**
 *  The exact answers for the auctions of [TimedAuction]. A bidder arrives at a time uniform over its auction, so the
 *  time to the close is uniform on 0 to `length`, and it is still bidding at the close if its exponential patience
 *  outlasts that. The bidders still bidding at a close are then Poisson, with mean [standing], and a lot is sold
 *  when there is at least one.
 *
 *  @param lambda the rate at which bidders arrive
 *  @param theta the rate at which a bidder's patience runs out
 *  @param length the length of an auction
 *  @param top the largest bid; bids are uniform from 0 to it
 */
class AuctionExact(val lambda: Double, val theta: Double, val length: Double, val top: Double = 100.0) {
    /** The probability that a bidder is still bidding at the close. */
    val probStanding: Double = (1.0 - exp(-theta * length)) / (theta * length)
    /** The mean number of bidders still bidding at a close. */
    val standing: Double = lambda * length * probStanding
    /** The probability that an auction sells its lot. */
    val probSold: Double = 1.0 - exp(-standing)
    /** The probability that a bidder wins: one winner per lot sold, among the bidders of an auction. */
    val probWon: Double = probSold / (lambda * length)
    /** The probability that a bidder withdraws before the close. */
    val probWithdrew: Double = 1.0 - probStanding
    /** The mean time a bidder waits: its patience or the time to the close, whichever is shorter. */
    val meanWait: Double = probWithdrew / theta

    /** The mean winning bid: the largest of n uniform bids has mean n/(n + 1) of the top, given a sale. */
    val meanWinningBid: Double
        get() {
            var p = exp(-standing)
            var sum = 0.0
            var n = 0
            while (n < 200) {
                n++
                p *= standing / n
                sum += p * n / (n + 1.0)
            }
            return top * sum / probSold
        }
}

/**
 *  Runs the auctions told of their close by a broadcast, and by rejections, against the exact answers, with 99%
 *  confidence intervals from the replications, and compares the two.
 */
fun main() {
    fun run(closing: Closing): TimedAuction {
        val m = Model("TimedAuction", autoCSVReports = false)
        val a = TimedAuction(m, closing = closing, name = "Auction")
        m.numberOfReplications = 20
        // a moment past the last close, so that every bidder's wait has ended
        m.lengthOfReplication = a.numAuctions * a.auctionLength + 1.0
        m.simulate()
        return a
    }
    val bySignal = run(Closing.SIGNAL)
    val exact = AuctionExact(1.0 / bySignal.meanBetweenBidders, 1.0 / bySignal.meanPatience, bySignal.auctionLength)
    println("Bidders told of the close by a broadcast, against the exact values, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareAuction("probability a lot is sold", bySignal.probSold.acrossReplicationStatistic, exact.probSold)
    compareAuction("mean winning bid", bySignal.winningBid.acrossReplicationStatistic, exact.meanWinningBid)
    compareAuction("probability a bidder wins", bySignal.probWon.acrossReplicationStatistic, exact.probWon)
    compareAuction("probability of withdrawing", bySignal.probWithdrew.acrossReplicationStatistic, exact.probWithdrew)
    compareAuction("mean wait of a bidder", bySignal.timeWaiting.acrossReplicationStatistic, exact.meanWait)
    println(String.format("%-28s %10.0f", "most unaccounted", bySignal.unaccounted.acrossReplicationStatistic.max))

    val byRejections = run(Closing.REJECTIONS)
    println()
    println("The same auctions, with a rejection for each losing bidder and receiveMessageOrTimeout")
    println(String.format("%-28s %12s %12s %6s", "statistic", "broadcast", "rejections", "same"))
    for ((label, a, b) in listOf(
        Triple("probability a lot is sold", bySignal.probSold, byRejections.probSold),
        Triple("mean winning bid", bySignal.winningBid, byRejections.winningBid),
        Triple("probability a bidder wins", bySignal.probWon, byRejections.probWon),
        Triple("probability of withdrawing", bySignal.probWithdrew, byRejections.probWithdrew),
        Triple("mean wait of a bidder", bySignal.timeWaiting, byRejections.timeWaiting)
    )) {
        val x = a.acrossReplicationStatistic
        val y = b.acrossReplicationStatistic
        val same = x.average == y.average && x.variance == y.variance
        println(String.format("%-28s %12.4f %12.4f %6s", label, x.average, y.average, if (same) "yes" else "NO"))
    }
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 8.")
}

private fun compareAuction(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
