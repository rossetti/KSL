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
import ksl.utilities.random.rvariable.EmpiricalRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistics

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
 *  Collects statistics for each dimension of the presented array.
 */
class MVStatistic(val dimension: Int = 2) {
    init {
        require(dimension >= 2) { "The dimension must be >= 2 to be considered multi-variate" }
    }

    val statistics = List(dimension) { Statistic(name = "dim_$it") }

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

    /**
     *  Returns the sample averages for each dimension
     */
    val averages: DoubleArray
        get() {
            val a = DoubleArray(dimension)
            for((i, s) in statistics.withIndex()){
                a[i] = s.average
            }
            return a
        }

    /**
     *  Returns the sample variances for each dimension
     */
    val variances: DoubleArray
        get() {
            val v = DoubleArray(dimension)
            for((i, s) in statistics.withIndex()){
                v[i] = s.variance
            }
            return v
        }
}

class MVBootstrap(
    originalData: DoubleArray,
    val dimension: Int = 2,
    name: String? = null
) : IdentityIfc by Identity(name), RNStreamControlIfc, RNStreamChangeIfc {

    init {
        require(originalData.size > 1) { "The supplied bootstrap original data had only 1 data point" }
        require(dimension >= 2) { "The dimension must be >= 2 to be considered multi-variate" }
    }

    // holds the original data from which the sampling will occur
    private val myOriginalData: DoubleArray = originalData.copyOf()

    // use to perform the sampling from the original data
    private val myOriginalPop: DPopulation = DPopulation(myOriginalData)

    // collects statistics along each dimension of the multi-variate estimates from the bootstrap samples
    private val myAcrossBSStat = MVStatistic(dimension)

    // if requested holds the bootstrap samples
    private val myBSArrayList = mutableListOf<DoubleArraySaver>()

    // holds the estimated values (for each dimension) from the bootstrap samples
    private val myBSEstimates = mutableListOf<DoubleArray>()

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

    /**
     *  Statistics collected across each dimension based on
     *  the estimates computed from each bootstrap sample.
     *  These statistics are cleared whenever generateSamples() is invoked
     *  in order to report statistics on the newly generated bootstrap samples.
     */
    val dimensionStatistics: List<Statistic>
        get() = myAcrossBSStat.statistics

    /** This method changes the underlying state of the Bootstrap instance by performing
     * the bootstrap sampling.
     *
     * @param numBootstrapSamples the number of bootstrap samples to generate
     * @param estimator           a function of the data
     * @param saveBootstrapSamples   indicates that the statistics and data of each bootstrap generated should be saved
     */
    fun generateSamples(
        numBootstrapSamples: Int,
        estimator: MVBSEstimatorIfc,
        saveBootstrapSamples: Boolean = false
    ) {
        require(numBootstrapSamples > 1) { "The number of bootstrap samples must be greater than 1" }
        this.numBootstrapSamples = numBootstrapSamples
        myAcrossBSStat.reset()
        myBSEstimates.clear()
        for (s in myBSArrayList) {
            s.clearData()
        }
        myBSArrayList.clear()
        originalDataEstimate = estimator.estimate(myOriginalData)
        for (i in 0 until numBootstrapSamples) {
            val sample: DoubleArray = myOriginalPop.sample(myOriginalPop.size())
            val x = estimator.estimate(sample)
            myAcrossBSStat.collect(x)
            myBSEstimates.add(x)
            if (saveBootstrapSamples) {
                val das = DoubleArraySaver()
                das.save(sample)
                myBSArrayList.add(das)
            }
        }
    }

    /**
     *
     * If the save bootstrap data option was not turned on during the sampling then the list returned is empty.
     *
     * @return a list of size getNumBootstrapSamples() holding a copy of the data from
     * every bootstrap generate
     */
    val dataForEachBootstrapSample: List<DoubleArray>
        get() {
            val list: MutableList<DoubleArray> = ArrayList()
            for (s in myBSArrayList) {
                list.add(s.savedData())
            }
            return list
        }

    /** Creates a random variable to represent the data in each bootstrap sample for which
     * the data was saved.
     *
     * @param useCRN if true the stream for every random variable is the same across the
     * bootstraps to facilitate common random number generation (CRN). If false
     * different streams are used for each created random variable
     * @return a list of the random variables
     */
    fun empiricalRVForEachBootstrapSample(useCRN: Boolean = true): List<RVariableIfc> {
        val list: MutableList<RVariableIfc> = ArrayList()
        var rnStream: RNStreamIfc? = null
        if (useCRN) {
            rnStream = KSLRandom.nextRNStream()
        }
        for (s in myBSArrayList) {
            val data: DoubleArray = s.savedData()
            if (data.isNotEmpty()) {
                if (useCRN) {
                    list.add(EmpiricalRV(data, rnStream!!))
                } else {
                    list.add(EmpiricalRV(data))
                }
            }
        }
        return list
    }

    /**
     *
     * @param b the bootstrap generate number, b = 1, 2, ... to getNumBootstrapSamples()
     * @return the generated values for the bth bootstrap, if no samples are saved then
     * the array returned is of zero length
     */
    fun dataForBootstrapSample(b: Int): DoubleArray {
        if (myBSArrayList.isEmpty()) {
            return DoubleArray(0)
        }
        require((b < 0) || (b < myBSArrayList.size)) { "The supplied index was out of range" }
        return myBSArrayList[b].savedData()
    }

    /** If the bootstrap samples were saved, this returns the
     * generated averages for each of the samples
     *
     * @return an array of the bootstrap generate averages, will be zero length if
     * no bootstrap samples were saved
     */
    val bootstrapSampleAverages: DoubleArray
        get() {
            val avg = DoubleArray(myBSArrayList.size)
            for ((i, sda) in myBSArrayList.withIndex()) {
                avg[i] = sda.savedData().statistics().average
            }
            return avg
        }

    /** If the bootstrap samples were saved, this returns the
     * generated variance for each of the samples
     *
     * @return an array of the bootstrap generated variances, will be zero length if
     * no bootstrap samples were saved
     */
    val bootstrapSampleVariances: DoubleArray
        get() {
            val v = DoubleArray(myBSArrayList.size)
            for ((i, sda) in myBSArrayList.withIndex()) {
                v[i] = sda.savedData().statistics().variance
            }
            return v
        }
}