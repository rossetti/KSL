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

package ksl.modeling.station

/**
 *  A first-class, named, ordered visitation sequence through a set of receivers
 *  (a "process plan"). A route is a reusable template: each QObject that follows
 *  the route gets its own cursor via [newSender], so many instances can traverse
 *  the same route concurrently without interfering.
 *
 *  A route is followed by attaching its sender to the QObject (the standard
 *  per-instance routing idiom): `qObject.sender(route.newSender())`. As each
 *  station completes the instance, the attached sender advances it to the next
 *  step; the final step is typically a sink. This generalizes [ReceiverSequence]
 *  with a named, inspectable list of steps.
 *
 *  Register a route with its network ([StationNetwork.registerRoute]) when its
 *  steps are stations that are otherwise unwired (driven only by the route), so
 *  the network's validation treats them as routed rather than dangling. The DSL
 *  `route(...)` helper and class routes register automatically.
 *
 *  @param routeName a name for the route, useful for reporting and multi-class models
 *  @param steps the ordered receivers to visit, which must be non-empty
 */
class Route(
    val routeName: String,
    steps: List<QObjectReceiverIfc>
) {
    init {
        require(routeName.isNotBlank()) { "A route name must not be blank." }
        require(steps.isNotEmpty()) { "A route must have at least one step." }
    }

    private val mySteps: List<QObjectReceiverIfc> = steps.toList()

    /** The ordered receivers visited by this route. */
    val steps: List<QObjectReceiverIfc>
        get() = mySteps

    /** The number of steps in the route. */
    val size: Int
        get() = mySteps.size

    /**
     *  Creates a fresh sender that advances a single QObject through this route,
     *  beginning at [fromStep]. Attach the result to the instance via
     *  `qObject.sender(route.newSender())`. The default begins at the first step.
     */
    fun newSender(fromStep: Int = 0): QObjectSenderIfc {
        require(fromStep in 0..mySteps.size) { "fromStep $fromStep is out of range 0..${mySteps.size}." }
        return ReceiverSequence(mySteps.listIterator(fromStep))
    }

    /**
     *  Returns a receiver that starts an arriving QObject on this route: it
     *  attaches a fresh per-instance sender positioned at the second step and
     *  forwards the instance to the first step. Each station then advances the
     *  instance to the next step via the attached sender. Wire a source or an
     *  upstream node to this receiver (for example,
     *  `source.firstReceiver(route.entryReceiver())`).
     */
    fun entryReceiver(): QObjectReceiverIfc = QObjectReceiverIfc { qObject ->
        qObject.sender(newSender(fromStep = 1))
        mySteps[0].receive(qObject)
    }
}
