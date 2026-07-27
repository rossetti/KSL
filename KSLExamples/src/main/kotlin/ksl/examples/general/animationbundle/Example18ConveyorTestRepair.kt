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

package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.animation
import ksl.examples.book.chapter8.TestAndRepairShopWithConveyor
import ksl.simulation.Model

/**
 * Example 18 — the test-and-repair shop again, with the parts riding a **loop conveyor**.
 *
 * This is the same job shop as [Example13MovableResources] with its transport replaced: instead of workers
 * fetching parts, an accumulating loop belt runs Diagnostics → Test 1 → Test 2 → Repair → Test 3 and back
 * to Diagnostics, and a part rides it to whichever station its test plan calls for next. Seeing the two
 * side by side is the point — the same shop, two transport policies, two very different pictures.
 *
 * What a loop conveyor makes visible that a report does not:
 *
 *  - **A part can ride past the station it wants.** The belt only goes one way, so a plan that sends a part
 *    from Test 3 back to Test 1 means most of a lap. The layout below draws the loop as a loop for exactly
 *    that reason: on a straight line the return leg is a lie.
 *  - **Accumulation.** The belt is accumulating, so a part blocked at a full entry stops while the belt
 *    keeps running beneath it, and parts bunch up behind it.
 *  - **Cells are a real resource.** A part occupies whole cells and a bigger test plan needs more of them,
 *    so the belt itself can be the constraint rather than the stations.
 *
 * Nothing here needs instrumenting: the conveyor reports its own definition and every ride.
 */
object Example18ConveyorTestRepair {

    fun buildModel(): Model {
        val m = Model("ConveyorTestRepairModel")
        TestAndRepairShopWithConveyor(m, name = "ConveyorTestRepair")
        m.numberOfReplications = 1
        // Matches Example13MovableResources, so the two transport policies can be watched over the same
        // stretch of shop time and compared.
        m.lengthOfReplication = 480.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Test & repair shop — parts on a loop conveyor"
        size(900.0, 620.0)
        clock(28.0, 38.0)

        objectClass("Part") { color = "#1f77b4"; size = 13.0 }

        // The conveyor's five anchors, placed around a loop in the order the belt visits them. The belt
        // ITSELF is not declared here: the DSL has no way to author a conveyor route, so the renderer falls
        // back to straight anchor-to-anchor interpolation and the return leg cuts diagonally across the shop.
        // Drawing it as a real loop needs waypoints, which only a `.lay.json` can carry — see the polished
        // layout in docs/animations/layouts. That gap is one of the reasons the showcase layouts are
        // documents rather than DSL.
        location("Diagnostics", 150.0, 180.0)
        location("Test1", 430.0, 180.0)
        location("Test2", 710.0, 180.0)
        location("Repair", 710.0, 430.0)
        location("Test3", 300.0, 430.0)

        resource("Diagnostics", 150.0, 120.0) { size = 30.0 }
        queue("Diagnostics:Q", 100.0, 120.0) { growthDegrees = 180.0; maxShown = 6 }
        resource("Test1", 430.0, 120.0) { size = 30.0 }
        queue("Test1:Q", 395.0, 120.0) { growthDegrees = 180.0; maxShown = 6 }
        resource("Test2", 710.0, 120.0) { size = 30.0 }
        queue("Test2:Q", 675.0, 120.0) { growthDegrees = 180.0; maxShown = 6 }
        resource("Repair", 710.0, 500.0) { size = 30.0 }
        queue("Repair:Q", 630.0, 500.0) { growthDegrees = 180.0; maxShown = 6 }
        resource("Test3", 300.0, 500.0) { size = 30.0 }
        queue("Test3:Q", 265.0, 500.0) { growthDegrees = 180.0; maxShown = 6 }

        bar("ConveyorTestRepair:NumInSystem", 40.0, 570.0) { width = 300.0; height = 22.0; maxValue = 30.0; label = "Number in system" }
    }
}
