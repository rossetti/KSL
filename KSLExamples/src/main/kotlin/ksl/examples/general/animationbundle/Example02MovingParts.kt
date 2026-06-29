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
import ksl.animation.LayoutShape
import ksl.animation.animation
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Example 2 — movement in isolation. A tiny, hand-authored model whose only purpose is to exercise
 * the spatial pipeline: a part is created at an entry, **walks** to station 1, is served, **walks**
 * to station 2, is served, then **walks** to the exit. The walks are real moves on a
 * [Euclidean2DPlane], so each emits a `MoveStarted` event with concrete from/to coordinates; the
 * renderer interpolates the part smoothly along the straight line between stations.
 *
 * This is the make-or-break test for the spatial half of the design: unlike the process-view
 * pharmacy (Example 1, no geometry), here entities actually traverse the layout. Between moves a
 * part holds at its station while it is in service.
 */
object Example02MovingParts {

    private const val MODEL_NAME = "MovingParts"

    /** A part walks Enter -> Station1 -> Station2 -> Exit, served at each station. */
    private class TwoStationLine(parent: ModelElement, name: String) : ProcessModel(parent, name) {
        private val plane = Euclidean2DPlane()

        // Coordinates chosen to match the authored layout below (world units).
        private val enter = plane.Point(80.0, 380.0, "Enter")
        private val s1 = plane.Point(300.0, 180.0, "Station1")
        private val s2 = plane.Point(560.0, 180.0, "Station2")
        private val exit = plane.Point(760.0, 380.0, "Exit")

        init {
            spatialModel = plane
        }

        private val worker1 = ResourceWithQ(this, "Worker1")
        private val worker2 = ResourceWithQ(this, "Worker2")
        private val st = RandomVariable(this, ExponentialRV(2.5, 2))
        private val tba = ExponentialRV(3.0, 1)
        private val wip = TWResponse(this, "$MODEL_NAME:NumInSystem")
        private val tip = Response(this, "$MODEL_NAME:TimeInSystem")

        @Suppress("unused")
        private val generator = EntityGenerator(::Part, tba, tba)

        private inner class Part : Entity() {
            @Suppress("unused")
            val line: KSLProcess = process(isDefaultProcess = true) {
                wip.increment()
                val arrived = time
                currentLocation = enter
                moveTo(s1, velocity = 30.0)
                val a1 = seize(worker1)
                delay(st)
                release(a1)
                moveTo(s2, velocity = 30.0)
                val a2 = seize(worker2)
                delay(st)
                release(a2)
                moveTo(exit, velocity = 30.0)
                tip.value = time - arrived
                wip.decrement()
            }
        }
    }

    fun buildModel(): Model {
        val m = Model("${MODEL_NAME}Model")
        TwoStationLine(m, MODEL_NAME)
        m.numberOfReplications = 1
        m.lengthOfReplication = 80.0
        return m
    }

    /**
     * Layout: a continuous-space backdrop, the two stations as labeled markers, the walking path
     * connecting Enter -> S1 -> S2 -> Exit, the two worker resources (colored by state) with their
     * queues, and a live WIP bar. Parts (the moving dots) are drawn from their interpolated
     * positions; station/queue/resource positions match the model's Euclidean points.
     */
    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Two-Station Line (movement demo)"
        size(840.0, 460.0)
        clock(24.0, 32.0)

        objectClass("Part") { color = "#1f77b4"; shape = LayoutShape.CIRCLE; size = 14.0 }

        continuousSpace("floor", xMin = 0.0, xMax = 840.0, yMin = 0.0, yMax = 460.0)

        // The walking path (decoration); parts move along straight lines between these points.
        path("route", 80.0 to 380.0, 300.0 to 180.0, 560.0 to 180.0, 760.0 to 380.0)

        station("Enter", 80.0, 380.0, label = "Enter")
        station("ExitPt", 760.0, 380.0, label = "Exit")

        // Workers sit just to the side of their station points; queues stack downward from them.
        resource("Worker1", 300.0, 180.0) { size = 30.0 }
        queue("Worker1:Q", 300.0, 230.0)
        resource("Worker2", 560.0, 180.0) { size = 30.0 }
        queue("Worker2:Q", 560.0, 230.0)

        bar("$MODEL_NAME:NumInSystem", 80.0, 60.0) {
            width = 260.0; height = 20.0; maxValue = 20.0; label = "Number in system"
        }
    }

}
