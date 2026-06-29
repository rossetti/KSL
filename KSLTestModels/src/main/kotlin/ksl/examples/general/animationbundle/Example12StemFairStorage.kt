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
import ksl.animation.StorageStyle
import ksl.animation.animation
import ksl.examples.book.chapter7.StemFairMixerEnhanced
import ksl.simulation.Model

/**
 * Example 12 — **storages for bare `delay()`s** (8K.4). The STEM-fair mixer is almost entirely bare
 * delays (students walk, get name tags, converse), so today most of the model renders as *nothing* —
 * only the two recruiter resources have geometry. Storages fix that:
 *
 *  - `storage("Student", …)` binds to the **entity type** (the automatic default for *unnamed* delays),
 *    so every student currently mid-delay — walking or at name tags — shows up as an ambient crowd
 *    with **no model changes at all**.
 *  - `storage("ConversationArea", …)` binds to a delay the model **named** (`delay(t, suspensionName =
 *    "ConversationArea")`) — a progress belt where each student drifts from entry to exit as their
 *    conversation elapses.
 *
 * The recruiter visits are `use(...)` (seize+delay), so those students draw inside the resources
 * (in-service), not in a storage — storages catch exactly the otherwise-invisible delays.
 */
object Example12StemFairStorage {

    fun buildModel(): Model {
        val m = Model("StemFairStorageModel")
        StemFairMixerEnhanced(m, "Mixer")
        m.numberOfReplications = 1
        m.lengthOfReplication = 360.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "STEM Fair Mixer — storages for bare delays (8K.4)"
        size(900.0, 520.0)
        clock(24.0, 32.0)

        objectClass("Student") { color = "#1f77b4"; size = 12.0 }

        // Static venue labels.
        text("Entrance / walkways", 40.0, 80.0)
        text("Conversation area", 330.0, 170.0)
        text("Recruiting", 700.0, 80.0)

        // The ambient crowd: every student currently in an UNNAMED delay (walking, name tags), keyed by
        // the entity type "Student". No model changes needed — this is the automatic default key.
        storage("Student", 40.0, 100.0) {
            style = StorageStyle.PACKED_REGION; width = 240.0; height = 180.0; spacing = 18.0; label = "In transit"
        }

        // The conversation area: a NAMED delay ("ConversationArea") shown as a progress belt — students
        // drift left-to-right as their conversation elapses.
        storage("ConversationArea", 330.0, 220.0) {
            style = StorageStyle.PROGRESS_BELT; width = 300.0; spacing = 16.0; label = "Conversing"
        }

        // The two recruiter stations (process-view resources) with their waiting lines.
        resource("JHBuntR", 760.0, 140.0) { size = 30.0 }
        queue("JHBuntR:Q", 700.0, 140.0) { growthDegrees = 180.0 }
        resource("MalWartR", 760.0, 260.0) { size = 30.0 }
        queue("MalWartR:Q", 700.0, 260.0) { growthDegrees = 180.0 }

        // Live counts.
        bar("NumInSystem", 40.0, 440.0) { width = 300.0; height = 20.0; maxValue = 60.0; label = "Number in system" }
        bar("NumInConversationArea", 40.0, 470.0) { width = 300.0; height = 20.0; maxValue = 40.0; label = "In conversation" }
    }

}
