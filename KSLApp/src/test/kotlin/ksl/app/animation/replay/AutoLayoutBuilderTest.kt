/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.animation.replay

import ksl.animation.AnimationInventory
import ksl.animation.AnimationLayout
import ksl.animation.ConveyorInfo
import ksl.animation.ConveyorLayoutElement
import ksl.animation.SegmentInfo
import ksl.animation.SegmentRoute
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-1 guard for the headless auto-layout builder extracted from the desktop app's
 * `AnimationAppController.buildAutoLayout`. The scaffold path + the model-derived overlays are unit-tested
 * here; the trace path and the DistancesModel source-selection guard are covered by the app suite
 * (AutoLayoutTest / AutoLayoutSourceTest / ScaffoldMoverHomeTest), which delegates to this same builder.
 */
class AutoLayoutBuilderTest {

    private class TinyQueueModel(parent: ModelElement) : ProcessModel(parent, "tiny") {
        @Suppress("unused") private val server = ResourceWithQ(this, "Server")
    }

    private fun model(): Model = Model("auto-layout-test").also { TinyQueueModel(it) }

    @Test
    fun scaffoldPathPlacesTheModelsResourceAndQueue() {
        // No trace supplied ⇒ AUTO falls through to the model scaffold.
        val layout = model().buildAutoLayout(source = AutoLayoutSource.AUTO)
        assertTrue(layout.resources.isNotEmpty(), "the model's resource is scaffolded; got ${layout.resources}")
        assertTrue(layout.queues.isNotEmpty(), "the resource's queue is scaffolded; got ${layout.queues}")
    }

    @Test
    fun modelOverlaysAreAdditiveAndSafeOnAnEmptyInventory() {
        val layout = model().buildAutoLayout()
        val empty = AnimationInventory()
        // The model overlays are additive: re-applying them with an empty inventory changes nothing.
        assertEquals(
            layout,
            layout.withModelGeometry(empty).withModelLocations(empty).withMoverPositionsAtHome(empty),
            "the model overlays must be no-ops given an empty inventory",
        )
    }

    @Test
    fun conveyorRoutesFilledAndAddedFromModelInventory() {
        val inventory = AnimationInventory(
            conveyorInfos = listOf(
                ConveyorInfo(
                    "Conveyor", cellSize = 1, accumulating = false,
                    segments = listOf(SegmentInfo("Enter", "Station1", 5), SegmentInfo("Station1", "Exit", 5)),
                ),
            ),
        )
        // (a) a route-less conveyor element (what the trace path creates) gets its belt route filled.
        val routeless = AnimationLayout(conveyors = listOf(ConveyorLayoutElement("Conveyor")))
        val filled = routeless.withModelConveyorRoutes(inventory).conveyors.single()
        assertEquals(2, filled.segments.size, "empty segments should be filled from the inventory")
        assertEquals("Enter", filled.segments.first().entryLocation)
        assertEquals("Station1", filled.segments.first().exitLocation)
        // (b) a layout with no conveyor element (what the scaffold path leaves) gets a routed one added.
        val added = AnimationLayout().withModelConveyorRoutes(inventory).conveyors
        assertEquals(1, added.size, "a conveyor element should be added when the layout has none")
        assertEquals(2, added.single().segments.size)
        // (c) already-authored segments are preserved.
        val authored = AnimationLayout(conveyors = listOf(ConveyorLayoutElement("Conveyor", segments = listOf(SegmentRoute("A", "B")))))
        assertEquals(1, authored.withModelConveyorRoutes(inventory).conveyors.single().segments.size, "authored segments must be kept")
    }
}
