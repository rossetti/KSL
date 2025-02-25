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

import ksl.modeling.variable.*
import ksl.simulation.ModelElement

/**
 *  Can be used to capture the total number disposed through this station
 *  and the total time in the system at this dispose. The optionally supplied
 *  number in the system response will be decremented by 1 if supplied.
 */
class DisposalStation<T: ModelElement.QObject>(
    parent: ModelElement,
    private val myNumInSystem: TWResponse? = null,
    name: String? = null
) : ModelElement(parent, name), QObjectReceiverIfc<T> {

    private val myNumDisposed: Counter = Counter(this, "${this.name}:TotalProcessed")
    val numDisposed: CounterCIfc
        get() = myNumDisposed
    private val mySystemTime: Response = Response(this, "${this.name}:TotalSystemTime")
    val totalTime: ResponseCIfc
        get() = mySystemTime

    override fun receive(arrivingQObject: T) {
        mySystemTime.value = arrivingQObject.currentTime - arrivingQObject.createTime
        myNumDisposed.increment()
        myNumInSystem?.decrement()
    }
}
