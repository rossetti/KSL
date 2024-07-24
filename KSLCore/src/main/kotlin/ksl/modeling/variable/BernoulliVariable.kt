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

package ksl.modeling.variable

import ksl.modeling.elements.RandomElement
import ksl.modeling.elements.RandomElementIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.BernoulliRV

class BernoulliVariable<T>(
    parent: ModelElement,
    private val bernoulliRV: BernoulliRV,
    private val success: T,
    private val failure: T,
    name: String? = null
) : RandomElement(parent, bernoulliRV, name) {

    override var initialRandomSource: RandomIfc
        get() = super.initialRandomSource
        set(value) {
            require(value is BernoulliRV) { "The initial random source must be a BernoulliRV" }
            super.initialRandomSource = value
        }

    /** Returns a randomly selected value
     */
    val randomElement: T
        get() = if (bernoulliRV.boolValue) success else failure

    /** Returns a randomly selected value
     */
    fun sample(): T {
        return randomElement
    }

    /** Returns sample of [size] of type T
     *
     * @return randomly selected elements as a list
     */
    fun sample(size: Int): List<T> {
        require(size > 0) { "The size of the sample must be at least 1." }
        val list = mutableListOf<T>()
        for (i in 0 until size) {
            list.add(randomElement)
        }
        return list
    }

}