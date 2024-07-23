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
    parent: ModelElement,
    val countDisposals: Boolean = false,
    val captureTotalTime: Boolean = false,
    name: String? = null
) : Station(parent, null, name = name) {

    private var myNumDisposed: Counter? = null
    private var mySystemTime: Response? = null

    init {
        if (countDisposals) {
            myNumDisposed = Counter(this, "${this.name}:NumDisposed")
        }
        if (captureTotalTime) {
            mySystemTime = Response(this, "${this.name}:TotalSystemTime")
        }
    }

    val numDisposed: CounterCIfc?
        get() = myNumDisposed

    val totalTime: ResponseCIfc?
        get() = mySystemTime

    override fun receive(qObject: QObject) {
        mySystemTime?.value = time - qObject.createTime
        myNumDisposed?.increment()
    }

}