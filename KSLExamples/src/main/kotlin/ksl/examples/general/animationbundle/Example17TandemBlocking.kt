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
import ksl.examples.book.chapter7.TandemQueueWithBlocking
import ksl.simulation.Model

/**
 * Example 17 — **blocking**, from chapter 7's two-stage tandem queue.
 *
 * A part finishing at `worker1` must take the single-space `buffer` before it can let go of `worker1`, and
 * must take `worker2` before it can let go of the buffer. So whenever the buffer is occupied and `worker2`
 * is busy, `worker1` is finished but stuck holding a part it cannot pass on — blocked rather than working.
 *
 * That distinction is the point of the model and it is invisible in a statistics report, where a blocked
 * server and a busy server both read as "not idle". It is not invisible in an animation: the buffer is a
 * single cell that is either occupied or not, and a part sitting in it while `worker2` is busy *is* the
 * blockage. Watching `worker1`'s queue grow behind a station that has already finished its work is the
 * thing the chapter is trying to describe.
 *
 * Nothing here needs instrumenting. Every element is a resource or a queue, so the trace carries it all.
 */
object Example17TandemBlocking {

    fun buildModel(): Model {
        val m = Model("TandemBlockingModel")
        TandemQueueWithBlocking(m, name = "TandemBlocking")
        m.numberOfReplications = 1
        // Arrivals average one every two units against 0.7 + 0.9 of work, so the line saturates quickly and
        // spends most of its time in the state worth watching. Long enough to see the buffer fill, empty and
        // fill again many times over; short enough to stay a few hundred kilobytes.
        m.lengthOfReplication = 200.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Tandem queue with blocking (buffer of one)"
        size(760.0, 320.0)
        clock(24.0, 34.0)

        objectClass("Customer") { color = "#1f77b4"; size = 14.0 }

        // Left to right, in the order a part travels: worker 1, the one buffer space, worker 2.
        queue("worker1:Q", 210.0, 150.0) { growthDegrees = 180.0; maxShown = 8 }
        resource("worker1", 260.0, 150.0) { size = 34.0 }
        resource("buffer", 400.0, 150.0) { size = 34.0 }
        queue("worker2:Q", 490.0, 150.0) { growthDegrees = 180.0; maxShown = 8 }
        resource("worker2", 540.0, 150.0) { size = 34.0 }

        bar("TandemBlocking:NumInSystem", 40.0, 260.0) { width = 300.0; height = 22.0; maxValue = 20.0; label = "Number in system" }
        plot("TandemBlocking:NumInSystem", 420.0, 220.0) { width = 300.0; height = 70.0; label = "Number in system over time" }
    }
}
