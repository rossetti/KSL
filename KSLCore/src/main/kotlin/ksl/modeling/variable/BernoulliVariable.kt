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
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.robj.BernoulliPickerIfc
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.KSLRandom

class BernoulliVariable<T>(
    parent: ModelElement,
    private val bernoulliRV: BernoulliRV,
    override val success: T,
    override val failure: T,
    name: String? = null
) : RandomElement(parent, bernoulliRV, name), BernoulliPickerIfc<T> {

    init {
        require(success != failure) {"The success and failure options cannot be the same."}
    }

    constructor(
        parent: ModelElement,
        successProb: Double,
        success: T,
        failure: T,
        stream: RNStreamIfc,
        name: String? = null
    ) : this(parent, BernoulliRV(successProb, stream), success, failure, name)

    constructor(
        parent: ModelElement,
        successProb: Double,
        success: T,
        failure: T,
        streamNum: Int,
        name: String? = null
    ) : this(parent, BernoulliRV(successProb, KSLRandom.rnStream(streamNum)), success, failure, name)

    override var initialRandomSource: RandomIfc
        get() = super.initialRandomSource
        set(value) {
            require(value is BernoulliRV) { "The initial random source must be a BernoulliRV" }
            super.initialRandomSource = value
        }

    override fun resetStartStream() {
        bernoulliRV.resetStartStream()
    }

    override fun resetStartSubStream() {
        bernoulliRV.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        bernoulliRV.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = bernoulliRV.antithetic
        set(value) {
            bernoulliRV.antithetic = value
        }
    override var advanceToNextSubStreamOption: Boolean
        get() = bernoulliRV.advanceToNextSubStreamOption
        set(value) {
            bernoulliRV.advanceToNextSubStreamOption = value
        }
    override var resetStartStreamOption: Boolean
        get() = bernoulliRV.resetStartStreamOption
        set(value) {
            bernoulliRV.resetStartStreamOption = value
        }

    /** Returns a randomly selected value
     */
    override val randomElement: T
        get() = if (bernoulliRV.boolValue) success else failure

    /** Returns a randomly selected value
     */
    override fun sample(): T {
        return randomElement
    }

    /** Returns sample of [size] of type T
     *
     * @return randomly selected elements as a list
     */
    override fun sample(size: Int): List<T> {
        require(size > 0) { "The size of the sample must be at least 1." }
        val list = mutableListOf<T>()
        for (i in 0 until size) {
            list.add(randomElement)
        }
        return list
    }

}