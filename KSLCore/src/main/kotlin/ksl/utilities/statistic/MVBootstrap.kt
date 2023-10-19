/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rng.RNStreamChangeIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.robj.DPopulation

/**
 *  Given some data, produce multiple estimated statistics
 *  from the data and store the estimated quantities in
 *  the returned array. It is up to the user to interpret
 *  the array values appropriately.
 */
interface MVBSEstimatorIfc {
    /**
     * the expected size of the array returned from estimate()
     */
    val dimension: Int

    fun estimate(data: DoubleArray): DoubleArray
}

/**
 *  Collects statistics for each dimension
 */
class MVStatistic(val dimension: Int = 2) {
    init {
        require(dimension >= 2) {"The dimension must be >= 2 to be considered multi-variate"}
    }

    val statistics = List(dimension) { Statistic(name = "stat_$it") }

    /**
     *  Statistics are collected on the dimensions of the supplied array.
     *  For example, for each observation[0] presented statistics are collected
     *  across all presented values of observation[0]. For each array
     *  dimension, there will be a Statistic that summarizes the statistical
     *  properties of that dimension.
     */
    fun collect(observation: DoubleArray) {
        require(observation.size == dimension) { "The size of the observation array must match the dimension of the collector." }
        for ((i, x) in observation.withIndex()) {
            statistics[i].collect(x)
        }
    }

    fun reset() {
        for (s in statistics) {
            s.reset()
        }
    }

}

class MVBootstrap(
    originalData: DoubleArray,
    val dimension: Int = 2,
    name: String? = null
) : IdentityIfc by Identity(name), RNStreamControlIfc, RNStreamChangeIfc {

    init {
        require(originalData.size > 1) { "The supplied bootstrap original data had only 1 data point" }
        require(dimension >= 2) {"The dimension must be >= 2 to be considered multi-variate"}
    }

    private val myOriginalData: DoubleArray = originalData.copyOf()
    private val myOriginalPop: DPopulation = DPopulation(myOriginalData)
    private val myAcrossBSStat =  MVStatistic(dimension)

    override var rnStream: RNStreamIfc
        get() = myOriginalPop.rnStream
        set(value) {
            myOriginalPop.rnStream = value
        }

    /**
     * Tells the stream to start producing antithetic variates
     *
     */
    override var antithetic: Boolean
        get() = myOriginalPop.antithetic
        set(value) {
            myOriginalPop.antithetic = value
        }

    override var advanceToNextSubStreamOption: Boolean
        get() = myOriginalPop.advanceToNextSubStreamOption
        set(b) {
            myOriginalPop.advanceToNextSubStreamOption = b
        }

    override var resetStartStreamOption: Boolean
        get() = myOriginalPop.resetStartStreamOption
        set(b) {
            myOriginalPop.resetStartStreamOption = b
        }

    /**
     * The resetStartStream method will position the RNG at the beginning of its
     * stream. This is the same location in the stream as assigned when the RNG
     * was created and initialized.
     */
    override fun resetStartStream() {
        myOriginalPop.resetStartStream()
    }

    /**
     * Resets the position of the RNG at the start of the current sub-stream
     */
    override fun resetStartSubStream() {
        myOriginalPop.resetStartSubStream()
    }

    /**
     * Positions the RNG at the beginning of its next sub-stream
     */
    override fun advanceToNextSubStream() {
        myOriginalPop.advanceToNextSubStream()
    }

    /**
     * @return the number of requested bootstrap samples
     */
    var numBootstrapSamples = 0
        private set

    /**
     *
     * @return a copy of the original data
     */
    val originalData: DoubleArray
        get() = myOriginalData.copyOf()


    /**
     * @return the estimate from the supplied EstimatorIfc based on the original data
     */
    var originalDataEstimate = doubleArrayOf()
        private set

    /** This method changes the underlying state of the Bootstrap instance by performing
     * the bootstrap sampling.
     *
     * @param numBootstrapSamples the number of bootstrap samples to generate
     * @param estimator           a function of the data
     * @param saveBootstrapSamples   indicates that the statistics and data of each bootstrap generate should be saved
     */
    fun generateSamples(
        numBootstrapSamples: Int,
        estimator: MVBSEstimatorIfc,
        saveBootstrapSamples: Boolean = false
    ) {
//        require(numBootstrapSamples > 1) { "The number of bootstrap samples must be greater than 1" }
//        this.numBootstrapSamples = numBootstrapSamples
//        myAcrossBSStat.reset()
//        myBSEstimates.clearData()
//        for (s in myBSArrayList) {
//            s.clearData()
//        }
//        myBSArrayList.clear()
//        originalDataEstimate = estimator.estimate(myOriginalData)
//        for (i in 0 until numBootstrapSamples) {
//            val sample: DoubleArray = myOriginalPop.sample(myOriginalPop.size())
//            val x = estimator.estimate(sample)
//            myAcrossBSStat.collect(x)
//            myBSEstimates.save(x)
//            if (saveBootstrapSamples) {
//                val das = DoubleArraySaver()
//                das.save(sample)
//                myBSArrayList.add(das)
//            }
//        }
    }
}