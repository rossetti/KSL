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

import ksl.utilities.GetTimeIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariable
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.IEEErem
import kotlin.math.floor

/**
 *
 * @param timeGetter the thing that will supply the current time
 * @param rateFunction the rate function
 * @param lastRate the last rate
 * @param name the name
 */
open class NHPPTimeBtwEventRVV2(
    private val timeGetter: GetTimeIfc,
    rateFunction: InvertibleCumulativeRateFunctionIfc,
    private val lastRate: Double? = null,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNumber, streamProvider, name) {

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): RVariableIfc {
        return NHPPTimeBtwEventRVV2(timeGetter, myRateFunction, lastRate, streamNumber, rnStreamProvider, name )
    }

    /** Used to schedule the end of cycles if they repeat
     *
     */
    private val myRate1Expo: ExponentialRV = ExponentialRV(1.0, streamNumber, streamProvider, name)

    private val time: Double
        get() = timeGetter.time

    /** Supplied to invert the rate function.
     *
     */
    private var myRateFunction: InvertibleCumulativeRateFunctionIfc = rateFunction

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
        if (lastRate != null) {
            require(lastRate > 0.0) { "The last rate must be > 0" }
            require(lastRate < Double.POSITIVE_INFINITY) { "The last rate must be < infinity" }
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

    //TODO the issue is when should this be called.  It needs to be called before the start of any cycle.
    fun initialize() {
        myCycleStartTime = time
        myPPTime = myCycleStartTime
        myNumCycles = 0
        myUseLastRateFlag = false
    }

    override fun generate(): Double {
        if (myUseLastRateFlag == true) {
            require(lastRate != null) { "The last rate must not be null if using the last rate." }
            // if this option is on the exponential distribution
            // should have been set to use the last rate
            // just return the time between arrivals
            return rnStream.rExponential(1.0/lastRate)
        }
        val t: Double = time // the current time
        //System.out.println("Current time = " + t);
        // exponential time btw events for rate 1 PP
        val x: Double = myRate1Expo.value
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
            if ((myRepeatFlag == false)) {
                // a last rate has been set, use constant rate forever
                myUseLastRateFlag = true
                require(lastRate != null) { "The last rate must not be null if using the last rate." }
                //System.out.println("setting use last rate flag");
                // set source for last rate, will be used from now on
                // ensure new rv uses same stream with new parameter
                //System.out.printf("%f > setting the rate to last rate = %f %n", getTime(), myLastRate);
                // need to use the residual amount, to get the time of the next event
                // using the inverse function for the final constant rate
                val tone = myRateFunction.timeRangeUpperLimit + residual / lastRate
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