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
 *  A **two-lane road network**: rows and aisles wide enough for traffic both ways, modelled as a
 *  pair of opposed one-way links rather than as one bidirectional link.
 *
 *  This is the warehouse layout that is not a loop and not a tree: a rectangular grid of rows and
 *  aisles, each wide enough for two lanes. The question it raises is whether the guide path can
 *  express it at all, and the answer is that it is the ordinary case rather than a special one --
 *  but three of its properties are worth holding a test to, because a modeller will assume them and
 *  two of them are not obvious.
 *
 *  **One network, not two.** A lane is a link. Two lanes between the same pair of junctions are two
 *  links, opposed. Nothing in the network keys on the pair of endpoints, so a second link between
 *  the same two junctions is not a duplicate of anything and needs no separate network, no separate
 *  system and no coordination between them. Two networks would be worse than redundant: routing,
 *  blocking and deadlock detection are per network, so a vehicle on one could not see a vehicle on
 *  the other, which is exactly the property a road model must not have -- they share the junctions.
 *
 *  **A junction is a zone, so it admits one vehicle at a time.** That is what makes a crossroads a
 *  crossroads. Two vehicles whose routes cross at a junction contend for it even when their lanes
 *  never touch, and they contend for it even when the junction is dimensionless and crossing it
 *  takes no time at all. In a grid under load this is where the queueing appears, and a modeller who
 *  expects lanes alone to decongest the layout will be surprised by it -- which is the point of
 *  modelling the grid on a guide path rather than on a free path.
 *
 *  **A U-turn is available wherever the return lane starts at the junction reached.** Nothing
 *  special implements it: leaving a junction by any link that begins there is what routing already
 *  does, and the return lane is such a link. So a vehicle turns round at a junction rather than in
 *  place, which is the physical truth about a vehicle in an aisle.
 */
class TwoLaneGridTest {

    companion object {

        /**
         *  A 2x2 grid of junctions with **both lanes** on every span:
         *
         *  ```
         *      NW <====> NE
         *      ^          ^
         *      ||        ||        every span is two opposed one-way links
         *      v          v
         *      SW <====> SE
         *  ```
         */
        fun grid(name: String): GuidedPathNetwork {
            val b = GuidedPathNetwork.builder(name)
                .intersection("NW", x = 0.0, y = 100.0)
                .intersection("NE", x = 100.0, y = 100.0)
                .intersection("SE", x = 100.0, y = 0.0)
                .intersection("SW", x = 0.0, y = 0.0)
            // Each span twice, once each way. A lane is a link; the pair is the two-way aisle.
            for ((a, c) in listOf("NW" to "NE", "NE" to "SE", "SE" to "SW", "SW" to "NW")) {
                b.link("$a-$c", a, c, length = 100.0, zoneLength = 25.0)
                b.link("$c-$a", c, a, length = 100.0, zoneLength = 25.0)
            }
            return b.station("Dock", "SW").station("Pick", "NE").build()
        }
    }

    @Test
    @DisplayName("opposed lanes on the same span are one network, and the builder accepts them")
    fun twoLanesAreOneNetwork() {
        val net = grid("Grid")
        // Eight links: four spans, two lanes each. Nothing keys on the endpoint pair.
        assertEquals(8, net.links.size, "a lane is a link, so four two-way spans are eight links")
        assertTrue(net.links.all { it.type == LinkType.UNIDIRECTIONAL },
            "a lane is one-way; the pair is what makes the aisle two-way")
        // Every junction is reachable from every other, in one network.
        for (from in listOf("NW", "NE", "SE", "SW")) {
            for (to in listOf("NW", "NE", "SE", "SW")) {
                if (from == to) continue
                assertTrue(net.distance(net.requireLocation(from), net.requireLocation(to)) < Double.MAX_VALUE,
                    "$from should reach $to without leaving the network")
            }
        }
    }

    @Test
    @DisplayName("the return lane is the shortest way back, and a U-turn is an ordinary route")
    fun theReturnLaneIsRoutable() {
        val net = grid("Grid2")
        val nw = net.requireLocation("NW")
        val ne = net.requireLocation("NE")
        // Out and back cost the same, because the return lane is the same length as the outbound.
        assertEquals(net.distance(nw, ne), net.distance(ne, nw), 1e-12,
            "the paired lane makes the journey symmetric without a bidirectional link")
        // And it is one span each way rather than three sides of the square, which is what says
        // routing found the return lane rather than going round.
        assertEquals(100.0, net.distance(ne, nw), 1e-12)
    }

    @Test
    @DisplayName("a junction admits one vehicle at a time, though the two lanes never touch")
    fun aJunctionIsAZone() {
        val m = Model("Crossing")
        val shop = CrossingShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        // The two vehicles share no link whatever: one runs W->C->E, the other S->C->W, and the
        // lane from W to C is a different object from the lane from C to W. What they share is the
        // junction, and a junction is a zone.
        val blocked = shop.cartA.numTimesBlocked.acrossReplicationStatistic.average +
                shop.cartB.numTimesBlocked.acrossReplicationStatistic.average
        assertTrue(
            blocked > 0.0,
            "crossing routes contend for the junction even with no lane in common: a second lane " +
                    "decongests the aisle, never the crossroads"
        )
    }

    @Test
    @DisplayName("a vehicle turns round at a junction by taking the return lane")
    fun aVehicleChangesDirectionAtAJunction() {
        val m = Model("Turning")
        val shop = TurningShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        // Out W->C->E is 200, back E->C->W is 200. A cart that could not change direction at the
        // junction could not have made the second trip at all; one that went some other way round
        // would show more ground than this. There is no other way round here, which is the point:
        // the return lane is the whole mechanism, and turning is routing rather than a manoeuvre.
        assertEquals(2.0, shop.cart.numTasksCompleted.acrossReplicationStatistic.average, 1e-12,
            "the cart should have carried out and back")
        assertEquals(400.0, shop.cart.distanceTravelled, 1e-9,
            "200 out and 200 back, so the return was one span and not a detour")
    }

    /**
     *  A crossroads with a lane each way on all three arms:
     *
     *  ```
     *          W <==> C <==> E
     *                 ^
     *                 ||
     *                 v
     *                 S
     *  ```
     *
     *  One load waits at W for E, another at S for W. The nearest-vehicle rule gives each to the
     *  cart standing on it, both are 100 from C at 10 a unit, and so both want C at t=10.
     */
    private class CrossingShop(parent: ModelElement) : ProcessModel(parent, "CrossingShop") {

        val network: GuidedPathNetwork = run {
            val b = GuidedPathNetwork.builder("Crossroads")
                .intersection("C", x = 100.0, y = 100.0)
                .intersection("W", x = 0.0, y = 100.0)
                .intersection("E", x = 200.0, y = 100.0)
                .intersection("S", x = 100.0, y = 0.0)
            for (arm in listOf("W", "E", "S")) {
                b.link("$arm-C", arm, "C", length = 100.0, zoneLength = 50.0)
                b.link("C-$arm", "C", arm, length = 100.0, zoneLength = 50.0)
            }
            b.build()
        }

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Fleet")

        val cartA = AgvVehicle(agv, TransporterPlacement.At("W"), ConstantRV(10.0), name = "CartA")
        val cartB = AgvVehicle(agv, TransporterPlacement.At("S"), ConstantRV(10.0), name = "CartB")

        inner class Load(private val from: String, private val to: String) : Entity() {
            val haul = process {
                currentLocation = network.requireLocation(from)
                transportByFleet(agv, destination = to, origin = from)
            }
        }

        override fun initialize() {
            activate(Load("W", "E").haul)
            activate(Load("S", "W").haul)
        }
    }

    /**
     *  One cart, one crossroads, and two hauls that face opposite ways: W to E, then E back to W.
     */
    private class TurningShop(parent: ModelElement) : ProcessModel(parent, "TurningShop") {

        val network: GuidedPathNetwork = run {
            val b = GuidedPathNetwork.builder("TurningRoads")
                .intersection("C", x = 100.0, y = 100.0)
                .intersection("W", x = 0.0, y = 100.0)
                .intersection("E", x = 200.0, y = 100.0)
            for (arm in listOf("W", "E")) {
                b.link("$arm-C", arm, "C", length = 100.0, zoneLength = 50.0)
                b.link("C-$arm", "C", arm, length = 100.0, zoneLength = 50.0)
            }
            b.build()
        }

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Fleet")
        val cart = AgvVehicle(agv, TransporterPlacement.At("W"), ConstantRV(10.0), name = "Cart")

        inner class Out : Entity() {
            val haul = process {
                currentLocation = network.requireLocation("W")
                transportByFleet(agv, destination = "E", origin = "W")
                activate(Back().haul)
            }
        }

        inner class Back : Entity() {
            val haul = process {
                currentLocation = network.requireLocation("E")
                transportByFleet(agv, destination = "W", origin = "E")
            }
        }

        override fun initialize() {
            activate(Out().haul)
        }
    }
}
