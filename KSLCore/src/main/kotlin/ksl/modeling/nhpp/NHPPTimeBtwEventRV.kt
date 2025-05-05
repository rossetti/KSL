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
package ksl.modeling.nhpp

import ksl.modeling.variable.RandomVariable
import ksl.simulation.ModelElement
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.KSLRandom
import kotlin.math.IEEErem
import kotlin.math.floor

/**
 *
 * @param parent the parent
 * @param rateFunction the rate function
 * @param lastRate the last rate
 * @param name the name
 */
open class NHPPTimeBtwEventRV(
    parent: ModelElement,
    rateFunction: InvertibleCumulativeRateFunctionIfc,
    lastRate: Double = Double.NaN,
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : RandomVariable(parent, ExponentialRV(1.0, stream), name) {

    constructor(
        parent: ModelElement,
        rateFunction: InvertibleCumulativeRateFunctionIfc,
        lastRate: Double = Double.NaN,
        streamNum: Int,
        name: String? = null
    ) : this(parent, rateFunction, lastRate, KSLRandom.rnStream(streamNum), name)

    override fun instance(streamNum: Int): NHPPTimeBtwEventRV {
        return NHPPTimeBtwEventRV(parent!!, myRateFunction, myLastRate, streamNum, null)
    }

    /** Used to schedule the end of cycles if they repeat
     *
     */
    private val myRate1Expo: ExponentialRV = initialRandomSource as ExponentialRV
    private val myRNStream: RNStreamIfc = myRate1Expo.rnStream

    /** Supplied to invert the rate function.
     *
     */
    private var myRateFunction: InvertibleCumulativeRateFunctionIfc = rateFunction

    /** If supplied and the repeat flag is false then this rate will
     * be used after the range of the rate function has been passed
     *
     */
    private var myLastRate = Double.NaN

    /** Indicates whether the rate function should repeat
     * when its range has been covered
     *
     */
    private var myRepeatFlag = true

    /** The length of a cycle if it repeats
     *
     */
    private var myCycleLength = 0.0

    init {
        if (!lastRate.isNaN()) {
            require(lastRate >= 0.0) { "The rate must be >= 0" }
            require(lastRate < Double.POSITIVE_INFINITY) { "The rate must be < infinity" }
            myLastRate = lastRate
            myRepeatFlag = false
        }
        if (myRepeatFlag == true) {
            myCycleLength = myRateFunction.timeRangeUpperLimit - myRateFunction.timeRangeLowerLimit
        }
    }

    /** Holds the time that the cycle started, where a cycle
     * is the time period over which the rate function is defined.
     *
     */
    private var myCycleStartTime = 0.0

    /** The number of cycles completed if cycles
     *
     */
    private var myNumCycles = 0

    /** Holds the time of the last event from the underlying Poisson process
     *
     */
    private var myPPTime = 0.0

    /** Turned on if the time goes past the rate function's range
     * and a last rate was supplied
     *
     */
    private var myUseLastRateFlag = false

    /** the rate function for the random variable.
     *
     */
    var rateFunction: InvertibleCumulativeRateFunctionIfc
        get() = myRateFunction
        set(rateFunction) {
            myRateFunction = rateFunction
        }

    final override fun initialize() {
        myCycleStartTime = time
        myPPTime = myCycleStartTime
        myNumCycles = 0
        myUseLastRateFlag = false
    }

    final override val value: Double
        get() {
            if (myUseLastRateFlag == true) {
                // if this option is on the exponential distribution
                // should have been set to use the last rate
                // just return the time between arrivals
                return randomSource.value
            }
            val t: Double = time // the current time
            //System.out.println("Current time = " + t);
            // exponential time btw events for rate 1 PP
            val x: Double = randomSource.value
            // compute the time of the next event on the rate 1 PP scale
            val tppne = myPPTime + x
            // tne cannot go past the rate range of the cumulative rate function
            // if this happens then the corresponding time will be past the
            // time range of the rate function
            val crul = myRateFunction.cumulativeRateRangeUpperLimit
            //System.out.println("tppne = " + tppne);
            //System.out.println("crul =" + crul);
            if (tppne >= crul) {
                // compute the residual into the next appropriate cycle
                val n = floor(tppne / crul).toInt()
                val residual = tppne.IEEErem(crul)
                //System.out.println("residual = " + residual);
                // must either repeat or use constant rate forever
                if (myRepeatFlag == false) {
                    // a last rate has been set, use constant rate forever
                    myUseLastRateFlag = true
                    //System.out.println("setting use last rate flag");
                    // set source for last rate, will be used from now on
                    // ensure new rv uses same stream with new parameter
                    //System.out.printf("%f > setting the rate to last rate = %f %n", getTime(), myLastRate);
                    val e = ExponentialRV(1.0 / myLastRate, myRNStream)
                    // update the random source
                    randomSource = e
                    // need to use the residual amount, to get the time of the next event
                    // using the inverse function for the final constant rate
                    val tone = myRateFunction.timeRangeUpperLimit + residual / myLastRate
                    //System.out.println("computing tone using residual: tone = " + tone);
                    return tone - t
                }
                //  set up to repeat
                myPPTime = residual
                myNumCycles = myNumCycles + n
                //			myCycleStartTime = myRateFunction.getTimeRangeUpperLimit();
            } else {
                myPPTime = tppne
            }
            val nt = myCycleLength * myNumCycles + myRateFunction.inverseCumulativeRate(myPPTime)
            return nt - t
        }
}