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
import ksl.examples.book.chapter8.TandemQueueWithConveyors
import ksl.simulation.Model

/**
 * Example 8 — conveyors. A tandem line where parts **ride a conveyor** between an entry, two work
 * stations, and an exit (segments Enter→Station1→Station2→Exit).
 *
 * The ride is what this one is for, and all of it renders:
 *
 *  - **The belt.** `ConveyorDefined` reports every anchor and the cell it sits at, so the belt is drawn as
 *    one square per cell running between the placed anchors, with travel arrows along it.
 *  - **The parts on it.** Each `ConveyorItemMoved` puts a part at its cell, so a part is watched moving
 *    along the belt rather than teleporting between stations, and the cells it occupies fill in behind it.
 *  - **Blockage.** A part that cannot get on because the entry is full draws at the entry with a red ring
 *    (`ConveyorEntryBlocked`), which is the thing an accumulating conveyor exists to demonstrate.
 *
 * Anchors are placed here as `station(...)` markers; a layout may instead supply a `ConveyorLayoutElement`
 * to route the belt through waypoints, which is what turns a loop conveyor into a loop rather than a line —
 * see the polished layout for [Example18ConveyorTestRepair].
 *
 * Conveyor parts ARE `ProcessModel.Entity`s, so they get `EntityCreated` (+type "Part") — better than
 * station QObjects.
 *
 * Note the trace size: a part moves one cell at a time, so a short run of a modest line produces tens of
 * thousands of `ConveyorItemMoved` events. This is the paradigm that writes the largest traces.
 */
object Example08ConveyorTandem {

    fun buildModel(): Model {
        val m = Model("ConveyorTandemModel")
        TandemQueueWithConveyors(m, name = "ConveyorTQ")
        m.numberOfReplications = 1
        m.lengthOfReplication = 120.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Tandem Queue with Conveyors (conveyor demo)"
        size(760.0, 360.0)
        clock(24.0, 32.0)

        objectClass("Part") { color = "#1f77b4"; size = 12.0 }

        // The belt's anchors. Placing them is all that is needed: the renderer maps a riding item's cell
        // index to a position between them and draws the belt itself. (A hand-drawn path used to be needed
        // here to stand in for a belt that did not render; it would now be a second line under the real one.)
        station("Enter", 100.0, 120.0, label = "Enter")
        station("Station1", 320.0, 120.0)
        station("Station2", 520.0, 120.0)
        station("Exit", 680.0, 120.0, label = "Exit")

        // Workers (process-view resources) with their queues. Each queue's head sits to the LEFT of its
        // server and its members grow further left, so a row reads "waiting -> head -> server", the same
        // direction as the flow along the belt above. Left at the default the members grow rightward, out
        // of the server they are waiting for.
        //
        // The gap between head and server is wider than it needs to be for clearance, and that is the only
        // lever available: an element's name label is drawn from its own position rightward, so "worker1:Q"
        // runs straight into "worker1" when the two sit close together. The layout document can hide the
        // redundant one -- a queue's name only repeats its server's, and its *count* is the informative part
        // -- but the `AnimationBuilder` DSL cannot express a label override. That is one of the reasons the
        // showcase layouts under docs/animations are documents rather than DSL.
        //
        // maxShown caps the drawn line, not the truth: worker 2's queue reaches 21 in this run, which drawn
        // in full would stretch back past worker 1. The count beside the head still reads 21.
        queue("worker1:Q", 250.0, 200.0) { growthDegrees = 180.0; spacing = 12.0; maxShown = 6 }
        resource("worker1", 320.0, 200.0) { size = 30.0 }
        queue("worker2:Q", 450.0, 200.0) { growthDegrees = 180.0; spacing = 12.0; maxShown = 6 }
        resource("worker2", 520.0, 200.0) { size = 30.0 }

        // Scaled to what the run reaches (29), not to a guess: at the old maximum of 12 the bar sat pinned
        // full for most of the animation, which reads as a broken display rather than as a busy system.
        bar("ConveyorTQ:NumInSystem", 80.0, 300.0) { width = 280.0; height = 20.0; maxValue = 30.0; label = "Number in system" }
        plot("ConveyorTQ:NumInSystem", 420.0, 260.0) { width = 280.0; height = 80.0; label = "WIP over time" }
    }

}
