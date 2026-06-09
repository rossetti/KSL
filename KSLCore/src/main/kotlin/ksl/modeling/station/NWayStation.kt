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
 *  Selects which of several input queues a multi-queue station serves next.
 *  Returns the index of the queue to serve, or null when all are empty.
 */
interface NWayQueueSelectionRuleIfc {
    fun selectQueue(queues: List<QueueCIfc<ModelElement.QObject>>): Int?

    /** Resets any per-replication state. Stateless rules need not override. */
    fun reset() {}
}

/** Serves the lowest-index non-empty queue first (strict priority by queue index). */
class PriorityQueueSelection : NWayQueueSelectionRuleIfc {
    override fun selectQueue(queues: List<QueueCIfc<ModelElement.QObject>>): Int? =
        queues.indexOfFirst { it.isNotEmpty }.takeIf { it >= 0 }
}

/** Cycles through the queues, serving the next non-empty one after the last served. */
class RoundRobinQueueSelection : NWayQueueSelectionRuleIfc {
    private var lastServed = -1
    override fun selectQueue(queues: List<QueueCIfc<ModelElement.QObject>>): Int? {
        if (queues.isEmpty()) return null
        for (offset in 1..queues.size) {
            val idx = (lastServed + offset) % queues.size
            if (queues[idx].isNotEmpty) {
                lastServed = idx
                return idx
            }
        }
        return null
    }

    override fun reset() {
        lastServed = -1
    }
}

/** Read-only view of an [NWayStation]. */
interface NWayStationCIfc {
    /** Time-weighted number of instances in the station (waiting plus in service). */
    val numInStation: TWResponseCIfc

    /** Time in the station per processed instance. */
    val stationTime: ResponseCIfc

    /** The number of instances processed. */
    val numProcessed: CounterCIfc

    /** The input queues (read-only). */
    val queues: List<QueueCIfc<ModelElement.QObject>>
}

/**
 *  A station with several input queues sharing one server group. Each input has
 *  its own queue (reached via [input]); when a server unit is free, a
 *  [NWayQueueSelectionRuleIfc] chooses which queue to serve next. This models
 *  multi-class or multi-stream service with an explicit cross-queue discipline.
 *
 *  @param parent the model element serving as this station's parent
 *  @param numQueues the number of input queues (>= 1)
 *  @param activityTime the service-time distribution
 *  @param capacity the number of server units (>= 1)
 *  @param selectionRule the cross-queue selection rule (default: strict priority by index)
 *  @param nextReceiver where processed instances go next
 *  @param name the name of the station
 */
class NWayStation(
    parent: ModelElement,
    numQueues: Int,
    activityTime: RVariableIfc,
    capacity: Int = 1,
    private val selectionRule: NWayQueueSelectionRuleIfc = PriorityQueueSelection(),
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : ModelElement(parent, name), RoutingOutletsIfc, NWayStationCIfc {
    init {
        require(numQueues >= 1) { "numQueues must be >= 1" }
        require(capacity >= 1) { "capacity must be >= 1" }
    }

    private var myNextReceiver: QObjectReceiverIfc = nextReceiver

    /** Sets the receiver of processed instances. */
    fun nextReceiver(receiver: QObjectReceiverIfc) {
        myNextReceiver = receiver
    }

    private val myResource = SResource(this, capacity, "${this.name}:R")
    val resource: SResourceCIfc
        get() = myResource

    private val myActivityRV = RandomVariable(this, activityTime, name = "${this.name}:ActivityRV")

    private val myQueues: List<Queue<QObject>> = (0 until numQueues).map { Queue(this, "${this.name}:Q$it") }
    override val queues: List<QueueCIfc<QObject>>
        get() = myQueues

    private inner class InputReceiver(val index: Int) : QObjectReceiverIfc {
        override fun receive(arrivingQObject: QObject) {
            enqueueTo(index, arrivingQObject)
        }
    }

    private val myInputs: List<InputReceiver> = myQueues.indices.map { InputReceiver(it) }

    /** Returns the receiver for input stream [index] (its dedicated queue). */
    fun input(index: Int): QObjectReceiverIfc = myInputs[index]

    private val myNumInStation = TWResponse(this, "${this.name}:NumInStation")
    override val numInStation: TWResponseCIfc
        get() = myNumInStation

    private val myStationTime = Response(this, "${this.name}:StationTime")
    override val stationTime: ResponseCIfc
        get() = myStationTime

    private val myNumProcessed = Counter(this, "${this.name}:NumProcessed")
    override val numProcessed: CounterCIfc
        get() = myNumProcessed

    init {
        myResource.attachUnitsAvailableListener { serveWhilePossible() }
    }

    override fun outlets(): List<QObjectReceiverIfc> =
        if (myNextReceiver === NotImplementedReceiver) emptyList() else listOf(myNextReceiver)

    override val hasOnwardRouting: Boolean
        get() = myNextReceiver !== NotImplementedReceiver

    private fun enqueueTo(index: Int, qObject: QObject) {
        qObject.stationArriveTime = time
        myNumInStation.increment()
        myQueues[index].enqueue(qObject)
        if (myResource.hasAvailableUnits) {
            serveNext()
        }
    }

    private fun anyNonEmpty(): Boolean = myQueues.any { it.isNotEmpty }

    private fun serveWhilePossible() {
        while (myResource.hasAvailableUnits && anyNonEmpty()) {
            serveNext()
        }
    }

    private fun serveNext() {
        val idx = selectionRule.selectQueue(myQueues) ?: return
        val customer = myQueues[idx].removeNext()!!
        myResource.seize()
        schedule(this::endOfProcessing, myActivityRV.value, customer)
    }

    private fun endOfProcessing(event: KSLEvent<QObject>) {
        val finished: QObject = event.message!!
        myResource.release()
        // a unit just freed; serve the next selected customer (release does not auto-notify)
        serveWhilePossible()
        myNumInStation.decrement()
        myNumProcessed.increment()
        myStationTime.value = time - finished.stationArriveTime
        myNextReceiver.receive(finished)
    }

    override fun initialize() {
        selectionRule.reset()
    }
}
