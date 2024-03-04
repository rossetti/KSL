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

package ksl.utilities.random.markovchain

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariable
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.Statistic

class TwoStateMarkovChain(
    theInitialState: Int = 1,
    val p01: Double = 0.5,
    val p11: Double = 0.5,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {

    init {
        require((0.0 < p11) && (p11 < 1.0)) { "P11 must be in (0,1)" }
        require((0.0 < p01) && (p01 < 1.0)) { "P01 must be in (0,1)" }
        require((theInitialState == 0) || (theInitialState == 1)) { "The initial state must be 0 or 1" }
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return TwoStateMarkovChain(initialState, p01, p11, stream)
    }

    var initialState = theInitialState
        set(value) {
            require((value == 0) || (value == 1)) { "The initial state must be 0 or 1" }
            field = value
        }

    val p0 = 1.0 - (p01 / (1.0 - p11 + p01))

    val p1 = 1.0 - p0

    var state = initialState
        private set(value) {
            require((value == 0) || (value == 1)) { "The state must be 0 or 1" }
            field = value
        }

    override fun instance(): TwoStateMarkovChain {
        return TwoStateMarkovChain(initialState, p01, p11, rnStream)
    }

    override fun generate(): Double {
        if (state == 1) {
            if (rnStream.randU01() > p11) {
                state = 0
            }
        } else {
            if (rnStream.randU01() > p01) {
                state = 1
            }
        }
        return state.toDouble()
    }

    /**
     * Sets the state back to the initial state
     */
    fun reset() {
        state = initialState
    }
}

fun main() {
    val s = Statistic()
    val d = TwoStateMarkovChain()
    for (i in 1..20000) {
        val x = d.value
        s.collect(x)
        //System.out.println(x);
    }
    println(s)
}