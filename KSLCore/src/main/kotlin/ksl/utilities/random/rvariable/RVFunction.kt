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

package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

class RVFunction(
    theFirst: RVariableIfc,
    theSecond: RVariableIfc,
    theTransform: ((f: Double, s: Double) -> Double) = { f: Double, s: Double -> f + s },
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : RVariable(stream, name) {

    private val first = theFirst.instance(rnStream)
    private val second = theSecond.instance(rnStream)
    private val transform = theTransform

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return RVFunction(first, second, transform, rnStream, name)
    }

    override fun generate(): Double {
        return transform(first.value, second.value)
    }

    override fun useStreamNumber(streamNumber: Int) {
        super.useStreamNumber(streamNumber) // sets rnStream
        first.useStreamNumber(streamNumber)
        second.useStreamNumber(streamNumber)
    }
}

