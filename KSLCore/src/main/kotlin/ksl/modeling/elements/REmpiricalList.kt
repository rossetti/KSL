/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.elements

import ksl.simulation.ModelElement
import ksl.utilities.random.robj.DEmpiricalList
import ksl.utilities.random.robj.RElementIfc

class REmpiricalList<T>(
    parent: ModelElement,
    private val dEmpiricalList: DEmpiricalList<T>,
    name: String? = null
) : ModelElement(parent, name), RElementIfc<T> by dEmpiricalList, RandomElementIfc {

    //TODO how to ensure dEmpiricalList uses the model's stream provider

    init {
        warmUpOption = false
    }

    constructor(
        parent: ModelElement,
        elements: List<T>,
        theCDF: DoubleArray,
        streamNumber: Int = 0,
        name: String? = null
    ) : this(parent, DEmpiricalList<T>(elements, theCDF, streamNumber, parent.streamProvider), name)

}