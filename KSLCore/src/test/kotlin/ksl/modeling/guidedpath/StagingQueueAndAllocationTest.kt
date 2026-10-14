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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.FurthestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.GuidedTransporterAllocationRuleIfc
import ksl.modeling.guidedpath.rules.MoveToStagingAreaRule
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Three guided-path changes in R1.8, on a loop A to B to C to S and back to A, with a staging area at S:
 *
 *  - a cart going to the staging area that stops behind the cart already parked there is in the staging
 *    queue, which is not an obstruction;
 *  - the distance-based allocation rules measure a cart from the zone it holds, so a cart queued on the
 *    approach to S no longer ties with the cart at S;
 *  - an entity can be given the location it starts at, instead of starting at the first intersection
 *    declared.
 */
class StagingQueueAndAllocationTest {

    /** One cart parked at S; another carries one part from A to B, is released, and is sent to S. */
    private class QueueAtStaging(parent: ModelElement) : ProcessModel(parent, "QueueAtStaging") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .intersection("A").intersection("B").intersection("C").intersection("S")
            .link("L1", "A", "B", length = 40.0, zoneLength = 10.0)
            .link("L2", "B", "C", length = 40.0, zoneLength = 10.0)
            .link("Approach", "C", "S", length = 30.0, zoneLength = 10.0)
            .link("L3", "S", "A", length = 20.0, zoneLength = 10.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val parked = GuidedTransporter(system, TransporterPlacement.At("S"), ConstantRV(10.0), name = "Parked")
        val worker = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Worker")
        val pool = GuidedTransporterPoolWithQ(
            this, system, listOf(parked, worker), idleDispositionRule = MoveToStagingAreaRule("S"), name = "Carts"
        )

        inner class Part : Entity() {
            val flow: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("A")
                guidedTransport(pool, destination = "B", pickupLocation = "A")
            }
        }

        override fun initialize() {
            activate(Part().flow, 1.0)
        }
    }

    @Test
    fun aCartQueueingAtItsStagingAreaIsNotAnObstruction() {
        val m = Model("QueueAtStagingModel", autoCSVReports = false)
        val model = QueueAtStaging(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        assertEquals(0.0, model.system.numObstructionsDetected.acrossReplicationStatistic.average, 0.0)
        assertEquals(0.0, model.system.numBlockedByIdleVehicle.acrossReplicationStatistic.average, 0.0)
        assertTrue(model.worker.fracTimeBlocked.acrossReplicationStatistic.average > 0.5,
            "the queued cart is still reported as blocked, which the documentation says")
    }

    /** A cart at S and a cart queued behind it; one pickup at A, chosen by [rule]. */
    private class OnePickup(
        parent: ModelElement,
        rule: GuidedTransporterAllocationRuleIfc,
        queuedZone: String = "Approach.Zone3"
    ) :
        ProcessModel(parent, "OnePickup") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .intersection("A").intersection("B").intersection("C").intersection("S")
            .link("L1", "A", "B", length = 40.0, zoneLength = 10.0)
            .link("L2", "B", "C", length = 40.0, zoneLength = 10.0)
            .link("Approach", "C", "S", length = 30.0, zoneLength = 10.0)
            .link("L3", "S", "A", length = 20.0, zoneLength = 10.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        // declared first, but queued on the approach behind the cart at S
        val queued = GuidedTransporter(system, TransporterPlacement.OnZone(queuedZone), ConstantRV(10.0), name = "Queued")
        val staged = GuidedTransporter(system, TransporterPlacement.At("S"), ConstantRV(10.0), name = "Staged")
        var delivered = 0
        var chosen: String? = null

        /** Records which transporter the rule chose, when it chose it. */
        private val recording = object : GuidedTransporterAllocationRuleIfc {
            override fun selectTransporter(
                network: GuidedPathNetwork,
                pickup: GuidedPathNetwork.Intersection,
                candidates: List<GuidedTransporter>
            ): GuidedTransporter = rule.selectTransporter(network, pickup, candidates).also { chosen = it.name }
        }

        val pool = GuidedTransporterPoolWithQ(
            this, system, listOf(queued, staged), allocationRule = recording,
            idleDispositionRule = MoveToStagingAreaRule("S"), name = "Carts"
        )

        inner class Part : Entity() {
            val flow: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("A")
                guidedTransport(pool, destination = "B", pickupLocation = "A")
                delivered++
            }
        }

        override fun initialize() {
            delivered = 0
            chosen = null
            activate(Part().flow, 1.0)
        }
    }

    private fun onePickup(rule: GuidedTransporterAllocationRuleIfc, queuedZone: String = "Approach.Zone3"): OnePickup {
        val m = Model("OnePickupModel", autoCSVReports = false)
        val model = OnePickup(m, rule, queuedZone)
        m.numberOfReplications = 1
        m.lengthOfReplication = 1000.0
        m.simulate()
        return model
    }

    @Test
    fun theClosestRuleSendsTheCartInFront() {
        val model = onePickup(ClosestByNetworkDistanceRule())
        assertEquals("Staged", model.chosen, "both are 20 from A, but the queued cart still has S to enter")
        assertEquals(1, model.delivered)
    }

    @Test
    fun theFurthestRuleSendsTheCartBehind() {
        val model = onePickup(FurthestByNetworkDistanceRule())
        assertEquals("Queued", model.chosen)
    }

    @Test
    fun aCartPartWayAlongTheApproachIsFurther() {
        // at the start of the approach the queued cart has 20 more to cover: 40 from A against 20
        val model = onePickup(ClosestByNetworkDistanceRule(), queuedZone = "Approach.Zone1")
        assertEquals("Staged", model.chosen)
        val network = model.network
        val a = network.intersection("A")!!
        val zone1 = network.zones.first { it.name == "Approach.Zone1" }
        assertEquals(40.0, network.pathLength(zone1, true, a), 1e-9)
        assertEquals(20.0, network.pathLength(network.intersection("S")!!.zone, true, a), 1e-9)
    }

    /** A part that should be collected at Enter; where it starts is given, not left to declaration order. */
    private class StartAt(parent: ModelElement, enterFirst: Boolean) : ProcessModel(parent, "StartAt") {
        val network: GuidedPathNetwork = run {
            val b = GuidedPathNetwork.builder("Line")
            if (enterFirst) b.intersection("Enter").intersection("Depot")
            else b.intersection("Depot").intersection("Enter")
            b.intersection("Exit")
                .link("L1", "Depot", "Enter", length = 100.0, zoneLength = 10.0)
                .link("L2", "Enter", "Exit", length = 50.0, zoneLength = 10.0)
                .link("L3", "Exit", "Depot", length = 100.0, zoneLength = 10.0)
                .build()
        }

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        private val cart = GuidedTransporter(system, TransporterPlacement.At("Exit"), ConstantRV(10.0), name = "Cart")
        private val pool = GuidedTransporterPoolWithQ(this, system, listOf(cart), name = "Carts")
        var routeLength = Double.NaN

        inner class Part : Entity(null, network.requireLocation("Enter")) {
            val flow: KSLProcess = process {
                routeLength = guidedTransport(pool, destination = "Exit").routeLength
            }
        }

        override fun initialize() {
            activate(Part().flow)
        }
    }

    @Test
    fun anEntityGivenAStartingLocationStartsThere() {
        for (enterFirst in listOf(false, true)) {
            val m = Model("StartAtModel", autoCSVReports = false)
            val model = StartAt(m, enterFirst)
            m.numberOfReplications = 1
            m.lengthOfReplication = 1000.0
            m.simulate()
            assertEquals(50.0, model.routeLength, 1e-9, "Enter to Exit, whichever intersection was declared first")
        }
    }
}
