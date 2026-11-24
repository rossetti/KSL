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
package ksl.modeling.spatial

import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A movable resource starts each replication as it starts the first: not returning home, not moving empty,
 *  not transporting, whatever it was doing when the previous replication ended.
 */
class MovableResourceReplicationResetTest {

    /**
     *  The cart starts at Far; its home base is Start, 100 away at speed 1, so a trip home outlasts the
     *  replication (length 10). In replication 1 a user seizes the cart at 0, releases it at 1 and sends it
     *  home; in replication 2 it does the same. What the cart reports is recorded at 0.5 and at 2 in each.
     *  With [movesEmptyInFirst], replication 1 instead starts a journey home through the movement seam, which
     *  the replication's end cuts short; at 2 the journey is flagged as carrying a load.
     */
    private class Yard(parent: ModelElement, val movesEmptyInFirst: Boolean) : ProcessModel(parent, "Yard") {
        val plane = Euclidean2DPlane()

        init {
            spatialModel = plane
        }

        val start = plane.Point(0.0, 0.0, "Start")
        val far = plane.Point(100.0, 0.0, "Far")
        val cart = MovableResource(this, far, ConstantRV(1.0), name = "Cart").also { it.initialHomeBase = start }
        val cartQ = RequestQ(this, "CartQ")

        /** For each replication, what the cart reported at 0.5 and at 2. */
        val seen = mutableMapOf<Int, List<Any>>()

        private inner class User : Entity("User") {
            val p = process(isDefaultProcess = true) {
                if (movesEmptyInFirst) {
                    if (model.currentReplicationNumber == 1) {
                        // a journey through the movement seam left unfinished by the replication's end: empty, and
                        // then one flagged as carrying a load
                        val q = cart.beginTravelTo(start, MovePurpose.SERVICE, this@User)!!
                        hold(q, suspensionName = "travelling")
                    }
                    return@process
                }
                val a = seize(cart, queue = cartQ)
                delay(1.0)
                release(a)
                cart.sendToHomeBase()
            }
        }

        override fun initialize() {
            activate(User().p)
            schedule(::look, 0.5, message = "early")
            schedule(::look, 2.0, message = "later")
        }

        private fun look(event: KSLEvent<String>) {
            // after the first look of replication 1, the journey is flagged as carrying a load
            if (movesEmptyInFirst && model.currentReplicationNumber == 1 && event.message == "later") cart.isTransporting = true
            val r = model.currentReplicationNumber
            val now = listOf(cart.isReturningHome, cart.isMoving, cart.isMovingEmpty, cart.isTransporting)
            seen[r] = (seen[r] ?: emptyList()) + listOf(now)
        }
    }

    private fun run(movesEmptyInFirst: Boolean): Yard {
        val model = Model("MovableResourceReset$movesEmptyInFirst", autoCSVReports = false)
        val yard = Yard(model, movesEmptyInFirst)
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        model.simulate()
        return yard
    }

    @Test
    @DisplayName("a resource whose trip home outlasts a replication is not returning home at the next, and is sent home there")
    fun returningHomeIsResetEachReplication() {
        val yard = run(movesEmptyInFirst = false)
        // replication 2: at 0.5 it is not returning home and not moving; at 2 it was sent home and is moving
        assertEquals(listOf(false, false), (yard.seen[2]!![0] as List<*>).take(2))
        assertEquals(listOf(true, true), (yard.seen[2]!![1] as List<*>).take(2))
    }

    @Test
    @DisplayName("an empty move or a transport left unfinished at a replication's end is not reported at the next")
    fun movingFlagsAreResetEachReplication() {
        val yard = run(movesEmptyInFirst = true)
        // replication 1, at 0.5: moving empty
        assertEquals(listOf(true, false), (yard.seen[1]!![0] as List<*>).drop(2))
        // replication 2, at 0.5: neither
        assertEquals(listOf(false, false), (yard.seen[2]!![0] as List<*>).drop(2))
    }
}
