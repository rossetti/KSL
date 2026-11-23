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
import ksl.modeling.fleet.Battery
import ksl.modeling.fleet.FailureModel
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
import kotlin.test.assertTrue

/**
 *  A rule of the fleet guide's "...make vehicles break down?" (`ksl-fleet.md`): a breakdown is never counted as
 *  stranding; `NumVehiclesStranded` and `NumTimesStranded` count only vehicles that ran out of charge. A breakdown on
 *  a trip of the vehicle's own, on a guide path, is checked elsewhere; this class checks a breakdown while carrying a
 *  load and on the way to a pickup, on a guide path, and a breakdown while carrying a load on a free path. In each,
 *  the vehicle has a battery with plenty of charge, breaks down after 150 travelled at 10, and its repair takes 1000,
 *  so it stands broken down at the end of the replication.
 */
class FleetBreakdownsCasesTest {

    private val failAt = { distance: Double -> FailureModel.distanceBased(ConstantRV(distance), ConstantRV(1000.0)) }

    private fun battery() = Battery(capacity = 10_000.0, chargePerDistance = 1.0)

    /** What a replication left: the failures, the two stranding counts, and the loads aboard and delivered. */
    private data class Outcome(val failures: Double, val vehiclesStranded: Double, val timesStranded: Double,
                               val aboard: Int, val delivered: Int)

    /** One cart at the depot of the ring; one load at NearStation, 120 away, asks at 0 to go to DropPoint, 100 on. */
    private class Ring(parent: ModelElement, failure: FailureModel, battery: Battery) : ProcessModel(parent, "Ring") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart",
            failureModel = failure, battery = battery
        ).apply { homeBase = AgvTestNetworks.DEPOT }
        var delivered = 0
        var aboardAtEnd = -1

        inner class Load : Entity("Load") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                transportByFleet(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.NEAR)
                delivered++
            }
        }

        override fun initialize() {
            delivered = 0
            aboardAtEnd = -1
            activate(Load().p)
        }

        override fun replicationEnded() {
            aboardAtEnd = cart.numLoadsAboard
        }
    }

    /** One cart at Depot on a plane; one load at Press, 100 away, asks at 0 to go to Ship, 100 on. */
    private class Yard(parent: ModelElement, failure: FailureModel, battery: Battery) : ProcessModel(parent, "Yard") {
        val plane = Euclidean2DPlane()
        val places = listOf(
            plane.Point(0.0, 0.0, "Depot"),
            plane.Point(100.0, 0.0, "Press"),
            plane.Point(100.0, 100.0, "Ship")
        )

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, places, name = "Fleet")
        val cart = FreePathVehicle(
            fleet, "Depot", ConstantRV(10.0), name = "Cart", stepSize = 5.0, battery = battery, failureModel = failure
        )
        var delivered = 0
        var aboardAtEnd = -1

        inner class Load : Entity("Load") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = fleet.space.requireLocation("Press")
                transportByFleet(fleet, "Ship", origin = "Press")
                delivered++
            }
        }

        override fun initialize() {
            delivered = 0
            aboardAtEnd = -1
            activate(Load().p)
        }

        override fun replicationEnded() {
            aboardAtEnd = cart.numLoadsAboard
        }
    }

    private fun outcome(model: Model, system: FleetSystem, cart: FleetVehicle, aboard: () -> Int, delivered: () -> Int): Outcome {
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        return Outcome(
            cart.numFailures!!.acrossReplicationStatistic.average,
            system.numVehiclesStranded.acrossReplicationStatistic.average,
            cart.numTimesStranded!!.acrossReplicationStatistic.average,
            aboard(), delivered()
        )
    }

    @Test
    @DisplayName("a guide-path vehicle that breaks down carrying a load is not counted as stranded")
    fun guidePathBreakdownWhileLoaded() {
        // 120 empty to the pickup, then 30 into the 100 to the drop: it breaks down at 15 with the load aboard
        val model = Model("LoadedFail${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(model, failAt(150.0), battery())
        val o = outcome(model, ring.agv, ring.cart, { ring.aboardAtEnd }, { ring.delivered })
        assertEquals(Outcome(1.0, 0.0, 0.0, aboard = 1, delivered = 0), o)
    }

    @Test
    @DisplayName("a guide-path vehicle that breaks down on its way to a pickup is not counted as stranded")
    fun guidePathBreakdownOnTheWayToAPickup() {
        // 50 into the 120 to the pickup: it breaks down at 5, empty
        val model = Model("EmptyFail${System.nanoTime()}", autoCSVReports = false)
        val ring = Ring(model, failAt(50.0), battery())
        val o = outcome(model, ring.agv, ring.cart, { ring.aboardAtEnd }, { ring.delivered })
        assertEquals(Outcome(1.0, 0.0, 0.0, aboard = 0, delivered = 0), o)
    }

    @Test
    @DisplayName("a free-path vehicle that breaks down carrying a load is not counted as stranded")
    fun freePathBreakdownWhileLoaded() {
        // 100 empty to the pickup, then 50 into the 100 to Ship: it breaks down at 15 with the load aboard
        val model = Model("FreePathFail${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model, failAt(150.0), battery())
        val o = outcome(model, yard.fleet, yard.cart, { yard.aboardAtEnd }, { yard.delivered })
        assertEquals(Outcome(1.0, 0.0, 0.0, aboard = 1, delivered = 0), o)
    }

    @Test
    @DisplayName("the control: a free-path vehicle that runs out of charge carrying a load is counted as stranded")
    fun freePathRunningOutOfChargeIsStranding() {
        // no breakdown, and a battery of 150: it runs flat 50 into the loaded trip
        val model = Model("FreePathFlat${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model, failAt(1.0e9), Battery(capacity = 150.0, chargePerDistance = 1.0))
        val o = outcome(model, yard.fleet, yard.cart, { yard.aboardAtEnd }, { yard.delivered })
        assertEquals(0.0, o.failures, "failures")
        assertEquals(1.0, o.vehiclesStranded, "NumVehiclesStranded")
        assertEquals(1.0, o.timesStranded, "NumTimesStranded")
        assertTrue(o.delivered == 0, "the load was delivered")
    }
}
