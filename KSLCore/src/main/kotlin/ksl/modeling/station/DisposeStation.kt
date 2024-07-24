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
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ModelElement

class DisposeStation(
    private val myNumDisposed: Counter? = null,
    private val mySystemTime: Response? = null,
) : QObjectReceiverIfc {

    val numDisposed: CounterCIfc?
        get() = myNumDisposed

    val totalTime: ResponseCIfc?
        get() = mySystemTime

    override fun receive(qObject: ModelElement.QObject) {
        mySystemTime?.value = qObject.currentTime - qObject.createTime
        myNumDisposed?.increment()
    }

}