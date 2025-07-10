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

/**
 *  Provides a model element representation for a [DUniformList]
 */
class RandomList<T : Any> private constructor(
    parent: ModelElement,
    private val dUniformList: DUniformList<T>,
    name: String? = null
) : ModelElement(parent, name), RElementIfc<T> by dUniformList, RandomElementIfc {

    init {
        warmUpOption = false
    }

    /**
     *  Allows randomly selecting with equal probability from the elements of the list.
     *  @param parent the parent of this element
     *  @param elements the elements in the list
     *  @param streamNum the stream number to use from the provider. The default is 0, which
     *  is the next stream.
     *  @param name the optional name of the model element
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        parent: ModelElement,
        elements: MutableList<T>,
        streamNum: Int = 0,
        name: String? = null
    ) : this(parent, DUniformList<T>(elements, streamNum, parent.streamProvider), name)

}