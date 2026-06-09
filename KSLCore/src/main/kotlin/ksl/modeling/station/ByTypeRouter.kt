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

import ksl.simulation.ModelElement

/**
 *  Routes each arriving QObject to the receiver mapped to its
 *  [ModelElement.QObject.qObjectType], falling back to [default] when the type is
 *  not present in [typeMap]. This is the natural routing primitive for a
 *  multi-class network, where each class follows its own path.
 *
 *  @param typeMap maps a QObject type id to its receiver
 *  @param default the receiver used when the instance's type is not in the map
 */
class ByTypeRouter(
    typeMap: Map<Int, QObjectReceiverIfc>,
    private val default: QObjectReceiverIfc
) : Router() {

    private val myTypeMap: Map<Int, QObjectReceiverIfc> = typeMap.toMap()

    override fun selectNextReceiver(qObject: ModelElement.QObject): QObjectReceiverIfc =
        myTypeMap[qObject.qObjectType] ?: default

    override fun destinations(): List<QObjectReceiverIfc> =
        (myTypeMap.values + default).distinct()
}
