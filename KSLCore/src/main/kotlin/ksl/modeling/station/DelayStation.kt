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

import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc

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
 *  @param delayTime the delay time at the station
 *  @param nextReceiver the receiving location that will receive the processed qObjects
 *  once the processing has been completed. A default of null, indicates that there is no
 *  receiver. If no receiver is present, the processed qObject are sent silently nowhere.
 *  @param name the name of the station
 */
class DelayStation(
    parent: ModelElement,
    delayTime: RandomIfc,
    nextReceiver: QObjectReceiverIfc?,
    name: String? = null
) : Station(parent, nextReceiver, name = name) {

    private var myDelayTimeRV: RandomVariable = RandomVariable(this, delayTime, "${this.name}:DelayRV")
    val delayTimeRV: RandomSourceCIfc
        get() = myDelayTimeRV

    private val myNS: TWResponse = TWResponse(this, "${this.name}:NS")
    val numInSystem: TWResponseCIfc
        get() = myNS

    override fun receive(qObject: QObject) {
        myNS.increment()
        val delayTime = qObject.valueObject?.value ?: myDelayTimeRV.value
        schedule(this::endDelayAction, delayTime, qObject)
    }

    private fun endDelayAction(event: KSLEvent<QObject>) {
        val finishedObject = event.message!!
        myNS.decrement()
        sendToNextReceiver(finishedObject)
    }
}