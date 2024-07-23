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

import ksl.modeling.elements.REmpiricalList
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

class NWayByChanceSender(
    parent: ModelElement,
    elements: List<ReceiveQObjectIfc>,
    theCDF: DoubleArray,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : Station(parent, null, name) {

    private var myReceiverList = REmpiricalList(this, elements, theCDF, stream,
        "${this.name}:REmpiricalList")

    override fun receive(qObject: QObject) {
        myReceiverList.element.receive(qObject)
    }
}