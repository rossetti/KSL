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

import ksl.utilities.KSLArrays
import ksl.utilities.copyOf
import ksl.utilities.hasElement
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariable
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.IntegerFrequency


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
open class DMarkovChain(
    theInitialState: Int = 1,
    transMatrix: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {
    init {
        require(KSLArrays.isSquare(transMatrix)) { "The probability transition matrix must be square" }
    }

    constructor(
        theInitialState: Int = 1,
        transMatrix: Array<DoubleArray>,
        streamNum: Int
    ) : this(theInitialState, transMatrix, KSLRandom.rnStream(streamNum))

    protected var myStates: IntArray = IntArray(transMatrix.size)
    val states: IntArray
        get() = myStates.copyOf()

    val numStates: Int = transMatrix.size

    protected var myCDFs: Array<DoubleArray> = Array(transMatrix.size) { DoubleArray(transMatrix.size) }

    init {
        for (r in transMatrix.indices) {
           // myCDFs[r] = KSLRandom.makeCDF(transMatrix[r])
            myCDFs[r] = makeCDF(transMatrix[r])
            myStates[r] = r + 1
        }
        require(myStates.hasElement(theInitialState)) { "The initial state, $theInitialState is not a valid state" }
    }

    private fun makeCDF(pmf: DoubleArray) : DoubleArray{
        require(pmf.size >= 2){"The array of probabilities must have 2 or more elements to be a PMF"}
        var cp = 0.0
        for((i, p) in pmf.withIndex()){
            require((0.0 <= p) && (p <= 1.0 )){"The supplied element p($i$) = $p and was not a valid probability"}
            cp = cp + p
        }
        require(KSLMath.equal(cp, 1.0)) {"The array of probabilities summed to more than 1.0"}
        val cdf = DoubleArray(pmf.size)
        var sum = 0.0
        for (i in 0 until pmf.size - 1) {
            sum = sum + pmf[i]
            cdf[i] = sum
        }
        cdf[pmf.size - 1] = 1.0
        return cdf
    }

    protected val myTransProb = transMatrix.copyOf()
    val transitionMatrix
        get() = myTransProb.copyOf()
    val transCDF
        get() = myCDFs.copyOf()

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
        protected set

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return DMarkovChain(initialState, myTransProb, stream)
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

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("States")
        for((i, j) in myStates.withIndex()) {
            sb.appendLine("state[$i] = $j")
        }
        sb.appendLine("Transition Matrix")
        for(array in myTransProb){
            sb.appendLine(array.joinToString())
        }
        return sb.toString()
    }

    /**
     *  Simulates the chain forward starting from the [startState] until
     *  the desired state [desiredState] is reached for the first time and
     *  returns the number of transitions required to reach the desired state
     *  for the first time.
     *
     *  The desired state may be unreachable from the starting state. The transition limit
     *  [transitionLimit] represents the maximum number of transitions allowed
     *  before returning. By default, this is 10000. The transition count
     *  will be Int.MAX_VALUE in this case, essentially infinity.
     *
     *  @return the returned value represents one observation of the first passage
     *  time from the starting state to the desired state.
     */
    fun countTransitionsUntil(
        startState: Int,
        desiredState: Int,
        transitionLimit: Int = 10000
    ): Int {
        initialState = startState
        reset()
        var n = 0
        do {
            n++
            if (n == transitionLimit){
                return Int.MAX_VALUE
            }
        } while (nextState() != desiredState)
        return n
    }

    /**
     *  Estimates the first passage time distribution from the
     *  starting state to the desired state as an IntegerFrequency
     *  based on the provided sample size [sampleSize].
     *
     *  The desired state may be unreachable from the starting state. The transition limit
     *  [transitionLimit] represents the maximum number of transitions allowed
     *  before returning. By default, this is 10000. The transition count
     *  will be Int.MAX_VALUE in this case, essential infinity.
     */
    fun firstPassageFrequency(
        sampleSize: Int,
        startState: Int,
        desiredState: Int,
        transitionLimit: Int = 10000
    ) : IntegerFrequency {
        val f = IntegerFrequency(name = "FirstPassageTime from $startState to $desiredState")
        for (i in 1..sampleSize){
            val fpTime = countTransitionsUntil(startState, desiredState, transitionLimit)
            f.collect(fpTime)
            if (fpTime == Int.MAX_VALUE){
                return f
            }
        }
        return f
    }

}
