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

package ksl.animation.replay

import ksl.animation.AnimationInventory
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
}
