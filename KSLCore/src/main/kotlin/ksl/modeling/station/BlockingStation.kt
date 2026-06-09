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
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  A receiver with a finite capacity that can report whether it can accept an
 *  instance and notify upstream stations when space frees. This enables
 *  manufacturing (block-after-service) blocking between stations.
 */
interface BlockableReceiverIfc : QObjectReceiverIfc {
    /** True if the receiver currently has room for another instance. */
    fun canReceive(): Boolean

    /** Registers a listener invoked when a slot frees (so a blocked upstream can push). */
    fun attachSpaceAvailableListener(listener: () -> Unit)
}

/** Read-only view of a [BlockingStation]. */
interface BlockingStationCIfc {
    /** Time-weighted number of instances in the station (waiting, in service, or blocked). */
    val numInStation: TWResponseCIfc

    /** Time-weighted 0/1 indicator that the server is blocked (finished but cannot push downstream). */
    val blockedProportion: TWResponseCIfc

    /** Time in the station per processed instance (includes any blocking delay). */
    val stationTime: ResponseCIfc

    /** The number of instances processed (pushed downstream). */
    val numProcessed: CounterCIfc

    /** The waiting queue (read-only). */
    val waitingQ: QueueCIfc<ModelElement.QObject>
}

/**
 *  A single-server station with a finite buffer that uses block-after-service
 *  semantics: when the server completes an instance but the downstream receiver
 *  cannot accept it, the server is blocked — it holds the finished instance and
 *  does not start the next one until the downstream frees a slot. A chain of
 *  blocking stations models a production line with limited buffers and
 *  back-pressure.
 *
 *  The finite buffer ([bufferCapacity], the maximum number waiting + in service +
 *  blocked) is enforced via [canReceive] for blocking upstreams; a non-blocking
 *  upstream (such as a source) is expected not to exceed it.
 *
 *  @param parent the model element serving as this station's parent
 *  @param bufferCapacity the maximum number of instances the station may hold (>= 1)
 *  @param activityTime the service-time distribution
 *  @param nextReceiver where processed instances are pushed
 *  @param name the name of the station
 */
class BlockingStation(
    parent: ModelElement,
    val bufferCapacity: Int,
    activityTime: RVariableIfc,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(parent, name), BlockableReceiverIfc, RoutingOutletsIfc, BlockingStationCIfc {
    init {
        require(bufferCapacity >= 1) { "bufferCapacity must be >= 1" }
    }

    private val myResource = SResource(this, 1, "${this.name}:R")
    private val myActivityRV = RandomVariable(this, activityTime, name = "${this.name}:ActivityRV")
    private val myWaitingQ: Queue<QObject> = Queue(this, "${this.name}:Q")
    override val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    private var myNextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    private var myBlockedItem: QObject? = null

    private val mySpaceListeners = mutableListOf<() -> Unit>()

    private val myNumInStation = TWResponse(this, "${this.name}:NumInStation")
    override val numInStation: TWResponseCIfc
        get() = myNumInStation

    private val myBlockedState = TWResponse(this, "${this.name}:BlockedState")
    override val blockedProportion: TWResponseCIfc
        get() = myBlockedState

    private val myStationTime = Response(this, "${this.name}:StationTime")
    override val stationTime: ResponseCIfc
        get() = myStationTime

    private val myNumProcessed = Counter(this, "${this.name}:NumProcessed")
    override val numProcessed: CounterCIfc
        get() = myNumProcessed

    init {
        nextReceiver(nextReceiver)
    }

    /** Sets the downstream receiver, registering for its space-available events if blockable. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
        if (receiver is BlockableReceiverIfc) {
            receiver.attachSpaceAvailableListener { onDownstreamSpace() }
        }
    }

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    override fun canReceive(): Boolean = myNumInStation.value.toInt() < bufferCapacity

    override fun attachSpaceAvailableListener(listener: () -> Unit) {
        mySpaceListeners.add(listener)
    }

    private fun notifySpaceAvailable() {
        for (listener in mySpaceListeners.toList()) {
            listener()
        }
    }

    override fun receive(arrivingQObject: QObject) {
        arrivingQObject.stationArriveTime = time
        myNumInStation.increment()
        myWaitingQ.enqueue(arrivingQObject)
        if (myResource.hasAvailableUnits && myBlockedItem == null) {
            serveNext()
        }
    }

    private fun serveNext() {
        val customer = myWaitingQ.removeNext()!!
        myResource.seize()
        schedule(this::endOfProcessing, myActivityRV.value, customer)
    }

    private fun endOfProcessing(event: KSLEvent<QObject>) {
        val finished: QObject = event.message!!
        if (canPush()) {
            pushOut(finished)
        } else {
            // block-after-service: hold the finished instance, keep the server busy
            myBlockedItem = finished
            myBlockedState.value = 1.0
        }
    }

    private fun canPush(): Boolean {
        val r = myNextReceiver
        return if (r is BlockableReceiverIfc) r.canReceive() else true
    }

    private fun pushOut(finished: QObject) {
        myResource.release()
        myNumInStation.decrement()
        myNumProcessed.increment()
        myStationTime.value = time - finished.stationArriveTime
        myBlockedState.value = 0.0
        myNextReceiver.receive(finished)
        notifySpaceAvailable() // a slot freed; let blocked upstreams push
        if (myResource.hasAvailableUnits && myBlockedItem == null && myWaitingQ.isNotEmpty) {
            serveNext()
        }
    }

    private fun onDownstreamSpace() {
        val held = myBlockedItem
        if (held != null && canPush()) {
            myBlockedItem = null
            pushOut(held)
        }
    }

    override fun initialize() {
        myBlockedItem = null
    }
}
