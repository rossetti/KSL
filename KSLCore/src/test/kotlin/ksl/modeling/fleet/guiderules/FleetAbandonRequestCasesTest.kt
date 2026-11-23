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

package ksl.modeling.fleet.guiderules

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.AgvTestNetworks
import ksl.modeling.fleet.FleetSystem
import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.FreePathFleet
import ksl.modeling.fleet.FreePathVehicle
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A rule of the fleet guide's "...abandon an outstanding request?" (`ksl-fleet.md`): at the end of a replication,
 *  `ProcessModel.afterReplication` terminates every suspended load without help from the fleet. This class checks,
 *  on a guide path and on a free path, each way a load can be suspended on a transport when the replication ends:
 *  waiting on the board with no vehicle, waiting with a vehicle on its way to it, and riding.
 *
 *  One cart, moving at 10, serves two loads that ask at 0: load A, 120 from the cart on the guide path (100 on the
 *  free path), and load B, which waits for the cart. A replication of 5 ends with the cart on its way to A; one of 15
 *  ends with A aboard. In both, B is still on the board.
 */
class FleetAbandonRequestCasesTest {

    /** Builds the loads in [initialize] and reads, in [replicationEnded], how the replication left them. */
    private abstract class Site(parent: ModelElement, name: String) : ProcessModel(parent, name) {
        abstract val system: FleetSystem
        abstract val cart: FleetVehicle
        val loads = mutableListOf<KSLProcess>()
        var atEnd = ""

        abstract fun load(tag: String, from: String, to: String): KSLProcess

        abstract val pickups: Pair<String, String>
        abstract val drop: String

        override fun initialize() {
            loads.clear()
            loads.add(load("A", pickups.first, drop).also { activate(it) })
            loads.add(load("B", pickups.second, drop).also { activate(it) })
        }

        override fun replicationEnded() {
            atEnd = "aboard=${cart.numLoadsAboard} awaiting=${system.awaitingPickupHoldQ.size} " +
                    "riding=${system.inTransitHoldQ.size} committed=${cart.currentAssignment != null}"
        }
    }

    private class Ring(parent: ModelElement) : Site(parent, "Ring") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        override val system: FleetSystem get() = agv
        override val cart: FleetVehicle = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = AgvTestNetworks.DEPOT }
        override val pickups = AgvTestNetworks.NEAR to AgvTestNetworks.FAR
        override val drop = AgvTestNetworks.DROP

        inner class Load(tag: String, val from: String, val to: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from)
                transportByFleet(agv, to, origin = from)
            }
        }

        override fun load(tag: String, from: String, to: String): KSLProcess = Load(tag, from, to).p
    }

    private class Yard(parent: ModelElement) : Site(parent, "Yard") {
        val plane = Euclidean2DPlane()
        val places = listOf(
            plane.Point(0.0, 0.0, "Depot"),
            plane.Point(100.0, 0.0, "Press"),
            plane.Point(300.0, 0.0, "Paint"),
            plane.Point(100.0, 100.0, "Ship")
        )

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, places, name = "Fleet")
        override val system: FleetSystem get() = fleet
        override val cart: FleetVehicle = FreePathVehicle(fleet, "Depot", ConstantRV(10.0), name = "Cart", stepSize = 5.0)
        override val pickups = "Press" to "Paint"
        override val drop = "Ship"

        inner class Load(tag: String, val from: String, val to: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = fleet.space.requireLocation(from)
                transportByFleet(fleet, to, origin = from)
            }
        }

        override fun load(tag: String, from: String, to: String): KSLProcess = Load(tag, from, to).p
    }

    /** Runs one replication of [length] and returns how it ended and whether each load's process was terminated. */
    private fun end(model: Model, site: Site, length: Double): Pair<String, List<Boolean>> {
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        return site.atEnd to site.loads.map { it.isTerminated }
    }

    @Test
    @DisplayName("guide path: a load with the vehicle on its way and a load on the board are terminated at the end")
    fun guidePathVehicleOnItsWay() {
        val model = Model("RingEnd5${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(model)
        assertEquals("aboard=0 awaiting=2 riding=0 committed=true" to listOf(true, true), end(model, ring, 5.0))
    }

    @Test
    @DisplayName("guide path: a load riding and a load on the board are terminated at the end")
    fun guidePathRiding() {
        val model = Model("RingEnd15${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(model)
        assertEquals("aboard=1 awaiting=1 riding=1 committed=true" to listOf(true, true), end(model, ring, 15.0))
    }

    @Test
    @DisplayName("free path: a load with the vehicle on its way and a load on the board are terminated at the end")
    fun freePathVehicleOnItsWay() {
        val model = Model("YardEnd5${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model)
        assertEquals("aboard=0 awaiting=2 riding=0 committed=true" to listOf(true, true), end(model, yard, 5.0))
    }

    @Test
    @DisplayName("free path: a load riding and a load on the board are terminated at the end")
    fun freePathRiding() {
        val model = Model("YardEnd15${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model)
        assertEquals("aboard=1 awaiting=1 riding=1 committed=true" to listOf(true, true), end(model, yard, 15.0))
    }
}
