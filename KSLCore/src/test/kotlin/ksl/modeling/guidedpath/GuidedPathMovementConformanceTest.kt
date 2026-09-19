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
package ksl.modeling.guidedpath

import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.spatial.VehicleMovementConformance
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  The guide path keeping the movement contract.
 *
 *  Everything substrate-specific is here: a network, a transporter, and a movement gate to stop it
 *  part way. The assertions are all inherited, so a second substrate joins by writing this much and
 *  nothing else — which is the property the seam exists for, and the reason this file is short.
 *
 *  The guide path's decision points are **zone boundaries**, so "stop part way" means a gate that
 *  refuses at the first boundary. A continuous projection would cancel a travel at an interpolation
 *  step instead, and a free-path move would interrupt its delay. The contract afterwards is the
 *  same: the vehicle reports that it stopped, and reports where.
 */
class GuidedPathMovementConformanceTest : VehicleMovementConformance() {

    private class Aisle(
        parent: ModelElement,
        val haltBeforeArrival: Boolean,
        val redirect: Boolean,
        val goNowhere: Boolean,
        val trace: Trace
    ) : ProcessModel(parent, "Aisle") {

        // A one-way loop. "Far" is deliberately not reached by way of "Near": a vehicle sent to D
        // passes B, so a redirect to B would otherwise be indistinguishable from carrying on.
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 20.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 20.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 20.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 20.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val space = GuidedPathTransportSystem(this, network, name = "Space")

        val cart = GuidedTransporter(space, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")

        private var gateClosed = haltBeforeArrival

        init {
            if (haltBeforeArrival) {
                cart.attachMovementGate { _, _ -> !gateClosed }
            }
        }

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                val target = if (goNowhere) network.requireLocation("A") else network.requireLocation("D")
                val q = cart.beginTravelTo(target, MovePurpose.SERVICE, this@Driver)
                if (q == null) {
                    trace.startedAlreadyThere = true
                    trace.finalOdometer = cart.distanceTravelled
                    return@process
                }
                if (redirect) {
                    // Turn it round while it is under way. The space layer defers the change to the
                    // next boundary, because something between two places cannot stop and turn.
                    schedule({ _: KSLEvent<Nothing> ->
                        cart.beginTravelTo(network.requireLocation("B"), MovePurpose.SERVICE, this@Driver)
                    }, 3.0)
                }
                hold(q, suspensionName = "travelling")
                if (cart.isHalted) {
                    trace.haltedAt = cart.positionNow
                    trace.distanceAtHalt = cart.distanceTravelled
                    // Whatever stopped it owns starting it again. Open the gate and release it.
                    gateClosed = false
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
                trace.remainingToTarget.add(cart.pathDistanceTo(network.requireLocation("D")))
            }
        }

        override fun initialize() {
            activate(Driver().p)
            var t = 0.5
            while (t < 45.0) {
                schedule(::sample, t)
                t += 0.5
            }
        }
    }

    override fun scenario(
        haltBeforeArrival: Boolean,
        redirect: Boolean,
        goNowhere: Boolean
    ): Pair<Scenario, Trace> {
        val trace = Trace()
        val m = Model("Conformance")
        val aisle = Aisle(m, haltBeforeArrival, redirect, goNowhere, trace)
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        m.simulate()
        return Scenario(
            movement = aisle.cart,
            start = aisle.network.requireLocation("A"),
            near = aisle.network.requireLocation("B"),
            far = aisle.network.requireLocation("D")
        ) to trace
    }
}
