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
package ksl.animation

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.spatial.MovableResource
import ksl.modeling.station.integration.ProcessStation
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The entity a movable resource uses to go home is the library's: a model's own entity type of a common name
 *  is listed as the model's, whichever the inventory meets first.
 */
class LibraryEntityNameCollisionTest {

    /** A model whose own entity type is named `Driver`, not registered, beside a movable resource with a home. */
    private class Depot(parent: ModelElement) : ProcessModel(parent, "Depot") {
        inner class Driver : Entity("Driver") {
            @KSLAnimatedProcess(name = "shift")
            val shift: KSLProcess = process { delay(1.0) }
        }
    }

    private fun inventoryWith(movableFirst: Boolean): AnimationInventory {
        val model = Model("Collision$movableFirst${System.nanoTime()}", autoCSVReports = false)
        val plane = Euclidean2DPlane()
        model.spatialModel = plane
        val home = plane.Point(0.0, 0.0, "Home")
        fun cart() = MovableResource(model, home, ConstantRV(1.0), name = "Cart").also { it.initialHomeBase = home }
        if (movableFirst) { cart(); Depot(model) } else { Depot(model); cart() }
        return model.animationInventory()
    }

    @Test
    @DisplayName("a model's entity type named Driver is the model's, met after a movable resource or before it")
    fun modelDriverIsTheModels() {
        for (movableFirst in listOf(true, false)) {
            val drivers = inventoryWith(movableFirst).entityTypes.filter { it.typeName == "Driver" }
            assertEquals(1, drivers.size, "movable first = $movableFirst")
            val driver = drivers.single()
            assertTrue(driver.include, "the model's Driver is captured (movable first = $movableFirst)")
            assertEquals(listOf("shift"), driver.processes.map { it.name }, "movable first = $movableFirst")
        }
    }

    /** A model whose own entity type is named `Carrier`, beside a process station. */
    private class Yard(parent: ModelElement) : ProcessModel(parent, "Yard") {
        inner class Carrier : Entity("Carrier") {
            @KSLAnimatedProcess(name = "haul")
            val haul: KSLProcess = process { delay(1.0) }
        }
    }

    @Test
    @DisplayName("a model's entity type named Carrier is the model's, beside a process station, whose own is listed apart")
    fun modelCarrierIsTheModels() {
        for (stationFirst in listOf(true, false)) {
            val model = Model("Carrier$stationFirst${System.nanoTime()}", autoCSVReports = false)
            fun station() = ProcessStation(model, { delay(1.0) }, name = "Station")
            if (stationFirst) { station(); Yard(model) } else { Yard(model); station() }
            val types = model.animationInventory().entityTypes
            val carrier = types.single { it.typeName == "Carrier" }
            assertTrue(carrier.include && !carrier.isInternal, "station first = $stationFirst")
            assertEquals(listOf("haul"), carrier.processes.map { it.name }, "station first = $stationFirst")
            val station = types.single { it.typeName == "ProcessStationCarrier" }
            assertTrue(station.include && !station.isInternal, "the station's entity runs the model's activity: it is offered")
        }
    }

    @Test
    @DisplayName("the movable resource's own driver is listed under its own name, and is not captured")
    fun homeBaseDriverIsListedApart() {
        val types = inventoryWith(movableFirst = true).entityTypes
        val library = types.filter { !it.include }
        val homeDriver = assertNotNull(library.singleOrNull(), "one excluded type: ${types.map { it.typeName }}")
        assertTrue(homeDriver.typeName != "Driver")
        assertTrue(homeDriver.processes.none { it.include })
    }
}
