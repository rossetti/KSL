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
 *  The rate function covers a period of time (cycle), which may be infinite or finite.
 *  The rate function determines the rate for the process at specific time instances.
 *  If the range of the rate function is finite, the user needs to define what happens
 *  when the end of the range has been reached. The default behavior (if no last rate)
 *  is supplied is to reset the computation to the start of the cycle. However,
 *  if a last rate is supplied, then once the end of the rate function's range
 *  is covered, the last rate will be used for all future generation. That is,
 *  the process becomes a Poisson process with a constant non-time varying mean rate.
 *
 * @param timeGetter the thing that will supply the current time during the generation process
 * The generation process assumes that time is supplied as a non-decreasing function.
 * @param rateFunction the rate function
 * @param lastRate the last rate. The default is null. If the last rate is
 * supplied then the process will not repeat after the range of the rate function is covered.
 * @param name the name
 */
open class NHPPTimeBtwEventRVV2(
    private val timeGetter: GetTimeIfc,
    var rateFunction: InvertibleCumulativeRateFunctionIfc,
    private val lastRate: Double? = null,
    streamNumber: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : RVariable(streamNumber, streamProvider, name) {

    /** Indicates whether the rate function should repeat
     * when its range has been covered
     *
     */
    var repeatsFlag = true
        private set

    /** The length of a cycle if it repeats
     *
     */
    var cycleLength = 0.0
        private set

    init {
        if (lastRate != null) {
            require(lastRate > 0.0) { "The last rate must be > 0" }
            require(lastRate < Double.POSITIVE_INFINITY) { "The last rate must be < infinity" }
            require(!lastRate.isNaN()) {"The last rate must not be NaN"}
            repeatsFlag = false
        }
        if (repeatsFlag == true) {
            cycleLength = rateFunction.timeRangeUpperLimit - rateFunction.timeRangeLowerLimit
        }
    }

    /** Holds the time that the cycle started, where a cycle
     * is the time period over which the rate function is defined.
     *
     */
    var cycleStartTime = 0.0
        private set

    /** The number of cycles completed if cycles
     *
     */
    var numCycles = 0
        private set

    /** Holds the time of the last event from the underlying Poisson process
     *
     */
    private var myPPTime = 0.0

    /** Turned on if the time goes past the rate function's range
     * and a last rate was supplied
     *
     */
    private var myUseLastRateFlag = false

    override fun instance(streamNumber: Int, rnStreamProvider: RNStreamProviderIfc): RVariableIfc {
        return NHPPTimeBtwEventRVV2(timeGetter, rateFunction, lastRate, streamNumber, rnStreamProvider, name )
    }

    private val myRate1Expo: ExponentialRV = ExponentialRV(1.0, streamNumber, streamProvider, name)

    val time: Double
        get() = timeGetter.time

    /**
     * This function should be called to initialize the generation process.
     * This function resets the process to start at the current time as
     * if no cycles have occurred and as if the rate function is at the beginning
     * of its defined range (typically time 0.0). Within a DEDS model,
     * this function should be called at the beginning of each replication (typically)
     * within a model element's initialize() function.
     */
    fun initialize() {
        cycleStartTime = time
        myPPTime = cycleStartTime
        numCycles = 0
        myUseLastRateFlag = false
    }

    override fun generate(): Double {
        if (myUseLastRateFlag == true) {
            require(lastRate != null) { "The last rate must not be null if using the last rate." }
            // if this option is on the exponential distribution
            // should be set to use the last rate
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
        val crul = rateFunction.cumulativeRateRangeUpperLimit
        //System.out.println("tppne = " + tppne);
        //System.out.println("crul =" + crul);
        if (tppne >= crul) {
            // compute the residual into the next appropriate cycle
            val n = floor(tppne / crul).toInt()
            val residual = tppne.IEEErem(crul)
            //System.out.println("residual = " + residual);
            // must either repeat or use constant rate forever
            if ((repeatsFlag == false)) {
                // a last rate has been set, use constant rate forever
                myUseLastRateFlag = true
                require(lastRate != null) { "The last rate must not be null if using the last rate." }
                //System.out.println("setting use last rate flag");
                // set source for last rate, will be used from now on
                // ensure new rv uses same stream with new parameter
                //System.out.printf("%f > setting the rate to last rate = %f %n", getTime(), myLastRate);
                // need to use the residual amount, to get the time of the next event
                // using the inverse function for the final constant rate
                val tone = rateFunction.timeRangeUpperLimit + residual / lastRate
                //System.out.println("computing tone using residual: tone = " + tone);
                return tone - t
            }
            //  set up to repeat
            myPPTime = residual
            numCycles = numCycles + n
            //			myCycleStartTime = myRateFunction.getTimeRangeUpperLimit();
        } else {
            myPPTime = tppne
        }
        val nt = cycleLength * numCycles + rateFunction.inverseCumulativeRate(myPPTime)
        return nt - t
    }

}