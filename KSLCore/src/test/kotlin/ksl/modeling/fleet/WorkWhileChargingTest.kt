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
import ksl.modeling.fleet.exceptions.FleetDispatchException
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.ChargeWhenLowDisposition
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ParkInPlaceDisposition
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A vehicle on a charger isn't available. Work that arrives during the charge goes to another vehicle,
 * or waits for this one to finish. The vehicle stays where it is until the battery is full.
 *
 * The layout is a one-way ring of 40 foot links, A to B to C to A, with a spur from C to S and back of
 * 10 feet each way. Every vehicle moves at 10 feet per minute, so a ring link takes 4 minutes and the
 * spur 1. A full charge from the starting level takes 50 minutes.
 */
class WorkWhileChargingTest {

    private class Shop(
        parent: ModelElement,
        val askAt: Double,
        charger: String = "C",
        firstCartAt: String = "C",
        secondCartAt: String? = null,
        val pickup: String = "A",
        val destination: String = "B",
        policy: AssignmentPolicyIfc = NearestVehiclePolicy()
    ) : ProcessModel(parent, "Shop") {
        val net = GuidedPathNetwork.builder("Ring")
            .intersection("A").intersection("B").intersection("C").intersection("S")
            .link("AB", "A", "B", length = 40.0, zoneLength = 10.0)
            .link("BC", "B", "C", length = 40.0, zoneLength = 10.0)
            .link("CA", "C", "A", length = 40.0, zoneLength = 10.0)
            .link("CS", "C", "S", length = 10.0, zoneLength = 10.0)
            .link("SC", "S", "C", length = 10.0, zoneLength = 10.0)
            .build()

        init {
            spatialModel = net
        }

        val agv = AgvSystem(this, net, assignmentPolicy = policy, name = "Agv").also { it.addCharger(charger) }

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(firstCartAt), ConstantRV(10.0), name = "Cart",
            battery = Battery(capacity = 1000.0, chargePerDistance = 0.1, chargingRate = 10.0, initialCharge = 500.0)
        ).apply { dispositionPolicy = ChargeWhenLowDisposition(threshold = 0.6) }

        val other = secondCartAt?.let {
            AgvVehicle(agv, TransporterPlacement.At(it), ConstantRV(10.0), name = "Other")
                .apply { dispositionPolicy = ParkInPlaceDisposition() }
        }

        var delivered = Double.NaN
        var carriedBy: String? = null
        var availableWhenAsked = true
        var chargingWhenAsked = false

        inner class Part : Entity() {
            val p: KSLProcess = process {
                delay(askAt)
                availableWhenAsked = cart.isAvailable
                chargingWhenAsked = cart.isCharging
                currentLocation = net.requireLocation(pickup)
                val result = transportByFleet(agv, destination = destination, origin = pickup)
                carriedBy = result.vehicleName
                delivered = time
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Part().p)
        }
    }

    /** Decides late, and proposes from the list it was given without looking again. */
    private class StaleListPolicy : AssignmentPolicyIfc {
        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            val task = context.board.unassigned.firstOrNull() ?: return emptyList()
            val vehicle = context.available.firstOrNull() ?: return emptyList()
            delay(1.0)
            return listOf(AssignmentProposal(vehicle, task))
        }
    }

    private fun run(build: (Model) -> Shop): Shop {
        val m = Model("WorkWhileCharging", autoCSVReports = false)
        val shop = build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("a lone vehicle finishes its charge, then takes the work")
    fun workWaitsForTheChargeToFinish() {
        val s = run { Shop(it, askAt = 10.0) }
        assertFalse(s.availableWhenAsked, "a vehicle on a charger reported itself available")
        assertTrue(s.chargingWhenAsked, "the vehicle should have been charging when the part asked")
        // Off the charger at 50, then C to A and A to B.
        assertEquals(58.0, s.delivered, 1.0e-9)
        assertEquals(1.0, s.cart.numChargingSessions!!.value, 0.0)
    }

    @Test
    @DisplayName("another vehicle takes the work, and the charging one stays on the charger")
    fun anotherVehicleTakesTheWork() {
        val s = run { Shop(it, askAt = 10.0, secondCartAt = "A") }
        assertEquals("Other", s.carriedBy)
        assertEquals(14.0, s.delivered, 1.0e-9)
        assertEquals(0.0, s.cart.distanceTravelled, 0.0, "the charging vehicle was driven off")
        assertEquals(1.0, s.cart.numChargingSessions!!.value, 0.0)
    }

    @Test
    @DisplayName("work at the charger itself waits as well")
    fun workAtTheChargerWaits() {
        val s = run { Shop(it, askAt = 10.0, pickup = "C", destination = "A") }
        assertEquals(54.0, s.delivered, 1.0e-9)
    }

    @Test
    @DisplayName("work after the charge is unaffected, and the vehicle is available again")
    fun workAfterTheCharge() {
        val s = run { Shop(it, askAt = 100.0) }
        assertTrue(s.availableWhenAsked, "a vehicle off the charger should be available again")
        assertFalse(s.chargingWhenAsked)
        assertEquals(108.0, s.delivered, 1.0e-9)
    }

    @Test
    @DisplayName("a vehicle on a charger counts as charging, not idle")
    fun theCountsSeparateChargingFromIdle() {
        // 50 minutes on the charger and 8 on task, out of 200.
        val s = run { Shop(it, askAt = 100.0) }
        val charging = s.agv.numVehiclesCharging.withinReplicationStatistic.weightedAverage
        val onTask = s.agv.numVehiclesOnTask.withinReplicationStatistic.weightedAverage
        val idle = s.agv.numVehiclesIdle.withinReplicationStatistic.weightedAverage
        val out = s.agv.numVehiclesOutOfService.withinReplicationStatistic.weightedAverage
        assertEquals(50.0 / 200.0, charging, 1.0e-9)
        assertEquals(8.0 / 200.0, onTask, 1.0e-9)
        assertEquals(142.0 / 200.0, idle, 1.0e-9)
        assertEquals(0.0, out, 0.0)
    }

    @Test
    @DisplayName("the four counts add up to the fleet with work arriving during a charge")
    fun theCountsPartitionTheFleet() {
        val s = run { Shop(it, askAt = 10.0, secondCartAt = "A") }
        val charging = s.agv.numVehiclesCharging.withinReplicationStatistic.weightedAverage
        val onTask = s.agv.numVehiclesOnTask.withinReplicationStatistic.weightedAverage
        val idle = s.agv.numVehiclesIdle.withinReplicationStatistic.weightedAverage
        val out = s.agv.numVehiclesOutOfService.withinReplicationStatistic.weightedAverage
        assertEquals(50.0 / 200.0, charging, 1.0e-9)
        assertEquals(2.0, charging + onTask + idle + out, 1.0e-9)
    }

    @Test
    @DisplayName("a vehicle driving to a charger can still be turned round")
    fun aVehicleOnItsWayToTheChargerStaysAssignable() {
        val s = run { Shop(it, askAt = 1.0, firstCartAt = "B") }
        assertTrue(s.availableWhenAsked, "a vehicle driving to a charger should stay available")
        assertFalse(s.chargingWhenAsked)
        // Asked 10 feet out of B: on to C, then C to A and A to B.
        assertEquals(12.0, s.delivered, 1.0e-9)
    }

    @Test
    @DisplayName("an auction's only bidder reaches the charger before the deadline: the task is auctioned again")
    fun anAuctionsOnlyBidderStartsCharging() {
        val s = run {
            Shop(it, askAt = 3.5, firstCartAt = "B", policy = ContractNetAssignmentPolicy(1.0))
        }
        assertEquals(1.0, s.agv.dispatcher.numAuctionsUnfilled.value, 0.0)
        // B to C by 4, drawing 4 units, so 50.4 on the charger. A second auction takes 1, then C to A to B.
        assertEquals(4.0 + 50.4 + 1.0 + 8.0, s.delivered, 1.0e-9)
    }

    @Test
    @DisplayName("the low bidder reaches the charger before the deadline: the next bid wins")
    fun anAuctionsLowBidderStartsCharging() {
        // Asked at 0.5, the cart on the spur is 55 feet from A, and the other cart is 80.
        val s = run {
            Shop(it, askAt = 0.5, charger = "S", secondCartAt = "B", policy = ContractNetAssignmentPolicy(1.0))
        }
        assertEquals("Other", s.carriedBy)
        // Awarded at 1.5, then B to C to A to B.
        assertEquals(13.5, s.delivered, 1.0e-9)
        assertEquals(0.0, s.agv.dispatcher.numAuctionsUnfilled.value, 0.0)
    }

    @Test
    @DisplayName("a policy of its own that proposes a vehicle now charging is told why that failed")
    fun aStaleProposalNamesTheCharger() {
        val m = Model("StaleProposal", autoCSVReports = false)
        Shop(m, askAt = 3.5, firstCartAt = "B", policy = StaleListPolicy())
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        val e = assertFailsWith<FleetDispatchException> { m.simulate() }
        assertTrue(e.message!!.contains("charger"), "the message should say where the vehicle went: ${e.message}")
        assertTrue(e.message!!.contains("Cart"), "the message should name the vehicle: ${e.message}")
    }

    private class Yard(parent: ModelElement) : ProcessModel(parent, "Yard") {
        val plane = Euclidean2DPlane()
        val places = listOf(
            plane.Point(0.0, 0.0, "Charger"),
            plane.Point(40.0, 0.0, "Press"),
            plane.Point(80.0, 0.0, "Ship")
        )

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, places, name = "Fleet").also { it.addCharger("Charger") }

        val cart = FreePathVehicle(
            fleet, "Charger", ConstantRV(10.0), name = "Cart",
            battery = Battery(capacity = 1000.0, chargePerDistance = 0.1, chargingRate = 10.0, initialCharge = 500.0)
        ).apply { dispositionPolicy = ChargeWhenLowDisposition(threshold = 0.6) }

        var delivered = Double.NaN

        inner class Part : Entity() {
            val p: KSLProcess = process {
                delay(10.0)
                currentLocation = fleet.space.requireLocation("Press")
                transportByFleet(fleet, destination = "Ship", origin = "Press")
                delivered = time
            }
        }

        override fun initialize() {
            super.initialize()
            activate(Part().p)
        }
    }

    @Test
    @DisplayName("the same holds on a free path")
    fun theSameOnAFreePath() {
        val m = Model("FreePathCharging", autoCSVReports = false)
        val y = Yard(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        assertEquals(58.0, y.delivered, 1.0e-9)
    }
}
