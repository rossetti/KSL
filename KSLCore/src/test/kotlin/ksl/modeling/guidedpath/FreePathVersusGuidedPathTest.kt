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

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.MovableResourcePoolWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The claim the whole subsystem rests on: **a free-path model is not an approximation of a guided
 *  one, it is optimistic about it**, and the error grows with the fleet.
 *
 *  Two models of one haul. Parts arrive at an entry station and are carried to an exit station at
 *  the end of a spur. In the first the carriers are `MovableResource`s over a `DistancesModel`; in
 *  the second they are guided transporters over an aisle whose distances are the same numbers. The
 *  layouts are calibrated so that with one cart, and with two, the two models agree — that
 *  agreement is the control, and without it any divergence later could be an artifact of two models
 *  that were never the same model to begin with.
 *
 *  Everything is deterministic: constant arrivals, constant velocity, one replication. There is no
 *  sampling error here to argue about and no confidence interval to widen if the answer is
 *  inconvenient. The numbers below are arithmetic, and the divergence is not a tendency but a fact
 *  about the two representations.
 *
 *  What the run shows, over fleets of one to eight:
 *
 *  | carts | guided completions | free-path completions | guided time in system | free-path |
 *  |---|---|---|---|---|
 *  | 1 | 64 | 64 | 878.4 | 878.4 |
 *  | 2 | 128 | 128 | 754.6 | 752.4 |
 *  | 4 | 236 | 255 | 538.6 | 498.5 |
 *  | 6 | 236 | 380 | 538.6 | 248.4 |
 *  | 8 | 236 | 492 | 538.6 | 31.0 |
 *
 *  The guide path stops improving at four carts and never improves again: the exit spur admits one
 *  cart at a time, and no size of fleet can put two of them down it. The distance model has no such
 *  notion, so it goes on rewarding every cart added, for ever.
 *
 *  At eight carts the free-path model predicts more than twice the throughput and about a
 *  seventeenth of the time in system. A study that sized this fleet on the free-path answer would
 *  buy eight carts, expect thirty-one minutes, and get five hundred and thirty-eight. That is the
 *  §1.2 claim stated as a number, and it is why "free-path is a reasonable approximation when
 *  vehicles are few" is a statement with a range of validity rather than a general one — the first
 *  two rows are exactly where it holds.
 */
class FreePathVersusGuidedPathTest {

    private companion object {
        const val ENTRY = "Entry"
        const val EXIT = "Exit"

        /** Constant arrivals fast enough to keep every fleet size busy. */
        const val TIME_BETWEEN_ARRIVALS = 4.0
        const val HORIZON = 2000.0
        const val VELOCITY = 10.0

        // The aisle: a one-way loop of 240 feet with a 36-foot spur to the exit station, and a
        // parking spur per cart off the entry junction so that no idle cart stands in the loop.
        const val ENTRY_TO_EXIT = 204.0
        const val EXIT_TO_ENTRY = 108.0
        const val HOME_TO_ENTRY = 12.0

        fun aisle(numCarts: Int): GuidedPathNetwork {
            var b = GuidedPathNetwork.builder("Haul")
                .intersection("I1", x = 0.0, y = 72.0)
                .intersection("I2", x = 48.0, y = 72.0)
                .intersection("I3", x = 48.0, y = 0.0)
                .intersection("I4", x = 0.0, y = 0.0)
                .intersection("I5", x = 0.0, y = -36.0)
                .link("L1", "I1", "I2", length = 48.0, zoneLength = 12.0, beginDirection = 0.0)
                .link("L2", "I2", "I3", length = 72.0, zoneLength = 12.0, beginDirection = 270.0)
                .link("L3", "I3", "I4", length = 48.0, zoneLength = 12.0, beginDirection = 180.0)
                .link("L4", "I4", "I1", length = 72.0, zoneLength = 12.0, beginDirection = 90.0)
                .link(
                    "ExitSpur", "I4", "I5", length = 36.0, zoneLength = 12.0,
                    type = LinkType.SPUR, beginDirection = 270.0
                )
            for (i in 1..numCarts) {
                b = b.intersection("H$i", x = -12.0, y = 72.0 + 12.0 * i)
                b = b.link(
                    "HS$i", "I1", "H$i", length = HOME_TO_ENTRY, zoneLength = 12.0,
                    type = LinkType.SPUR, beginDirection = 180.0
                )
            }
            return b.station(ENTRY, "I1").station(EXIT, "I5").build()
        }
    }

    /** What one configuration produced. */
    private data class Outcome(val completions: Double, val timeInSystem: Double, val blockedFraction: Double)

    // ---- the guided model ----------------------------------------------------------------------

    private class GuidedHaul(parent: ModelElement, numCarts: Int) : ProcessModel(parent, "GuidedHaul") {
        val network = aisle(numCarts)

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val carts = (1..numCarts).map { i ->
            GuidedTransporter(
                system, TransporterPlacement.At("H$i"), ConstantRV(VELOCITY), 1,
                EndOfZoneControl(), "Cart$i"
            ).apply { homeBase = "H$i" }
        }

        val pool = GuidedTransporterPoolWithQ(
            this, system, carts, ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Carts"
        )

        val timeInSystem = Response(this, "TimeInSystem")
        val completed = Counter(this, "Completed")

        @Suppress("unused")
        private val generator = EntityGenerator(
            ::Part, ConstantRV(TIME_BETWEEN_ARRIVALS), ConstantRV(TIME_BETWEEN_ARRIVALS)
        )

        inner class Part : Entity() {
            @Suppress("unused")
            val haul = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                guidedTransport(pool, destination = EXIT, pickupLocation = ENTRY)
                timeInSystem.value = time - arrived
                completed.increment()
            }
        }
    }

    // ---- the free-path model, over the same distances -------------------------------------------

    private class FreePathHaul(parent: ModelElement, numCarts: Int) : ProcessModel(parent, "FreePathHaul") {
        private val distances = DistancesModel()
        val home = distances.Location("Home")
        val entry = distances.Location("Entry")
        val exit = distances.Location("Exit")

        init {
            // The same numbers the aisle implies, so the two models place things identically and
            // differ only in what a cart must do to get between them.
            distances.addDistance(home, entry, HOME_TO_ENTRY)
            distances.addDistance(entry, exit, ENTRY_TO_EXIT)
            distances.addDistance(exit, entry, EXIT_TO_ENTRY)
            distances.addDistance(exit, home, EXIT_TO_ENTRY + HOME_TO_ENTRY)
            distances.addDistance(entry, home, HOME_TO_ENTRY)
            distances.addDistance(home, exit, HOME_TO_ENTRY + ENTRY_TO_EXIT)
            distances.defaultVelocity = ConstantRV(VELOCITY)
            spatialModel = distances
        }

        val fleet = List(numCarts) { MovableResource(this, home, ConstantRV(VELOCITY), "Cart$it") }
        private val requestQ = RequestQ(this, "CartQ")
        val pool = MovableResourcePoolWithQ(this, fleet, ConstantRV(VELOCITY), requestQ, "Carts")

        val timeInSystem = Response(this, "TimeInSystem")
        val completed = Counter(this, "Completed")

        @Suppress("unused")
        private val generator = EntityGenerator(
            ::Part, ConstantRV(TIME_BETWEEN_ARRIVALS), ConstantRV(TIME_BETWEEN_ARRIVALS)
        )

        inner class Part : Entity() {
            @Suppress("unused")
            val haul = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = entry
                transportWith(pool, exit)
                timeInSystem.value = time - arrived
                completed.increment()
            }
        }
    }

    private fun guided(numCarts: Int): Outcome {
        val m = Model("GuidedHaulRun$numCarts")
        val shop = GuidedHaul(m, numCarts)
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Outcome(
            shop.completed.acrossReplicationStatistic.average,
            shop.timeInSystem.acrossReplicationStatistic.average,
            shop.system.numTransportersBlocked.acrossReplicationStatistic.average / numCarts
        )
    }

    private fun freePath(numCarts: Int): Outcome {
        val m = Model("FreePathHaulRun$numCarts")
        val shop = FreePathHaul(m, numCarts)
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return Outcome(
            shop.completed.acrossReplicationStatistic.average,
            shop.timeInSystem.acrossReplicationStatistic.average,
            0.0
        )
    }

    @Test
    @DisplayName("With one cart, and with two, the two models agree")
    fun theTwoModelsAgreeWhenThereIsNoContention() {
        // The control. One cart cannot contend with anything, so any difference here would be a
        // difference in the distances or the dispatching rather than in congestion, and every
        // divergence measured later would be uninterpretable.
        val g1 = guided(1)
        val f1 = freePath(1)
        assertEquals(f1.completions, g1.completions, 0.0, "one cart moves the same parts either way")
        assertEquals(f1.timeInSystem, g1.timeInSystem, 1e-9, "and takes exactly as long doing it")
        assertEquals(0.0, g1.blockedFraction, 0.0, "with one cart there is nothing to block against")

        val g2 = guided(2)
        val f2 = freePath(2)
        assertEquals(f2.completions, g2.completions, 0.0)
        assertTrue(
            g2.timeInSystem - f2.timeInSystem < 0.01 * f2.timeInSystem,
            "two carts on a 240-foot loop barely meet, so the models must still agree to within a " +
                    "percent: guided ${g2.timeInSystem} against free-path ${f2.timeInSystem}"
        )
    }

    @Test
    @DisplayName("A distance model rewards every cart added; an aisle stops rewarding them")
    fun theFreePathModelKeepsImprovingAndTheGuidePathDoesNot() {
        val fleets = listOf(1, 2, 4, 6, 8)
        val free = fleets.map { freePath(it) }
        val guide = fleets.map { guided(it) }

        // A distance model has no notion of one cart being in another's way, so throughput rises
        // with every cart, without limit and without any warning that it should not.
        for (i in 1 until fleets.size) {
            assertTrue(
                free[i].completions > free[i - 1].completions,
                "free-path throughput must rise from ${fleets[i - 1]} to ${fleets[i]} carts, but " +
                        "went ${free[i - 1].completions} -> ${free[i].completions}"
            )
        }

        // The aisle stops. The exit spur admits one cart at a time and no fleet can put two down
        // it, so beyond four carts the extra ones only queue.
        assertEquals(
            guide[2].completions, guide[3].completions, 0.0,
            "four carts and six must deliver the same parts: the spur, not the fleet, is the limit"
        )
        assertEquals(
            guide[3].completions, guide[4].completions, 0.0,
            "and eight carts must deliver no more than six"
        )
        assertTrue(
            guide[4].blockedFraction > 0.25,
            "the surplus carts must be visibly waiting, not quietly absorbed: blocked fraction " +
                    "was ${guide[4].blockedFraction}"
        )
    }

    @Test
    @DisplayName("The free-path answer would size this fleet wrongly, and says nothing about it")
    fun theFreePathAnswerIsOptimisticAndSilent() {
        val guidedAt8 = guided(8)
        val freeAt8 = freePath(8)

        assertTrue(
            freeAt8.completions > 2.0 * guidedAt8.completions,
            "at eight carts the distance model must claim more than twice the throughput the aisle " +
                    "can deliver: ${freeAt8.completions} against ${guidedAt8.completions}"
        )
        assertTrue(
            guidedAt8.timeInSystem > 10.0 * freeAt8.timeInSystem,
            "and about a seventeenth of the time in system: free-path ${freeAt8.timeInSystem} " +
                    "against guided ${guidedAt8.timeInSystem}"
        )
        // The point is not that the free-path number is wrong. It is that nothing in the free-path
        // model is capable of being wrong here: there is no statistic it could report, however
        // carefully read, that would reveal the aisle it does not represent.
        assertTrue(
            guidedAt8.blockedFraction > 0.25,
            "the guided model, by contrast, says so plainly: ${guidedAt8.blockedFraction} of the " +
                    "fleet is blocked at any moment"
        )
    }
}
