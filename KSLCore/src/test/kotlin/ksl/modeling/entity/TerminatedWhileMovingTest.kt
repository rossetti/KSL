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

package ksl.modeling.entity

import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  An entity terminated in the middle of a move leaves nothing recorded as moving: neither itself nor a movable
 *  resource it was moving, which another entity then moves as usual.
 *
 *  A plane with O at (0, 0) and P at (100, 0); speed 10, so the trip takes 10.
 */
class TerminatedWhileMovingTest {

    private class Yard(parent: ModelElement) : ProcessModel(parent, "yard") {
        val plane = Euclidean2DPlane()
        val o = plane.Point(0.0, 0.0, "O")
        val p = plane.Point(100.0, 0.0, "P")

        init {
            spatialModel = plane
        }

        val cart = MovableResourceWithQ(this, o, ConstantRV(10.0), name = "Cart")
        val log = mutableListOf<String>()
        val actors = mutableMapOf<String, Entity>()
        val setups = mutableListOf<Yard.() -> Unit>()

        /** Walks from O to P at [from]. */
        inner class Walker(val tag: String, val from: Double) : Entity(tag) {
            val w: KSLProcess = process("walk") {
                delay(from)
                currentLocation = o
                move(o, p, 10.0)
                log.add("$tag walked@$time")
            }
        }

        /** Seizes the cart at [from] and moves it to [to]. */
        inner class Driver(val tag: String, val from: Double, val to: String) : Entity(tag) {
            val d: KSLProcess = process("drive") {
                delay(from)
                val a = seize(cart)
                move(cart, if (to == "P") p else o, 10.0)
                log.add("$tag moved the cart@$time")
                release(a)
            }
        }

        fun walker(tag: String, from: Double) = Walker(tag, from).also { actors[tag] = it; activate(it.w) }
        fun driver(tag: String, from: Double, to: String) = Driver(tag, from, to).also { actors[tag] = it; activate(it.d) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            actors.clear()
            for (s in setups) s()
        }
    }

    private val moveEvents = mutableListOf<String>()

    private fun run(setup: Yard.() -> Unit): Pair<Yard, Throwable?> {
        val model = Model("Moving${System.nanoTime()}", autoCSVReports = false)
        moveEvents.clear()
        model.animationSink = object : ksl.animation.AnimationSink {
            override val isActive = true
            override fun emit(event: ksl.animation.AnimationEvent) {
                if (event is ksl.animation.AnimationEvent.SpatialElementMoveCompleted) {
                    moveEvents.add("${event.name} ended@${event.simTime} x=${event.toX}")
                }
            }
        }
        val yard = Yard(model)
        yard.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return yard to error
    }

    @Test
    @DisplayName("an entity terminated while it moves is not left moving")
    fun walkerTerminated() {
        val (yard, error) = run {
            walker("W", 0.0)
            at(4.0) {
                val w = actors.getValue("W")
                w.terminateProcess()
                log.add("W terminated@$time moving=${w.isMoving}")
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("W terminated@4.0 moving=false"), yard.log)
    }

    @Test
    @DisplayName("an entity terminated while it moves a movable resource leaves the resource free to be moved by the next")
    fun driverTerminated() {
        val (yard, error) = run {
            driver("D1", 0.0, "P")
            driver("D2", 1.0, "O")
            at(4.0) {
                actors.getValue("D1").terminateProcess()
                log.add("D1 terminated@$time cart moving=${cart.isMoving}")
            }
        }
        assertNull(error, "the run stopped: $error")
        // A move changes the cart's location when it arrives, so a move cut short leaves it where it set out, at O.
        // D2 has it at 4 and moves it to O, where it already is.
        assertEquals(listOf("D1 terminated@4.0 cart moving=false", "D2 moved the cart@4.0"), yard.log)
        // the animation is told D1's move ended at 4, where the cart stands, and then D2's move, to where it stands
        assertEquals(listOf("Cart ended@4.0 x=0.0", "Cart ended@4.0 x=0.0"), moveEvents)
    }
}
