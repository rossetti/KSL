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
import ksl.examples.book.chapter6.DriveThroughPharmacy
import ksl.simulation.Model

/**
 * Example 1 — the simplest case: a single-server queue (the Ch6 drive-through pharmacy), animated
 * with the **process view**. There is no spatial movement in this model, so the animation shows the
 * three things the process view captures: the **queue** of waiting customers (one dot per waiting
 * entity), the **resource** (the pharmacist, colored by busy/idle state), and **statistics** (a live
 * WIP bar and a WIP time-series plot). This is the right starting point for understanding what a
 * zero-geometry model looks like in the viewer.
 *
 * Shape of every animation example:
 *   1. build the KSL [Model] (here, reuse [DriveThroughPharmacy] from KSLExamples) — see [buildModel],
 *   2. author an [AnimationLayout] with the `model.animation { … }` DSL — placing each element by
 *      the **same name the model uses** (resource "Pharmacists", its queue "Pharmacists:Q", the WIP
 *      response "DriveThrough:NumInSystem") — see [buildLayout].
 *
 * The animation app drives the rest: it runs the model with the trace attachment to capture a
 * replication, then replays the trace against a layout the user authors and saves.
 */
object Example01DriveThroughPharmacy {

    private const val MODEL_NAME = "DriveThrough"

    /** Builds the model configured for a single, watchable replication (no warm-up, short run). */
    fun buildModel(): Model {
        // The Model and the ProcessModel must have distinct names; response names use the
        // ProcessModel's name ($MODEL_NAME), so only that one feeds the layout keys.
        val m = Model("${MODEL_NAME}PharmacyModel")
        // Two pharmacists, so the resource animates as 2 units (N-of-C busy/idle, 8A.3).
        DriveThroughPharmacy(m, numPharmacists = 2, name = MODEL_NAME)
        m.numberOfReplications = 1
        m.lengthOfReplication = 240.0 // minutes; ~240 customers at the default arrival rate
        return m
    }

    /**
     * Authors the layout. Element names must match the model: the [DriveThroughPharmacy] resource is
     * "Pharmacists" (so its `ResourceWithQ` queue is "Pharmacists:Q"), and the WIP `TWResponse` is
     * "$MODEL_NAME:NumInSystem". The DSL only describes *where/how* to draw — the emitters that fill
     * the trace are wired automatically by the attachment.
     */
    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Drive-Through Pharmacy (Ch6 Example 1)"
        size(720.0, 420.0)

        text("Drive-Through Pharmacy", 220.0, 40.0)
        clock(24.0, 32.0)

        objectClass("Customer") { color = "#1f77b4"; size = 14.0 }

        // Cars queue vertically, then reach the pharmacist window to the right.
        // Note: ResourceWithQ names its queue "<resourceName>:Q" and element names are NOT
        // parent-prefixed, so the resource is "Pharmacists" and its queue is "Pharmacists:Q".
        line(360.0 to 150.0, 470.0 to 150.0, color = "#999999")
        queue("Pharmacists:Q", 360.0, 150.0) {
            growthDegrees = 90.0 // grows downward
            spacing = 24.0
        }
        // The canvas auto-labels resources/queues with their trace names, so no extra text here.
        resource("Pharmacists", 500.0, 150.0) { size = 36.0 }

        // Live statistics bound to the model's responses (by name).
        bar("$MODEL_NAME:NumInSystem", 80.0, 320.0) {
            width = 280.0; height = 22.0; maxValue = 12.0; label = "Number in system"
        }
        plot("$MODEL_NAME:NumInSystem", 420.0, 280.0) {
            width = 260.0; height = 110.0; label = "WIP over time"
        }
        // A plain labeled readout (the value() primitive that bar composes, 8A.5).
        value("$MODEL_NAME:NumServed", 80.0, 290.0, label = "Customers served", decimals = 0)
        // Live within-replication statistics for time-in-system, emitted by the engine (D11 / 8A.4).
        summary("$MODEL_NAME:TimeInSystem", 420.0, 410.0, label = "Time in system")
        // A live histogram of time-in-system, binned in the viewer from the raw values (D12 / 8D.1).
        histogram("$MODEL_NAME:TimeInSystem", 60.0, 60.0, bins = 12) { width = 220.0; height = 90.0; label = "Time-in-system histogram" }
    }

}
