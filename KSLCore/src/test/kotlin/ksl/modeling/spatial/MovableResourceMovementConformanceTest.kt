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
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **The free path keeping the movement contract — the same eight tests, unchanged.**
 *
 *  The third substrate, and the released one. A free-path move has always been a single delay with
 *  the resource at the place it set out from until the instant it arrived; through the seam it is
 *  stepped, so a dispatcher asking which vehicle is nearest gets an answer about now rather than
 *  about wherever each vehicle last stopped.
 *
 *  **What is not touched.** `move`, `moveWith` and `transportWith` still make one delay and one
 *  `currentLocation` update. So does a seam journey — the stepping writes to a live position and
 *  `currentLocation` is set once, when the journey ends — so nothing observing a spatial element
 *  can tell the two apart.
 *
 *  The geometry is a plane, which can say where between two places is. `TheGeometryDecidesTest`
 *  below covers the other case: a spatial model that cannot, where the contract still holds and one
 *  of its promises is honestly unmet.
 */
class MovableResourceMovementConformanceTest : VehicleMovementConformance() {

    private class Yard(
        parent: ModelElement,
        val haltBeforeArrival: Boolean,
        val redirect: Boolean,
        val goNowhere: Boolean,
        val trace: Trace
    ) : ProcessModel(parent, "Yard") {

        val plane = Euclidean2DPlane()

        init {
            spatialModel = plane
        }

        // Not collinear: `far` is due east and `near` due north, so a redirection cannot be
        // mistaken for carrying on.
        val start = plane.Point(0.0, 0.0, "Start")
        val near = plane.Point(0.0, 20.0, "Near")
        val far = plane.Point(100.0, 0.0, "Far")

        val cart = MovableResource(this, start, ConstantRV(10.0), name = "Cart", stepSize = 5.0)

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                val target = if (goNowhere) start else far
                val q = cart.beginTravelTo(target, MovePurpose.SERVICE, this@Driver)
                if (q == null) {
                    trace.startedAlreadyThere = true
                    trace.finalOdometer = cart.distanceTravelled
                    return@process
                }
                if (redirect) {
                    schedule({ _: KSLEvent<Nothing> ->
                        cart.beginTravelTo(near, MovePurpose.SERVICE, this@Driver)
                    }, 3.0)
                }
                if (haltBeforeArrival) {
                    schedule({ _: KSLEvent<Nothing> -> cart.halt() }, 3.0)
                }
                hold(q, suspensionName = "travelling")
                if (cart.isHalted) {
                    trace.haltedAt = cart.positionNow
                    trace.distanceAtHalt = cart.distanceTravelled
                    cart.resumeHalted()
                    val again = cart.beginTravelTo(target, MovePurpose.SERVICE, this@Driver)
                    if (again != null) hold(again, suspensionName = "resuming")
                }
                trace.arrivedAt = cart.positionNow
                trace.finalOdometer = cart.distanceTravelled
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            trace.positions.add(cart.positionNow)
            trace.odometer.add(cart.distanceTravelled)
            if (!redirect && !goNowhere && !haltBeforeArrival) {
                trace.remainingToTarget.add(cart.pathDistanceTo(far))
            }
        }

        override fun initialize() {
            activate(Driver().p)
            var t = 0.25
            while (t < 25.0) {
                schedule(::sample, t)
                t += 0.25
            }
        }
    }

    override fun scenario(
        haltBeforeArrival: Boolean,
        redirect: Boolean,
        goNowhere: Boolean
    ): Pair<Scenario, Trace> {
        val trace = Trace()
        val m = Model("FreePathConformance")
        val yard = Yard(m, haltBeforeArrival, redirect, goNowhere, trace)
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()
        return Scenario(
            movement = yard.cart,
            start = yard.start,
            near = yard.near,
            far = yard.far
        ) to trace
    }
}
