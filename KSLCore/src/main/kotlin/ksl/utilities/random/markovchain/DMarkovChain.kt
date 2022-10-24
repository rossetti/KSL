/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.KSLArrays
import ksl.utilities.copyOf
import ksl.utilities.hasElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariable
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  Randomly generates the states of a discrete Markov Chain. Assumes that
 *  the states are labeled 1, 2, 3, etc.
 *  The transition probabilities are supplied as an array of arrays.
 *  transMatrix[0] holds the array of transition probabilities for transition to each state {p11, p12, p13, .., p1n} for state 1
 *  transMatrix[1] holds the array of transition probabilities for transition to each state {p21, p22, p23, .., p2n} for state 2
 *  etc.
 *  @param theInitialState the initial state
 *  @param transMatrix the single step transition matrix
 *  @param stream the random number stream
 * @author rossetti
 */
class DMarkovChain(
    theInitialState: Int = 1,
    transMatrix: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    init {
        require(KSLArrays.isSquare(transMatrix)) { "The probability transition matrix must be square" }
    }

    private var myStates: IntArray = IntArray(transMatrix.size)

    private var myCDFs: Array<DoubleArray> = Array(transMatrix.size) { DoubleArray(transMatrix.size) }

    init {
        for (r in transMatrix.indices) {
            myCDFs[r] = KSLRandom.makeCDF(transMatrix[r])
            myStates[r] = r + 1
        }
        require(myStates.hasElement(theInitialState)) { " The initial state, $theInitialState is not a valid state" }
    }

    private val transProb = transMatrix.copyOf()

    /**
     *  The initial starting state of the chain
     */
    var initialState = theInitialState
        set(value) {
            require(myStates.hasElement(value)) { " The initial state, $value is not a valid state" }
            field = value
        }

    /**
     *  The current state, with no transition implied. In other words, the last generated state
     */
    var state = initialState
        private set

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return DMarkovChain(initialState, transProb, stream)
    }

    override fun generate(): Double {
        state = KSLRandom.discreteInverseCDF(myStates, myCDFs[state - 1], rnStream)
        return state.toDouble()
    }

    /**
     * Sets the state back to the initial state
     */
    fun reset() {
        state = initialState
    }

    /**
     *  Randomly generates the next state from the current state.
     *  Same as using value, value(), or sample()
     */
    fun nextState(): Int {
        return value.toInt()
    }

}

fun main() {
    val p = arrayOf(doubleArrayOf(0.3, 0.1, 0.6), doubleArrayOf(0.4, 0.4, 0.2), doubleArrayOf(0.1, 0.7, 0.2))

    val mc = DMarkovChain(1, p)
    val f = IntegerFrequency()

    for (i in 1..100000) {
        f.collect(mc.nextState())
    }
    println("True Steady State Distribution")
    println("P{X=1} = " + 238.0 / 854.0)
    println("P{X=2} = " + 350.0 / 854.0)
    println("P{X=3} = " + 266.0 / 854.0)
    println()
    println("Observed Steady State Distribution")
    println(f)

}