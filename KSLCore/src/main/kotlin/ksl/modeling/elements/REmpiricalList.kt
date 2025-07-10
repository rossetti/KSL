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

class REmpiricalList<T> private constructor(
    parent: ModelElement,
    private val dEmpiricalList: DEmpiricalList<T>,
    name: String? = null
) : ModelElement(parent, name), RElementIfc<T> by dEmpiricalList, RandomElementIfc {

    init {
        warmUpOption = false
    }
    /**
     *  Allows randomly selecting using the CDF probability from the elements of the list.
     *  @param parent the parent of this element
     *  @param elements the elements in the list
     * @param theCDF the CDF for the elements in the list
     *  @param streamNum the stream number to use from the provider. The default is 0, which
     *  is the next stream.
     *  @param name the optional name of the model element
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        parent: ModelElement,
        elements: List<T>,
        theCDF: DoubleArray,
        streamNum: Int = 0,
        name: String? = null
    ) : this(parent, DEmpiricalList<T>(elements, theCDF, streamNum, parent.streamProvider), name)

}