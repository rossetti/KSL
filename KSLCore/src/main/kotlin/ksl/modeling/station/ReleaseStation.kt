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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.simulation.ModelElement

/** Read-only view of a [ReleaseStation]. */
interface ReleaseStationCIfc {
    /** The number of times this station released the resource. */
    val numReleased: CounterCIfc
}

/**
 *  An atomic release: when an entity arrives, releases this entity's oldest
 *  outstanding allocation on [resource] (FIFO) and forwards. If the entity
 *  holds no allocation on the resource, the model is in error and the build
 *  fails loudly — releases must be paired with seizes.
 *
 *  Construct via [StationNetwork.releaseStation] or the builder DSL.
 */
class ReleaseStation internal constructor(
    private val network: StationNetwork,
    private val resource: SResource,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(network, name), QObjectReceiverIfc, RoutingOutletsIfc, ReleaseStationCIfc {

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of entities that have just released the resource. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private val myNumReleased: Counter = Counter(this, "${this.name}:NumReleased")
    override val numReleased: CounterCIfc
        get() = myNumReleased

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    override fun receive(arrivingQObject: QObject) {
        val alloc = network.takeAllocation(arrivingQObject, resource)
            ?: error(
                "ReleaseStation '${this.name}': entity ${arrivingQObject.name} (id=${arrivingQObject.id}) " +
                    "has no allocation on resource '${resource.name}' to release. Did you seize it first?"
            )
        resource.release(alloc.amount)
        myNumReleased.increment()
        // honor the QObject's sender if present (e.g., a Route), otherwise next
        val attachedSender = arrivingQObject.sender
        if (attachedSender != null) attachedSender.send(arrivingQObject) else myNextReceiver.receive(arrivingQObject)
    }
}
