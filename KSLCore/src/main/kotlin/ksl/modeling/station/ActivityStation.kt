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

import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  Models a simple delay.
 *
 *  A QObject may have an object that implements the GetValueIfc attached. If so,
 *  the current value from this object is used as the delay time at the station. If
 *  not attached, then the specified delay time for the station will be used. Thus,
 *  a processed QObject instance can bring its own delay time.  In addition, a QObject
 *  may have a ListIterator<QObjectReceiverIfc> attached. If one is attached, the
 *  iterator will be used to determine where to send the qObject. If an iterator is
 *  not attached, then the specified next receiver (if not null)  will be used. Thus,
 *  a processed QObject instance can determine where it goes to next after processing.
 *
 *  @param parent the model element serving as this element's parent
 *  @param activityTime the delay time at the station. The default is a 0.0 delay.
 *  @param nextReceiver the receiving location that will receive the processed qObjects
 *  once the processing has been completed. A default of null, indicates that there is no
 *  receiver. If no receiver is present, the processed qObject are sent silently nowhere.
 *  @param name the name of the station
 */
open class ActivityStation(
    parent: ModelElement,
    activityTime: RVariableIfc = ConstantRV.ZERO,
    nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
    name: String? = null
) : Station(parent, nextReceiver, name = name), ActivityStationCIfc{

    /**
     *  If true, the instance will attempt to use the QObject that is experiencing
     *  the activity to determine the activity time by referencing the QObject's
     *  valueObject. If false (the default), the supplied activity time will be used
     */
    var useQObjectForActivityTime: Boolean = false

    /**
     *  If set and the station does not use the qObject for determining the activity time,
     *  then the supplied function will be used.
     */
    var activityTime: StationActivityTimeIfc? = null

    protected var myActivityTimeRV: RandomVariable = RandomVariable(this, activityTime, "${this.name}:ActivityRV")
    override val activityTimeRV: RandomVariableCIfc
        get() = myActivityTimeRV

    override fun process(arrivingQObject: QObject) {
        schedule(this::endActivityAction, activityTime(arrivingQObject), arrivingQObject)
    }

    /**
     *  Could be overridden to supply different approach for determining the activity delay.
     *  The current approach does the following.
     *  1. Checks the `useQObjectForActivityTime` option and if true uses the
     *  qObject's valueObject to determine the activity time. If this option is used, an illegal state
     *  exception will occur if the qObject's valueObject property has not been set.
     *  2. Checks if the `activityTime` property has been set and if so uses the
     *  supplied `StationActivityTimeIfc` to determine the activity time.
     *  3. If neither the first two options are used, then the activity time
     *  will be determined by the supplied `RVariableIfc`, which is zero by default.
     */
    protected open fun activityTime(qObject: QObject) : Double {
        if (useQObjectForActivityTime) {
            checkNotNull(qObject.valueObject) {"The station was told to use the qObject's valueObject for the activity time, but it was null"}
            return qObject.valueObject!!.value
        }
        activityTime?.let { return it.activityTime(qObject, this) }
        return myActivityTimeRV.value
    }

    private fun endActivityAction(event: KSLEvent<QObject>) {
        val finishedObject = event.message!!
        sendToNextReceiver(finishedObject)
    }
}