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
package ksl.modeling.agent

import ksl.modeling.spatial.MovePurpose
import ksl.modeling.spatial.VehicleMovementConformance
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

/**
 *  A continuous projection keeping the movement contract — **the same eight tests, unchanged**.
 *
 *  This file is the point of the seam. Everything substrate-specific is here: a projection, a
 *  resource whose position it tracks, and a `halt()` to stop it part way. The assertions are all
 *  inherited from the suite the guide path passes, so what is being demonstrated is that the eight
 *  sentences in `VehicleMovementIfc`'s KDoc were about *movement* and not about zones.
 *
 *  **The decision points here are interpolation steps**, which the modeller chooses, where the guide
 *  path's are zone boundaries, which the network's geometry fixes. So "stop part way" means
 *  cancelling the step chain rather than closing a gate, and a redirection is observed at most
 *  `stepSize/velocity` later rather than at the next boundary. The contract afterwards is the same:
 *  the vehicle reports that it stopped, reports where, and its odometer does not go backwards.
 *
 *  The geometry is deliberately not collinear. `far` is due east and `near` is due north, so a
 *  redirection cannot be mistaken for carrying on — which is the same property the guide path's
 *  one-way loop is built for, achieved on a plane by a right angle instead.
 */
class MovableAgentMovementConformanceTest : VehicleMovementConformance() {

    private class Floor(
        parent: ModelElement,
        val haltBeforeArrival: Boolean,
        val redirect: Boolean,
        val goNowhere: Boolean,
        val trace: Trace
    ) : AgentModel(parent, "Floor") {

        val world: Context<AgentLike> = Context("world")

        val space: ContinuousProjection<AgentLike> =
            ContinuousProjection(world, 0.0..200.0, 0.0..200.0)

        val cart: MovableAgentResource = MovableAgentResource(
            this, space, initPosition = Point2D(0.0, 0.0), name = "Cart",
            velocity = 10.0, stepSize = 5.0
        )

        val start = space.spatialModel.location(0.0, 0.0, "Start")
        val near = space.spatialModel.location(0.0, 20.0, "Near")
        val far = space.spatialModel.location(100.0, 0.0, "Far")

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
                    // Turn it round while it is under way. The substrate defers the change to the
                    // next interpolation step, because the step in flight was paid for in elapsed
                    // time and the ground it covers has been covered.
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
                    // Whatever stopped it owns starting it again.
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
        val m = Model("AgentConformance")
        val floor = Floor(m, haltBeforeArrival, redirect, goNowhere, trace)
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()
        return Scenario(
            movement = floor.cart,
            start = floor.start,
            near = floor.near,
            far = floor.far
        ) to trace
    }
}
