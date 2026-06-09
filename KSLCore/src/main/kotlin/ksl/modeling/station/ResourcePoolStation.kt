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
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  A single-queue station that seizes units from a shared [SResourcePool] rather
 *  than from its own resource. Several such stations sharing one pool model a set
 *  of servers (the pool) serving several distinct queues; a unit freed by any
 *  station can serve whichever waiting station the pool notifies first.
 *
 *  Activity-time determination mirrors [SingleQStation] (a fixed random variable,
 *  the QObject's value object, or a supplied [StationActivityTimeIfc]).
 *
 *  @param parent the model element serving as this station's parent
 *  @param pool the shared resource pool to seize from
 *  @param activityTime the processing time at the station
 *  @param nextReceiver the receiver of processed instances
 *  @param name the name of the station
 */
open class ResourcePoolStation(
    parent: ModelElement,
    private val pool: SResourcePool,
    activityTime: RVariableIfc = ConstantRV.ZERO,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : Station(parent, nextReceiver, name = name), ActivityStationCIfc {

    /** The shared pool this station seizes from. */
    val resourcePool: SResourcePoolCIfc
        get() = pool

    /**
     *  If true, the station uses the QObject's value object for the activity time.
     */
    var useQObjectForActivityTime: Boolean = false

    /** If set, supplies the activity time when not using the QObject value object. */
    var activityTime: StationActivityTimeIfc? = null

    private val myActivityTimeRV: RandomVariable = RandomVariable(this, activityTime, name = "${this.name}:ActivityRV")
    override val activityTimeRV: RandomVariableCIfc
        get() = myActivityTimeRV

    private val myWaitingQ: Queue<QObject> = Queue(this, "${this.name}:Q")
    val waitingQ: QueueCIfc<QObject>
        get() = myWaitingQ

    init {
        // When the shared pool frees units, serve this station's queue.
        pool.attachUnitsAvailableListener { serveWaitingCustomers() }
    }

    override fun process(arrivingQObject: QObject) {
        myWaitingQ.enqueue(arrivingQObject)
        if (pool.hasAvailableUnits) {
            serveNext()
        }
    }

    private fun serveWaitingCustomers() {
        while (pool.hasAvailableUnits && myWaitingQ.isNotEmpty) {
            serveNext()
        }
    }

    private fun serveNext() {
        val nextCustomer = myWaitingQ.removeNext()!!
        pool.seize()
        schedule(this::endOfProcessing, activityTime(nextCustomer), nextCustomer)
    }

    protected open fun activityTime(qObject: QObject): Double {
        if (useQObjectForActivityTime) {
            checkNotNull(qObject.valueObject) { "The station was told to use the qObject's valueObject for the activity time, but it was null" }
            return qObject.valueObject!!.value
        }
        activityTime?.let { return it.activityTime(qObject, this) }
        return myActivityTimeRV.value
    }

    private fun endOfProcessing(event: KSLEvent<QObject>) {
        val leaving: QObject = event.message!!
        // releasing notifies the pool's stations (including this one) to serve waiting work
        pool.release()
        sendToNextReceiver(leaving)
    }
}
