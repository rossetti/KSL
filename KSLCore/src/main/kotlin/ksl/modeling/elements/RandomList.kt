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
import ksl.utilities.random.robj.DUniformList
import ksl.utilities.random.robj.RElementIfc

class RandomList<T : Any>(
    parent: ModelElement,
    private val dUniformList: DUniformList<T>,
    name: String? = null
) : ModelElement(parent, name), RElementIfc<T> by dUniformList, RandomElementIfc {

    //TODO how to ensure dUniformList uses the model's stream provider

    init {
        warmUpOption = false
    }

    constructor(
        parent: ModelElement,
        elements: MutableList<T>,
        streamNumber: Int = 0,
        name: String? = null
    ) : this(parent, DUniformList<T>(elements, streamNumber, parent.streamProvider), name)

}