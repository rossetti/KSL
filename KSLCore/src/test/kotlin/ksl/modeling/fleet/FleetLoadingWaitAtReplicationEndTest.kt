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
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A replication that ends while a fleet vehicle waits at a pickup for its load leaves nothing behind: every
 *  replication of the same deterministic model runs alike, with no error.
 *
 *  One AGV of load capacity 1 serves a ring of four 100-long links at speed 10. Load A asks at 0 for a transport and
 *  waits for it only at 100; load B asks at 1. The cart is assigned to A and waits at the pickup for A, so when each
 *  replication ends at 50 neither load has been delivered and both tasks are still open.
 */
class FleetLoadingWaitAtReplicationEndTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Ring")
            .link("NE", "N", "E", length = 100.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("ES", "E", "S", length = 100.0, zoneLength = 50.0, beginDirection = 90.0)
            .link("SW", "S", "W", length = 100.0, zoneLength = 50.0, beginDirection = 180.0)
            .link("WN", "W", "N", length = 100.0, zoneLength = 50.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(agv, TransporterPlacement.At("N"), ConstantRV(10.0), name = "Cart", loadCapacity = 1)
        val log = mutableListOf<String>()

        inner class Load(val tag: String, val from: Double, val split: Double) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation("E")
                val task = requestFleetTransport(agv, "S", "E", ConstantRV(0.0), ConstantRV.ZERO)
                if (split > 0.0) delay(split)
                awaitFleetTransport(task)
                log.add("$tag delivered@$time")
            }
        }

        override fun initialize() {
            log.clear()
            activate(Load("A", 0.0, 100.0).p)
            activate(Load("B", 1.0, 0.0).p)
        }
    }

    @Test
    @DisplayName("a cart waiting at the pickup for its load when the replication ends: every replication runs alike")
    fun cartWaitingForItsLoadAtReplicationEnd() {
        val model = Model("FleetLoadingWait${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        val records = mutableListOf<String>()
        object : ModelElement(model, "Recorder") {
            override fun replicationEnded() {
                val d = shop.agv.dispatcher
                records.add(
                    "log=${shop.log} posted=${d.numTasksPosted.value} completed=${d.numTasksCompleted.value} " +
                            "cancelled=${d.numTasksCancelled.value} made=${d.numAssignmentsMade.value} " +
                            "revoked=${d.numAssignmentsRevoked.value} cartAt=${shop.cart.currentLocationName}"
                )
            }
        }
        model.numberOfReplications = 3
        model.lengthOfReplication = 50.0
        val error = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(error, "the run stopped: $error")
        assertEquals(3, records.size)
        assertEquals(1, records.distinct().size, "replications differ: $records")
        val first = records.first()
        assertEquals(true, first.startsWith("log=[] posted=2.0 completed=0.0"), "no load is delivered by 50: $first")
    }
}
