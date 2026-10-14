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

import ksl.examples.book.chapter8.TestAndRepairShopWithGuidedTransporters
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.MoveToStagingAreaRule
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Baselines for the guided-path changes in R1.8, recorded on `develop` at `c06710b0c` before any change.
 *
 *  R1.8 stops counting a cart that queues behind the cart at its staging area as an obstruction, and measures a
 *  candidate transporter from the zone it holds rather than from the end of its link. Both are
 *  expected to move these numbers; they are pinned so that every move is seen and explained, and are
 *  updated deliberately when those changes land.
 */
class GuidedAllocationBaselineTest {

    /** A staging loop: four intersections, three carts, one staging area that idle carts queue for. */
    private class StagingLoop(parent: ModelElement) : ProcessModel(parent, "StagingLoop") {
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
        val carts = listOf(
            GuidedTransporter(system, TransporterPlacement.At("S"), ConstantRV(10.0), name = "Cart1"),
            GuidedTransporter(system, TransporterPlacement.OnZone("Approach.Zone3"), ConstantRV(10.0), name = "Cart2"),
            GuidedTransporter(system, TransporterPlacement.OnZone("Approach.Zone2"), ConstantRV(10.0), name = "Cart3")
        )
        val pool = GuidedTransporterPoolWithQ(
            this, system, carts, allocationRule = ClosestByNetworkDistanceRule(),
            idleDispositionRule = MoveToStagingAreaRule("S"), name = "Carts"
        )
        private val tba = ExponentialRV(6.0, 1)
        private val generator = EntityGenerator(::Part, tba, tba)

        inner class Part : Entity() {
            val flow: KSLProcess = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation("A")
                guidedTransport(
                    pool, destination = "B", pickupLocation = "A",
                    loadingDelay = ConstantRV(1.0), unLoadingDelay = ConstantRV(1.0)
                )
            }
        }
    }

    @Test
    @DisplayName("Staging loop under the default allocation rule (expected to change in R1.8)")
    fun stagingLoop() {
        val m = Model("StagingLoopBaseline", autoCSVReports = false)
        val loop = StagingLoop(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 10_000.0
        m.simulate()
        val obstructions = loop.system.numObstructionsDetected.acrossReplicationStatistic.average
        val blockedByIdle = loop.system.numBlockedByIdleVehicle.acrossReplicationStatistic.average
        val blocked = loop.carts.map { it.fracTimeBlocked.acrossReplicationStatistic.average }
        println("StagingLoop: obstructions = $obstructions, blockedByIdle = $blockedByIdle, fracTimeBlocked = $blocked")
        assertEquals(LOOP_OBSTRUCTIONS, obstructions, 1e-9)
        assertEquals(LOOP_BLOCKED_BY_IDLE, blockedByIdle, 1e-9)
        assertEquals(LOOP_FRAC_BLOCKED, blocked.joinToString(), "fracTimeBlocked per cart")
    }

    @Test
    @DisplayName("Chapter 8 test-and-repair shop with guided transporters (reviewed if R1.8 moves it)")
    fun chapter8GuidedShop() {
        val m = Model("Ch8GuidedBaseline", autoCSVReports = false)
        val shop = TestAndRepairShopWithGuidedTransporters(m)
        m.numberOfReplications = 2
        m.lengthOfReplication = 20_000.0
        m.simulate()
        val systemTime = shop.systemTime.acrossReplicationStatistic.average
        val transferTime = shop.transferTime.acrossReplicationStatistic.average
        println("Ch8 guided shop: systemTime = $systemTime, transferTime = $transferTime")
        assertEquals(CH8_SYSTEM_TIME, systemTime, 1e-9)
        assertEquals(CH8_TRANSFER_TIME, transferTime, 1e-9)
    }

    companion object {
        // Recorded on develop @ c06710b0c, before the R1.8 changes.
        const val LOOP_OBSTRUCTIONS = 167.66666666666666
        const val LOOP_BLOCKED_BY_IDLE = 0.13410235825249214
        const val LOOP_FRAC_BLOCKED = "0.13102531925096605, 0.09268367909891655, 0.039194651663602524"
        const val CH8_SYSTEM_TIME = 375.577127270315
        const val CH8_TRANSFER_TIME = 17.72067045647517
    }
}
