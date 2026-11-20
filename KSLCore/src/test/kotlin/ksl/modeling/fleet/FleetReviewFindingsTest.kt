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
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.exceptions.FleetProtocolException
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.guidedpath.GuidedPathNetwork
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
 *  Fleet cases where a load's wait, a vehicle's room, a short turn or the stop control could go wrong.
 *
 *  Ring: N, E, S, W with links 100 long and the depot at Park on a 20-long spur; one cart at the depot, speed 10.
 *  Loop: A, B, C, D with links 100 long; one bus at A, speed 10, on the circular line.
 */
class FleetReviewFindingsTest {

    // ---------- the ring ----------

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart")
            .apply { homeBase = AgvTestNetworks.DEPOT }
        val log = mutableListOf<String>()
        val tasks = mutableMapOf<String, Dispatcher.TransportTask>()
        val setups = mutableListOf<Shop.() -> Unit>()

        /** Asks for T2 from the far station, then T1 from the near one, and waits for T1 only. */
        inner class TwoAsks : Entity("L") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                tasks["T2"] = requestFleetTransport(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.FAR)
                val t1 = requestFleetTransport(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.NEAR)
                tasks["T1"] = t1
                awaitFleetTransport(t1)
                log.add("returned@$time T1=${t1.state}")
            }
        }

        inner class Load(val tag: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(AgvTestNetworks.NEAR)
                transportByFleet(agv, AgvTestNetworks.DROP, origin = AgvTestNetworks.NEAR)
                log.add("$tag delivered@$time")
            }
        }

        override fun initialize() {
            log.clear()
            tasks.clear()
            for (s in setups) s()
        }
    }

    private fun runShop(setup: Shop.() -> Unit): Pair<Shop, Throwable?> {
        val model = Model("FleetFindings${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return shop to error
    }

    @Test
    @DisplayName("a load with two transports asked for and waiting for one is not taken by the vehicle of the other")
    fun loadTakenOnlyForTheTaskItWaitsFor() {
        val (shop, error) = runShop { activate(TwoAsks().p) }
        assertNull(error, "the run stopped: $error")
        // The only cart is given T2, which was posted first, and waits at the far station for a load that waits for
        // T1: T1 is never collected, and the wait for it never returns.
        assertEquals(emptyList(), shop.log)
        assertEquals(TaskState.ASSIGNED, shop.tasks.getValue("T2").state)
        assertEquals(TaskState.POSTED, shop.tasks.getValue("T1").state)
    }

    @Test
    @DisplayName("a short turn is refused at a stop with a transport still to collect or set down after it")
    fun shortTurnRefusedBeforeATransportStop() {
        val (shop, error) = runShop {
            cart.stopControl = object : StopControlIfc {
                override suspend fun KSLProcessBuilder.instruct(vehicle: FleetVehicle, stop: TourStop, tour: Tour): StopInstruction =
                    StopInstruction.ServeAndEndTour
            }
            activate(Load("A").p)
        }
        assertTrue(error is FleetProtocolException, "expected the short turn to be refused, but got $error; log=${shop.log} cart=${shop.cart.currentLocationName} aboard=${shop.cart.numLoadsAboard}")
    }

    // ---------- the loop ----------

    private class Town(parent: ModelElement, capacity: Int, val cycles: Int, policy: AssignmentPolicyIfc) :
        ProcessModel(parent, "Town") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 50.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 50.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 50.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, assignmentPolicy = policy, name = "Fleet")
        val stopA = Stop(fleet, "A", "StopA")
        val stopB = Stop(fleet, "B", "StopB")
        val stopC = Stop(fleet, "C", "StopC")
        val stopD = Stop(fleet, "D", "StopD")
        val line = Line(
            "Circular", listOf(LineStop(stopA), LineStop(stopB), LineStop(stopC), LineStop(stopD)), cyclic = true
        )
        val bus = AgvVehicle(fleet, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Bus", loadCapacity = capacity)
        val log = mutableListOf<String>()
        val setups = mutableListOf<Town.() -> Unit>()

        inner class Rider(tag: String, val from: Stop, val to: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from.location)
                rideFrom(from, to)
                log.add("${this@Rider.name} alighted@$time at=${currentLocation.name}")
            }
        }

        inner class Load(tag: String, val origin: String, val destination: String, val at: Double) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(at)
                currentLocation = network.requireLocation(origin)
                transportByFleet(fleet, destination, origin = origin)
                log.add("${this@Load.name} delivered@$time")
            }
        }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            repeat(cycles) { fleet.dispatcher.postLine(line) }
            for (s in setups) s()
        }
    }

    private fun runTown(
        capacity: Int = 1, cycles: Int = 6, policy: AssignmentPolicyIfc = NearestVehiclePolicy(),
        setup: Town.() -> Unit
    ): Pair<Town, Throwable?> {
        val model = Model("LineFindings${System.nanoTime()}", autoCSVReports = false)
        val town = Town(model, capacity, cycles, policy)
        town.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 300.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return town to error
    }

    @Test
    @DisplayName("a bus full of riders at the end of its last cycle is not offered a transport it has no room for")
    fun fullBusNotOfferedATransport() {
        val (town, error) = runTown(capacity = 1, cycles = 1) {
            activate(Rider("R", stopC, "B").p)
            activate(Load("T", "A", "C", 1.0).p)
        }
        assertNull(error, "the run stopped: $error")
        assertTrue(town.log.any { it.startsWith("T delivered@") }, "the transport was never delivered: ${town.log}")
    }

    @Test
    @DisplayName("riders do not take the seat a pickup later in the same round needs")
    fun ridersLeaveTheSeatAPickupNeeds() {
        // batched, so that the cycle and the transport reach the policy in one pass and go to the bus together
        val policy = ksl.modeling.fleet.policies.BatchedAssignmentPolicy(window = 1.0, inner = ConsolidatingPolicy())
        val (town, error) = runTown(capacity = 2, cycles = 1, policy = policy) {
            activate(Rider("R1", stopB, "D").p)
            activate(Rider("R2", stopB, "D").p)
            activate(Load("T", "C", "D", 0.0).p)
        }
        assertNull(error, "the run stopped: $error")
        assertTrue(town.log.any { it.startsWith("T delivered@") }, "the transport was never delivered: ${town.log}")
        assertEquals(1.0, town.stopB.numBoarded.value, "one rider left a seat for the pickup at C: ${town.log}")
    }

    @Test
    @DisplayName("the stops of a cancelled line cycle are passed over without the stop control being asked about them")
    fun controlNotAskedAboutStopsNoLongerOwned() {
        val asked = mutableListOf<Pair<Double, Dispatcher.Task?>>()
        var cancelled: Dispatcher.Task? = null
        val (_, error) = runTown(capacity = 2, cycles = 2) {
            bus.stopControl = object : StopControlIfc {
                override suspend fun KSLProcessBuilder.instruct(vehicle: FleetVehicle, stop: TourStop, tour: Tour): StopInstruction {
                    asked.add(time to stop.madeFor)
                    return StopInstruction.Serve()
                }
            }
            at(15.0) {
                cancelled = bus.currentAssignment!!.task
                fleet.dispatcher.cancel(cancelled!!)
            }
        }
        assertNull(error, "the run stopped: $error")
        val late = asked.filter { it.first > 15.0 && it.second === cancelled }
        assertEquals(emptyList(), late, "the control was asked about stops of the cancelled cycle")
    }
}
