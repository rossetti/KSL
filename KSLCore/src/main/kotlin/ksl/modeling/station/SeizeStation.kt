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

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.simulation.ModelElement

/** Read-only view of a [SeizeStation]. */
interface SeizeStationCIfc {
    /** The waiting queue of entities blocked at this seize station. */
    val waitingQ: QueueCIfc<ModelElement.QObject>

    /** The number of times this station successfully seized the resource. */
    val numSeized: CounterCIfc
}

/**
 *  An atomic seize: when an entity arrives, the station either acquires
 *  [amount] units of [resource] (recording the [Allocation] with the network
 *  so [ReleaseStation] can release it later) and forwards the entity, or
 *  blocks the entity in its own queue. When the resource gains units, the
 *  station serves as many waiting entities as it can.
 *
 *  Between this station and a paired [ReleaseStation], the entity continues
 *  to hold the allocation across any number of intermediate stations. This is
 *  the Arena-style separation of seize / delay / release that lets a single
 *  entity hold multiple resources simultaneously (e.g., tester + machine).
 *
 *  Several seize stations may share the same resource; each has its own queue
 *  and reacts to the resource's availability event in registration order
 *  (first-come tie-break).
 *
 *  Construct via [StationNetwork.seizeStation] or the builder DSL.
 */
class SeizeStation internal constructor(
    private val network: StationNetwork,
    private val resource: SResource,
    private val amount: Int = 1,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(network, name), QObjectReceiverIfc, RoutingOutletsIfc, SeizeStationCIfc {
    init {
        require(amount >= 1) { "SeizeStation '${this.name}': amount must be >= 1 (got $amount)" }
        // When the resource frees units (release or capacity increase), try to serve
        // this station's queue. Multiple seize stations sharing a resource will each
        // be notified; first-come tie-break by listener registration order.
        resource.attachUnitsAvailableListener { serveWaiting() }
    }

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of entities that have just seized the resource. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private val myWaitingQ: Queue<QObject> = Queue(this, "${this.name}:Q")
    override val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private val myNumSeized: Counter = Counter(this, "${this.name}:NumSeized")
    override val numSeized: CounterCIfc
        get() = myNumSeized

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    override fun receive(arrivingQObject: QObject) {
        if (resource.numAvailableUnits >= amount) {
            seizeAndForward(arrivingQObject)
        } else {
            myWaitingQ.enqueue(arrivingQObject)
        }
    }

    private fun serveWaiting() {
        while (myWaitingQ.isNotEmpty && resource.numAvailableUnits >= amount) {
            val next = myWaitingQ.removeNext()!!
            seizeAndForward(next)
        }
    }

    private fun seizeAndForward(qObject: QObject) {
        resource.seize(amount)
        val alloc = Allocation(resource, amount, time, this.name)
        network.recordAllocation(qObject, alloc)
        myNumSeized.increment()
        // honor the QObject's sender if present (e.g., a Route), otherwise next
        val attachedSender = qObject.sender
        if (attachedSender != null) attachedSender.send(qObject) else myNextReceiver.receive(qObject)
    }
}
