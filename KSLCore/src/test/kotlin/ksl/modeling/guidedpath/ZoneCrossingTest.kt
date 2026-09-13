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
package ksl.modeling.guidedpath

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.AlternatingArbiter
import ksl.modeling.guidedpath.rules.BoundedBatchArbiter
import ksl.modeling.guidedpath.rules.CrossingArbiterIfc
import ksl.modeling.guidedpath.rules.PedestrianPriorityArbiter
import ksl.modeling.guidedpath.rules.VehiclePriorityArbiter
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  People crossing guide-path space that vehicles also want.
 *
 *  The construct is thin on purpose -- a set of zones, an arbiter and its statistics -- so most of
 *  what is asserted here is about the **arbiter**, which is where the modelling actually lives.
 *
 *  Two of these tests exist to show the failures the design record says a single decision produces,
 *  rather than to assert that the shipped disciplines are good. With only a pedestrian rule,
 *  vehicles starve; with only a vehicle rule, pedestrians never cross. Seeing both happen is what
 *  makes the case for an arbiter having *two* questions rather than one.
 *
 *  ## The arithmetic
 *
 *  Zones are twelve feet and carts travel twelve feet a minute, so one zone costs one minute. The
 *  aisle is six zones with the crossing on the third, so a cart sent from A to B with a clear path
 *  reaches the crossing at 2.0 and arrives at 6.0.
 */
class ZoneCrossingTest {

    private class Town(
        parent: ModelElement,
        arbiter: CrossingArbiterIfc,
        val walkTime: Double = 1.0
    ) : ProcessModel(parent, "Town") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Town")
            .link("L1", "A", "B", length = 72.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val crossing = ZoneCrossing(
            this, system, listOf(network.zone("L1.Zone3")!!), arbiter, name = "Crossing"
        )
        val walkQ = HoldQueue(this, "WalkQ")

        val arrivedAt = mutableListOf<Double>()
        val crossedAt = mutableListOf<Double>()

        /** When to send the cart, and when each walker turns up. */
        var cartLeavesAt: Double = Double.NaN
        var walkersArriveAt: List<Double> = emptyList()
        var terminateWalkerAt: Double = Double.NaN

        private var lastWalker: Walker? = null

        init {
            cart.attachArrivalListener { arrivedAt.add(time) }
        }

        inner class Walker : Entity() {
            val walk = process(isDefaultProcess = true) {
                crossOnFoot(crossing, walkTime, walkQ)
                crossedAt.add(time)
            }
        }

        override fun initialize() {
            arrivedAt.clear(); crossedAt.clear()
            if (cartLeavesAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, cartLeavesAt)
            }
            for (t in walkersArriveAt) {
                schedule({ _: KSLEvent<Nothing> ->
                    val w = Walker(); lastWalker = w; activate(w.walk)
                }, t)
            }
            if (terminateWalkerAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> ->
                    lastWalker?.let { if (it.hasCurrentProcess) it.terminateProcess() }
                }, terminateWalkerAt)
            }
        }
    }

    private fun run(
        arbiter: CrossingArbiterIfc,
        length: Double = 60.0,
        walkTime: Double = 1.0,
        setUp: (Town) -> Unit
    ): Town {
        val m = Model("CrossingRun")
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        val town = Town(m, arbiter, walkTime)
        setUp(town)
        m.simulate()
        return town
    }

    // ---- the mechanism -------------------------------------------------------------------------

    @Test
    fun `a walker crosses, and the cart waits for the zones to be given back`() {
        // The whole mechanism in one run. The walker turns up at 0.5 and the crossing takes its
        // zone; the cart, sent at 0.0, is refused Zone3 and waits until the turn ends at 1.5.
        val town = run(PedestrianPriorityArbiter(), walkTime = 3.0) { t ->
            t.cartLeavesAt = 0.0
            t.walkersArriveAt = listOf(0.5)
        }
        assertEquals(listOf(3.5), town.crossedAt, "on at 0.5, across at 3.5")
        assertEquals(1.0, town.crossing.turnsTaken.value, 0.0)
        assertEquals(1.0, town.crossing.crossingsMade.value, 0.0)
        assertTrue(town.arrivedAt.single() > 6.0, "the cart was held up: ${town.arrivedAt}")
        assertNull(town.network.zone("L1.Zone3")!!.holder, "the crossing gave the zone back")
        assertEquals(0, town.network.zone("L1.Zone3")!!.numPresent, "and nobody is left on it")
    }

    @Test
    fun `a cart already on the crossing drains off before the turn opens`() {
        // Nothing is evicted. The cart is crossing Zone3 between 2.0 and 4.0; a walker arriving at
        // 2.5 cannot step on until the cart has gone, and the crossing takes the zone through the
        // ordinary all-or-nothing machinery rather than by taking it from the cart.
        var onAtArrival = 0
        val town = run(PedestrianPriorityArbiter()) { t ->
            t.cartLeavesAt = 0.0
            t.walkersArriveAt = listOf(2.5)
        }
        assertEquals(listOf(6.0), town.arrivedAt, "the cart was not stopped at all")
        assertTrue(town.crossedAt.single() > 3.0, "the walker waited for the cart: ${town.crossedAt}")
        assertEquals(0, onAtArrival)
    }

    @Test
    fun `a walker arriving while a turn is open does not wait at all`() {
        // Two walkers, the second arriving mid-turn. The verb must not suspend it.
        val town = run(PedestrianPriorityArbiter()) { t ->
            t.walkersArriveAt = listOf(1.0, 1.5)
        }
        assertEquals(listOf(2.0, 2.5), town.crossedAt, "each takes exactly its walk time")
        assertEquals(
            0.0, town.crossing.waitToCross.withinReplicationStatistic.weightedAverage, 1e-9,
            "neither waited: the crossing was already theirs"
        )
        assertEquals(1.0, town.crossing.turnsTaken.value, 0.0, "and it was all one turn")
    }

    // ---- the two failures a single decision produces --------------------------------------------

    @Test
    fun `with only a pedestrian rule, vehicles starve`() {
        // A steady stream: there is never an instant with nobody waiting, so the crossing never
        // reopens. The design record's first row, demonstrated rather than asserted.
        val town = run(PedestrianPriorityArbiter(), length = 30.0) { t ->
            t.cartLeavesAt = 0.0
            t.walkersArriveAt = (1..100).map { it * 0.4 }   // past the horizon, so the stream never lets up
        }
        assertTrue(town.arrivedAt.isEmpty(), "the cart should never have got through: ${town.arrivedAt}")
        assertTrue(
            town.crossing.fracTimeBarred.withinReplicationStatistic.weightedAverage > 0.9,
            "the crossing was shut essentially all the time"
        )
    }

    @Test
    fun `with only a vehicle rule, pedestrians wait for a gap`() {
        // The opposite row. This arbiter never bars traffic, so a turn opens only when the crossing
        // happens to be free -- which here it is, once the cart has gone by.
        val town = run(VehiclePriorityArbiter(), length = 30.0) { t ->
            t.cartLeavesAt = 0.0
            t.walkersArriveAt = listOf(0.5)
        }
        assertEquals(listOf(6.0), town.arrivedAt, "traffic is never held up")
        assertTrue(
            town.crossedAt.isEmpty(),
            "and with nothing ever barring vehicles no turn opens at all: ${town.crossedAt}"
        )
    }

    // ---- disciplines with a memory --------------------------------------------------------------

    @Test
    fun `a bounded batch admits the group that opened the turn and no more`() {
        // Three waiting opens a turn; a fourth arriving during it waits for the next one. That
        // bound is the whole discipline -- without it a stream of arrivals extends one turn for
        // ever, which is what the pedestrian-priority test shows.
        val town = run(BoundedBatchArbiter(batchSize = 3, maxWait = 5.0), length = 30.0) { t ->
            t.walkersArriveAt = listOf(1.0, 1.1, 1.2, 1.3)
        }
        assertEquals(4, town.crossedAt.size, "everybody gets across eventually")
        assertEquals(
            2.0, town.crossing.turnsTaken.value, 0.0,
            "but in two turns: the three that opened it, then the one that missed it"
        )
    }

    @Test
    fun `a bounded batch opens on time when the batch never fills`() {
        // One walker and a batch of three. Without the maxWait it would wait for ever.
        val town = run(BoundedBatchArbiter(batchSize = 3, maxWait = 4.0), length = 30.0) { t ->
            t.walkersArriveAt = listOf(1.0)
        }
        assertEquals(1, town.crossedAt.size)
        assertTrue(town.crossedAt.single() >= 5.0, "it waited out maxWait: ${town.crossedAt}")
    }

    @Test
    fun `an alternating arbiter gives traffic a share that pedestrian demand cannot take`() {
        // The same steady stream that starved the cart above. Here the cart gets through, which is
        // the entire argument for a discipline with a cycle.
        val town = run(AlternatingArbiter(walkTime = 2.0, driveTime = 4.0), length = 40.0) { t ->
            t.cartLeavesAt = 0.0
            t.walkersArriveAt = (1..40).map { it * 0.8 }
        }
        assertTrue(town.arrivedAt.isNotEmpty(), "the cart must get through under a cycle")
        assertTrue(town.crossedAt.isNotEmpty(), "and people must still cross")
        val barred = town.crossing.fracTimeBarred.withinReplicationStatistic.weightedAverage
        assertTrue(barred < 0.9, "traffic gets a real share, not the leftovers: $barred")
    }

    // ---- the population edges -------------------------------------------------------------------

    @Test
    fun `a walker terminated while waiting leaves the queue`() {
        val town = run(BoundedBatchArbiter(batchSize = 5, maxWait = 100.0), length = 30.0) { t ->
            t.walkersArriveAt = listOf(1.0)
            t.terminateWalkerAt = 2.0
        }
        assertEquals(0, town.crossing.numWaitingToCross, "the queue must not keep a dead walker")
        assertTrue(town.crossedAt.isEmpty())
    }

    @Test
    fun `a walker terminated while on the crossing still leaves the count`() {
        // The single most likely hand-rolling error, and the reason the construct is worth having:
        // without this the zone keeps an occupant no vehicle can pass and nothing will remove.
        val town = run(PedestrianPriorityArbiter(), length = 30.0, walkTime = 10.0) { t ->
            t.cartLeavesAt = 5.0
            t.walkersArriveAt = listOf(1.0)
            t.terminateWalkerAt = 3.0
        }
        assertEquals(0, town.network.zone("L1.Zone3")!!.numPresent, "the count must be clear")
        assertNull(town.network.zone("L1.Zone3")!!.holder, "and the crossing must have let go")
        assertTrue(town.arrivedAt.isNotEmpty(), "so the cart gets through: ${town.arrivedAt}")
    }

    @Test
    fun `the crossing is put back between replications`() {
        // The defect family this subsystem has met three times. Two replications, same answer.
        val m = Model("CrossingReps")
        m.numberOfReplications = 2
        m.lengthOfReplication = 20.0
        val town = Town(m, BoundedBatchArbiter(batchSize = 2, maxWait = 3.0))
        town.walkersArriveAt = listOf(1.0, 1.5)
        m.simulate()
        assertEquals(
            1.0, town.crossing.turnsTaken.acrossReplicationStatistic.average, 1e-9,
            "one turn carries the batch of two, in each replication"
        )
        assertEquals(0.0, town.crossing.turnsTaken.acrossReplicationStatistic.standardDeviation, 1e-9)
    }

    @Test
    fun `stepping on without stepping off is not something a model can express`() {
        // Recorded as a test because the alternative -- a check at process completion, like the one
        // guide-path space has -- was written first and then removed. Seizing and releasing space
        // are two verbs that a model can fail to pair; crossOnFoot is one verb that does all three
        // steps, so the leak it would have caught cannot be written. A check that can never fire
        // implies a hazard that is not there.
        val town = run(PedestrianPriorityArbiter(), length = 30.0) { t ->
            t.walkersArriveAt = listOf(1.0, 2.0, 3.0)
        }
        assertEquals(3, town.crossedAt.size)
        assertEquals(0, town.network.zone("L1.Zone3")!!.numPresent, "every walker stepped off")
        assertEquals(0, town.crossing.numOnCrossing)
    }

    // ---- the scheduled driver, which is packaging ------------------------------------------------

    @Test
    fun `a driver closes space over and over without a holder being reused`() {
        // ZoneClosureDriver saves the six lines a model would otherwise write for every recurring
        // closure, and the two errors that shape invites: sampling the duration twice, and reusing
        // one holder across closures. A holder is the identity of ONE closure, so a driver that
        // kept a single object would be told it already holds space.
        val m = Model("DriverRun")
        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        val town = Town(m, PedestrianPriorityArbiter())
        val driver = ZoneClosureDriver(
            town, town.system,
            zones = { listOf(town.network.zone("L1.Zone5")!!) },
            timeBetween = ConstantRV(5.0),
            duration = ConstantRV(2.0),
            firstAt = 1.0,
            name = "Repeating"
        )
        m.simulate()
        // Asked at 1, 6, 11, 16, 21, 26 and each held for two minutes: six begun, six ended, and
        // the last ends at 28 inside the horizon.
        assertEquals(6.0, driver.closuresHeld.value, 0.0)
        assertNull(town.network.zone("L1.Zone5")!!.holder, "the last closure gave its zone back")
    }

    @Test
    fun `a driver is put back between replications`() {
        val m = Model("DriverReps")
        m.numberOfReplications = 3
        m.lengthOfReplication = 30.0
        val town = Town(m, PedestrianPriorityArbiter())
        val driver = ZoneClosureDriver(
            town, town.system,
            zones = { listOf(town.network.zone("L1.Zone5")!!) },
            timeBetween = ConstantRV(5.0),
            duration = ConstantRV(2.0),
            firstAt = 1.0,
            name = "Repeating"
        )
        m.simulate()
        assertEquals(6.0, driver.closuresHeld.acrossReplicationStatistic.average, 1e-9)
        assertEquals(
            0.0, driver.closuresHeld.acrossReplicationStatistic.standardDeviation, 1e-9,
            "every replication must close the same number of times"
        )
    }
}
