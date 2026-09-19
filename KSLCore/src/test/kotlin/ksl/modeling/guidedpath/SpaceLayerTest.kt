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

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  The guide path's **space layer**, and the three things extracting it was for.
 *
 *  Both subsystems move vehicles over zones in exactly the same way, and until this was extracted
 *  that sameness was expressed by the active subsystem composing the passive one's class and calling
 *  it `:Space`. An AGV model therefore instantiated a *transport system* it never used as one, and
 *  the two subsystems shared their physics by one of them depending on the other.
 *
 *  That is not an aesthetic complaint, and this suite has the receipt. Five per-carry responses lived
 *  on the passive class, were exposed by the active one on the argument that the layer is shared, and
 *  were fed by only the passive path -- so they read zero in every active model that ever ran, and
 *  `PerCarryStatisticsTest` exists because of it. A shared layer that is nobody's class is how that
 *  particular mistake stops being writable.
 *
 *  Three claims, one test each, and the third is the one that would be worth writing even if the
 *  other two were obvious:
 *
 *  1. **The active subsystem no longer holds a transport system.** Structural, and the direct
 *     regression guard: widening the field back would fail here.
 *  2. **Nothing a passive model reports moved.** The extraction is meant to be invisible from
 *     outside, so the rows are compared by name against a model built the old way -- which is to say,
 *     against `GuidedPathTransportSystem`, whose construction and reported rows are unchanged.
 *     Meanwhile an active model has *lost* one row, `:Space:TransportTime`, which no active run could
 *     ever have filled.
 *  3. **A third consumer can be written against the space layer and nothing else.** The extraction
 *     buys nothing unless the seam is actually usable on its own, so this builds a model that drives
 *     transporters directly over a `GuidedPathSpace`, with no pool, no request, and no transport
 *     verb anywhere in it, and checks that it runs and reports. That is the claim the design record
 *     deferred this work on: *until a second consumer exists*. There are now two, and this is a
 *     third, written in twenty lines.
 */
class SpaceLayerTest {

    private companion object {
        const val VELOCITY = 10.0

        fun loop(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
            .link("Out", "A", "B", length = 100.0, zoneLength = 20.0, beginDirection = 0.0)
            .link("Back", "B", "A", length = 100.0, zoneLength = 20.0, beginDirection = 180.0)
            .build()
    }

    // ---- 1: the active subsystem holds a space and not a transport system ----------------------

    private class ActiveModel(parent: ModelElement) : ProcessModel(parent, "Active") {
        val network = loop("Active")

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(agv, TransporterPlacement.At("A"), ConstantRV(VELOCITY), name = "Cart")
    }

    @Test
    @DisplayName("an active model composes the space layer, not the passive subsystem's class")
    fun theActiveSubsystemHoldsOnlyASpace() {
        val m = Model("SpaceOnly")
        val shop = ActiveModel(m)
        val space = shop.agv.spaceSystem
        assertTrue(space is GuidedPathSpace, "the AGV system's space layer is not a GuidedPathSpace")
        // The whole point. A `GuidedPathTransportSystem` *is* a `GuidedPathSpace`, so the assertion
        // above would hold either way; this is the one that fails if the field is widened back.
        assertFalse(
            space is GuidedPathTransportSystem,
            "the AGV system composes a GuidedPathTransportSystem again, which is the coupling the " +
                    "space layer was extracted to remove: an active model has no use for the passive " +
                    "protocol's transport time and cannot fill the row it registers"
        )
    }

    // ---- 2: what each paradigm reports ----------------------------------------------------------

    private class PassiveModel(parent: ModelElement) : ProcessModel(parent, "Passive") {
        val network = loop("Passive")

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(VELOCITY), name = "Cart")
    }

    private fun rowsOf(build: (Model) -> Unit): Set<String> {
        val m = Model("Rows")
        build(m)
        return (m.responses.map { it.name } + m.counters.map { it.name }).toSet()
    }

    @Test
    @DisplayName("a passive model still reports its transport time; an active one no longer pretends to")
    fun theTransportTimeRowGoesWhereItIsFilled() {
        val passive = rowsOf { PassiveModel(it) }
        assertTrue(
            passive.any { it.endsWith("Sys:TransportTime") },
            "the passive subsystem's own transport time is missing from its report: $passive"
        )
        // Every space-layer row a passive model had, it still has -- the extraction moved the code,
        // not the responses, and a subclass registers its parent's elements under its own name.
        for (row in listOf(
            "Sys:ApproachTime", "Sys:RideTime", "Sys:TransportBlockedTime",
            "Sys:ZonesTraversedPerTransport", "Sys:RouteLengthPerTransport",
            "Sys:NumZoneTraversals", "Sys:ZoneUtilization"
        )) {
            assertTrue(passive.any { it.endsWith(row) }, "the passive report lost $row: $passive")
        }

        val active = rowsOf { ActiveModel(it) }
        // The five per-carry rows are the space layer's and an active model has them, which is what
        // makes `AgvSystem`'s delegation of them honest.
        for (row in listOf(
            "Agv:Space:ApproachTime", "Agv:Space:RideTime", "Agv:Space:TransportBlockedTime",
            "Agv:Space:ZonesTraversedPerTransport", "Agv:Space:RouteLengthPerTransport"
        )) {
            assertTrue(active.any { it.endsWith(row) }, "the active report lost $row: $active")
        }
        // And the row it could never fill is gone rather than empty. An empty row on a report is a
        // question a reader has to answer for themselves, every time.
        assertFalse(
            active.any { it.endsWith("Agv:Space:TransportTime") },
            "an active model still registers the passive paradigm's transport time, which nothing " +
                    "in an active run ever writes to: $active"
        )
        // Its own is `TimeAboard`, not `TransportTime`: the two paradigms measure different intervals
        // and must not share a row name in a model that contains both. `StatisticNamingTest` is where
        // that rule is asserted; here it is only being read correctly.
        assertTrue(
            active.any { it.endsWith("Agv:TimeAboard") },
            "the active subsystem's own aboard-to-set-down interval is missing: $active"
        )
    }

    // ---- 3: a consumer of the space layer alone -------------------------------------------------

    /**
     *  A shuttle that is neither paradigm: no pool, no request, no transport verb.
     *
     *  It commands transporters over a [GuidedPathSpace] with `sendTo` and is told when they arrive.
     *  That is the whole of what a third subsystem -- a rail network, a stacker crane, an AS/RS aisle
     *  -- would need from the layer, and being able to write it in twenty lines against a public type
     *  is what the extraction bought.
     */
    private class ShuttleModel(parent: ModelElement) : ModelElement(parent, "Shuttle") {
        val network = loop("Shuttle")

        init {
            spatialModel = network
        }

        val space = GuidedPathSpace(this, network, name = "Space")
        val shuttle = GuidedTransporter(space, TransporterPlacement.At("A"), ConstantRV(VELOCITY), name = "Car")

        val arrivals = mutableListOf<Pair<String, Double>>()
        private var trips = 0

        init {
            shuttle.attachArrivalListener {
                arrivals.add(it.currentLocation.name to time)
                // Back and forth for as long as the run lasts, which is enough to make the space
                // layer schedule, traverse, release and reset without anybody asking for a vehicle.
                if (++trips < 6) it.sendTo(if (trips % 2 == 0) "B" else "A")
            }
        }

        override fun initialize() {
            arrivals.clear()
            trips = 0
            schedule({ _: KSLEvent<Nothing> -> shuttle.sendTo("B") }, 0.0)
        }
    }

    @Test
    @DisplayName("a subsystem that is neither paradigm can drive the space layer on its own")
    fun theSpaceLayerIsUsableWithoutEitherProtocol() {
        val m = Model("ThirdConsumer")
        val shop = ShuttleModel(m)
        shop.space.checkInvariants = true
        m.numberOfReplications = 2
        m.lengthOfReplication = 500.0
        m.simulate()

        assertEquals(6, shop.arrivals.size, "the shuttle did not complete its trips: ${shop.arrivals}")
        // A lap of the loop is 200 long at velocity 10, and each leg is half of it. Deterministic,
        // so this is an identity rather than an estimate -- and it is what says the space layer
        // really moved something rather than merely accepting the commands.
        val leg = 100.0 / VELOCITY
        shop.arrivals.forEachIndexed { i, (_, at) ->
            assertEquals(
                leg * (i + 1), at, 1.0e-9,
                "arrival ${i + 1} was at $at, not at ${leg * (i + 1)}"
            )
        }
        // The layer reports for this consumer as it does for the other two, without either of them.
        assertTrue(
            shop.space.numZoneTraversals.value > 0.0,
            "the space layer recorded no zone traversals for a model that crossed the network six times"
        )
        assertEquals(
            0.0, shop.space.numTransportersBlocked.withinReplicationStatistic.weightedAverage, 1.0e-12,
            "a single shuttle on a loop of its own cannot be blocked by anything"
        )
        // Replication reset is the space layer's too, and running two of them is what checks it: the
        // second starts from a clean network or the arrival times above would not repeat.
        assertTrue(shop.space.blockedTransporters.isEmpty(), "the run ended with a transporter blocked")
    }
}
