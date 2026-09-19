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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **What a junction costs, and how to stop it costing that.**
 *
 *  A junction is a zone, so it admits one vehicle at a time. That is not a special rule about
 *  junctions; it is the *only* rule the subsystem has, applied to junctions like everything else.
 *  But two consequences of it are worth pinning to numbers rather than leaving as folklore, because
 *  a modeller has to be able to predict them and, where they are wrong for a layout, to remove them.
 *
 *  **1. How long a junction is held.** A junction of zero length takes no time to cross, so it is
 *  tempting to conclude it is held for no time. It is not. Under the default `EndOfZoneControl` a
 *  vehicle gives up the zone behind it *on arriving in the next one*, so a vehicle crossing junction
 *  J holds J from the moment it claims J until it has travelled the whole first zone of the outgoing
 *  link. The junction therefore costs **one outgoing-zone traversal**, and that is predictable from
 *  the layout: `zoneLength / velocity` of the link the vehicle leaves by.
 *
 *  This is not an artefact. Releasing J the instant the vehicle nominally reached it would let a
 *  second vehicle claim J while the first is still physically in the intersection, which is the
 *  thing zone exclusivity exists to prevent. If you want the junction released sooner, that is
 *  exactly what `StartOfZoneControl` is for -- and choosing it is a statement about the real control
 *  system, not a tuning knob.
 *
 *  **2. Two directions of one aisle share the junction, and often should not.** In a two-lane aisle
 *  the northbound and southbound lanes are separate links, but if they meet at the same junction
 *  *node* they share that node's zone -- so a northbound and a southbound vehicle serialise at a
 *  crossing point where, in a real wide aisle, they would pass one another.
 *
 *  The lever is the layout, not a flag: **give each direction its own junction node.** The two lanes
 *  then never share a zone anywhere, and the contention disappears. A modeller decides which
 *  movements genuinely conflict by deciding which movements share a node, which is the same decision
 *  a traffic engineer makes when drawing conflict points.
 */
class JunctionOccupancyTest {

    // -- 1. what a junction costs ---------------------------------------------------------------

    /**
     *  One aisle, one junction, two vehicles going the same way: `A -> J -> B`, zones of 50 at a
     *  velocity of 10, so a zone traversal is 5.
     */
    private class FollowingShop(parent: ModelElement) : ProcessModel(parent, "Following") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Straight")
            .intersection("A", x = 0.0, y = 0.0)
            .intersection("J", x = 100.0, y = 0.0)
            .intersection("B", x = 200.0, y = 0.0)
            .link("A-J", "A", "J", length = 100.0, zoneLength = 50.0)
            .link("J-B", "J", "B", length = 100.0, zoneLength = 50.0)
            .build()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Fleet")

        init {
            // One response per zone, so the junction's own occupancy can be read off directly.
            agv.collectZoneStatistics = true
        }

        val cart = AgvVehicle(agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")

        inner class Haul : Entity() {
            val run = process {
                currentLocation = network.requireLocation("A")
                transportByFleet(agv, destination = "B", origin = "A")
            }
        }

        override fun initialize() {
            activate(Haul().run)
        }
    }

    @Test
    @DisplayName("a junction of zero length is held for one outgoing-zone traversal")
    fun aJunctionCostsOneZoneTraversal() {
        val horizon = 500.0
        val m = Model("JunctionHold")
        val shop = FollowingShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = horizon
        m.simulate()

        // The junction J has length 0, so crossing it takes no time. The time it is *held* is a
        // different quantity, and this is it: the fraction of the run J was occupied, times the run.
        val occupied = m.response("Fleet:Space:J:ZoneCovered")!!
            .withinReplicationStatistic.weightedAverage * horizon

        // Predicted from the layout, not fitted: the outgoing link J-B has zones of 50 and the cart
        // travels at 10, so the cart holds J until it has covered that first zone -- 5.0 units.
        assertEquals(
            5.0, occupied, 1e-9,
            "a zero-length junction still costs one traversal of the first zone beyond it, " +
                    "because the default rule gives up the zone behind on arriving in the next"
        )
    }

    // -- 2. the lever: one junction node per direction --------------------------------------------

    /**
     *  A two-lane aisle whose two lanes **meet at one node**. Northbound `S -> C -> N`, southbound
     *  `N -> C -> S`. Different links; the same junction zone.
     */
    private class SharedNodeAisle(parent: ModelElement) : ProcessModel(parent, "SharedNode") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("SharedNode")
            .intersection("S", x = 0.0, y = 0.0)
            .intersection("C", x = 0.0, y = 100.0)
            .intersection("N", x = 0.0, y = 200.0)
            .link("S-C", "S", "C", length = 100.0, zoneLength = 50.0)
            .link("C-N", "C", "N", length = 100.0, zoneLength = 50.0)
            .link("N-C", "N", "C", length = 100.0, zoneLength = 50.0)
            .link("C-S", "C", "S", length = 100.0, zoneLength = 50.0)
            .build()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Fleet")
        val northbound = AgvVehicle(agv, TransporterPlacement.At("S"), ConstantRV(10.0), name = "Northbound")
        val southbound = AgvVehicle(agv, TransporterPlacement.At("N"), ConstantRV(10.0), name = "Southbound")

        inner class Haul(private val from: String, private val to: String) : Entity() {
            val run = process {
                currentLocation = network.requireLocation(from)
                transportByFleet(agv, destination = to, origin = from)
            }
        }

        override fun initialize() {
            activate(Haul("S", "N").run)
            activate(Haul("N", "S").run)
        }
    }

    /**
     *  The same aisle with **a junction node per direction**: northbound crosses `Cup`, southbound
     *  crosses `Cdn`. The lanes are as separate in the middle as they are along their length, which
     *  is what a wide aisle physically is.
     */
    private class SplitNodeAisle(parent: ModelElement) : ProcessModel(parent, "SplitNode") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("SplitNode")
            .intersection("S", x = 0.0, y = 0.0)
            .intersection("Cup", x = -5.0, y = 100.0)
            .intersection("Cdn", x = 5.0, y = 100.0)
            .intersection("N", x = 0.0, y = 200.0)
            .link("S-Cup", "S", "Cup", length = 100.0, zoneLength = 50.0)
            .link("Cup-N", "Cup", "N", length = 100.0, zoneLength = 50.0)
            .link("N-Cdn", "N", "Cdn", length = 100.0, zoneLength = 50.0)
            .link("Cdn-S", "Cdn", "S", length = 100.0, zoneLength = 50.0)
            .build()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Fleet")
        val northbound = AgvVehicle(agv, TransporterPlacement.At("S"), ConstantRV(10.0), name = "Northbound")
        val southbound = AgvVehicle(agv, TransporterPlacement.At("N"), ConstantRV(10.0), name = "Southbound")

        inner class Haul(private val from: String, private val to: String) : Entity() {
            val run = process {
                currentLocation = network.requireLocation(from)
                transportByFleet(agv, destination = to, origin = from)
            }
        }

        override fun initialize() {
            activate(Haul("S", "N").run)
            activate(Haul("N", "S").run)
        }
    }

    @Test
    @DisplayName("two lanes meeting at one node contend; a node per direction removes it")
    fun aNodePerDirectionRemovesTheContention() {
        val shared = Model("Shared").let { m ->
            val s = SharedNodeAisle(m)
            m.numberOfReplications = 1
            m.lengthOfReplication = 500.0
            m.simulate()
            s.northbound.numTimesBlocked.acrossReplicationStatistic.average +
                    s.southbound.numTimesBlocked.acrossReplicationStatistic.average
        }

        val split = Model("Split").let { m ->
            val s = SplitNodeAisle(m)
            m.numberOfReplications = 1
            m.lengthOfReplication = 500.0
            m.simulate()
            s.northbound.numTimesBlocked.acrossReplicationStatistic.average +
                    s.southbound.numTimesBlocked.acrossReplicationStatistic.average
        }

        assertTrue(
            shared > 0.0,
            "opposed lanes that meet at one node share that node's zone and serialise there"
        )
        assertEquals(
            0.0, split, 1e-12,
            "a junction node per direction gives the two lanes no zone in common, so vehicles " +
                    "running opposite ways pass one another as they would in a wide aisle"
        )
    }
}
