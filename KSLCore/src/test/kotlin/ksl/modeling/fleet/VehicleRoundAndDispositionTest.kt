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

package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ReassigningPolicy
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A fleet vehicle's round and its disposition: a stop is made only for the award it was planned for, where it is,
 *  and a vehicle whose trip home is interrupted goes on home once it is put right. "Stranded" counts only vehicles
 *  out of charge.
 */
class VehicleRoundAndDispositionTest {

    private fun simulate(model: Model, length: Double): Throwable? {
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        return try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
    }

    /**
     *  One two-place cart at the depot on the one-way ring (Park, N, E=NearStation, S=DropPoint, W=FarStation; a link
     *  takes 10, the spur 2). A asks at 0 to go from FarStation (32 away) to DropPoint; T1 asks at 1 to go from
     *  NearStation (12 away) to DropPoint.
     */
    private class OneCart(parent: ModelElement, policy: AssignmentPolicyIfc) : ProcessModel(parent, "OneCart") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart", loadCapacity = 2
        ).apply { homeBase = AgvTestNetworks.DEPOT }
        val delivered = mutableMapOf<String, Double>()

        inner class Load(val tag: String, val from: Double, val origin: String, val destination: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation(origin)
                val task = requestFleetTransport(agv, destination, origin)
                awaitFleetTransport(task)
                delivered[tag] = time
            }
        }

        override fun initialize() {
            delivered.clear()
            activate(Load("A", 0.0, AgvTestNetworks.FAR, AgvTestNetworks.DROP).p)
            activate(Load("T1", 1.0, AgvTestNetworks.NEAR, AgvTestNetworks.DROP).p)
        }
    }

    @Test
    @DisplayName("a task taken back and given again to the same cart in one pass is collected where it waits")
    fun revokedAndGivenBackIsCollectedWhereItWaits() {
        // the re-tasking policy takes A back for the nearer T1, and the consolidating policy gives it straight back
        // behind T1; the cart cannot be at FarStation before 32, nor carry A round to DropPoint before 62
        val model = Model("OneCart${System.nanoTime()}", autoCSVReports = false)
        val shop = OneCart(model, ConsolidatingPolicy(ReassigningPolicy(50.0)))
        val error = simulate(model, 300.0)
        assertNull(error, "the run stopped: $error")
        val at = shop.delivered.getValue("A")
        assertTrue(at >= 62.0, "A was delivered at $at, before the cart could have collected it at FarStation")
    }

    @Test
    @DisplayName("the same loads with nothing taken back: the control")
    fun revokedAndGivenBackControl() {
        val model = Model("OneCart${System.nanoTime()}", autoCSVReports = false)
        val shop = OneCart(model, ConsolidatingPolicy(NearestVehiclePolicy()))
        assertNull(simulate(model, 300.0))
        assertEquals(setOf("A", "T1"), shop.delivered.keys)
    }

    /** A cart that carries one load and starts for home; distance-based failure at 300 travelled, on the way home. */
    private class HomeFail(parent: ModelElement) : ProcessModel(parent, "HomeFail") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart",
            failureModel = FailureModel.distanceBased(ConstantRV(300.0), ConstantRV(7.0))
        ).apply { homeBase = AgvTestNetworks.DEPOT }
        var endedAt: String? = null
        var strandedAtEnd: Boolean? = null

        inner class Load : Entity("A") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                transportByFleet(agv, AgvTestNetworks.DROP)
            }
        }

        override fun initialize() {
            activate(Load().p)
            schedule({ _: KSLEvent<Nothing> ->
                endedAt = cart.currentLocationName
                strandedAtEnd = cart.isStranded
            }, 250.0)
        }
    }

    @Test
    @DisplayName("a cart that breaks down on its way home goes on home after the repair, and is not counted as stranded")
    fun breakdownOnTheWayHome() {
        // it collects A at NearStation, sets it down at DropPoint and starts for home; the failure comes on the way,
        // at 300 travelled; the cart has no battery
        val model = Model("HomeFail${System.nanoTime()}", autoCSVReports = false)
        val shop = HomeFail(model)
        val error = simulate(model, 300.0)
        assertNull(error, "the run stopped: $error")
        // the depot stands at the Park intersection
        assertTrue(shop.endedAt == AgvTestNetworks.DEPOT || shop.endedAt == "Park", "the cart went on home, not ${shop.endedAt}")
        assertEquals(false, shop.strandedAtEnd)
        assertEquals(0.0, shop.agv.numVehiclesStranded.acrossReplicationStatistic.average, "a cart with no battery is never stranded")
    }
}
