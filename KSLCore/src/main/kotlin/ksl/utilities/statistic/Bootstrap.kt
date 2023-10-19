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
package ksl.utilities.statistic

import ksl.utilities.*
import ksl.utilities.distributions.Normal
import ksl.utilities.random.SampleIfc
import ksl.utilities.random.rng.*
import ksl.utilities.random.robj.DPopulation
import ksl.utilities.random.rvariable.EmpiricalRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariableIfc


interface BootstrapEstimateIfc {
    /**
     * @return the estimate from the supplied EstimatorIfc based on the original data
     */
    var originalDataEstimate: Double

    /**
     * @return the observations of the estimator for each bootstrap generated, may be zero length if
     * no samples have been generated
     */
    val bootstrapEstimates: DoubleArray

    /**
     * Each element is the bootstrap estimate for sample i minus originalDataEstimate
     *
     * @return the array of bootstrap differences
     */
    val bootstrapDifferences: DoubleArray

    /**
     * Each element is the bootstrap estimate for sample i minus getOriginalDataEstimate()
     * divided by getBootstrapStdErrEstimate()
     *
     * @return the array of bootstrap differences
     */
    val standardizedBootstrapDifferences: DoubleArray

    /**
     * This is acrossBootstrapAverage - originalDataEstimate
     *
     * @return an estimate the bias based on bootstrapping
     */
    val bootstrapBiasEstimate: Double

    /**
     * This is the standard deviation of the across bootstrap observations of the estimator
     * for each bootstrap generate
     *
     * @return the standard error of the estimate based on bootstrapping
     */
    val bootstrapStdErrEstimate: Double

    /**
     * Gets the standard normal based bootstrap confidence interval. Not recommended.
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    fun stdNormalBootstrapCI(level: Double = defaultCILevel): Interval

    /**
     * The "basic" method, but with no bias correction. This
     * is the so-called centered percentile method (2θ − Bu , 2θ − Bl )
     * where θ is the bootstrap estimator and Bu is the 1 - alpha/2 percentile
     * and Bl is the lower (alpha/2) percentile, where level = 1-alpha of
     * the bootstrap replicates.
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    fun basicBootstrapCI(level: Double = defaultCILevel): Interval

    /**
     * The "percentile" method, but with no bias correction. This
     * is the percentile method (Bl , Bu )
     * where Bu is the 1 - alpha/2 percentile
     * and Bl is the lower (alpha/2) percentile, where level = 1-alpha of the
     * bootstrap replicates
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    fun percentileBootstrapCI(level: Double = defaultCILevel): Interval
}

/**
 * A class to do statistical bootstrapping.  The calculations occur via the method generateSamples().
 * Until generateSamples() is called the results are meaningless.
 *
 * It is possible to save the individual
 * bootstrap samples from which the bootstrap samples can be retrieved. Recognize that
 * this could be a lot of data.  The class implements three classic bootstrap confidence
 * intervals normal, basic, and percentile.  To estimate the quantiles it uses algorithm 8 from
 * Hyndman, R. J. and Fan, Y. (1996) Sample quantiles in statistical packages,
 * American Statistician 50, 361–365 as the default.  This can be changed by the user.
 */
class Bootstrap(originalData: DoubleArray, name: String? = null) : IdentityIfc by Identity(name), RNStreamControlIfc, RNStreamChangeIfc,
    BootstrapEstimateIfc {

    init {
        require(originalData.size > 1) { "The supplied bootstrap generate had only 1 data point" }
    }
    private val myOriginalData: DoubleArray = originalData.copyOf()
    private val myOriginalPop: DPopulation = DPopulation(myOriginalData)
    private val myAcrossBSStat: Statistic = Statistic("Across Bootstrap Statistics")
    private val myBSArrayList = mutableListOf<DoubleArraySaver>()
    private val myOriginalPopStat: Statistic = Statistic("Original Pop Statistics", myOriginalData)
    private val myBSEstimates =  DoubleArraySaver()

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

    /**
     * @return the number of requested bootstrap samples
     */
    var numBootstrapSamples = 0
        private set

    /**
     * @return the estimate from the supplied EstimatorIfc based on the original data
     */
    override var originalDataEstimate = 0.0
        private set

    /**
     * the default confidence interval level
     */
    var defaultCILevel: Double = 0.95
        set(level) {
            require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
            field = level
        }

    /** This method changes the underlying state of the Bootstrap instance by performing
     * the bootstrap sampling.
     *
     * @param numBootstrapSamples the number of bootstrap samples to generate
     * @param estimator           a function of the data
     * @param saveBootstrapSamples   indicates that the statistics and data of each bootstrap generate should be saved
     */
    fun generateSamples(
        numBootstrapSamples: Int, estimator: BSEstimatorIfc = BSEstimatorIfc.Average(),
        saveBootstrapSamples: Boolean = false
    ) {
        require(numBootstrapSamples > 1) { "The number of bootstrap samples must be greater than 1" }
        this.numBootstrapSamples = numBootstrapSamples
        myAcrossBSStat.reset()
        myBSEstimates.clearData()
        for (s in myBSArrayList) {
            s.clearData()
        }
        myBSArrayList.clear()
        originalDataEstimate = estimator.estimate(myOriginalData)
        for (i in 0 until numBootstrapSamples) {
            val sample: DoubleArray = myOriginalPop.sample(myOriginalPop.size())
            val x = estimator.estimate(sample)
            myAcrossBSStat.collect(x)
            myBSEstimates.save(x)
            if (saveBootstrapSamples) {
                val das = DoubleArraySaver()
                das.save(sample)
                myBSArrayList.add(das)
            }
        }
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
     *
     * @return a copy of the original data
     */
    val originalData: DoubleArray
        get() = myOriginalData.copyOf()

    /**
     * The list itself is unmodifiable. The
     * underlying statistic objects can be modified, but have no effect on the bootstrap generate statistics.
     * The statistical values will be changed the next time generateSamples() is executed. Users are
     * advised to copy the statistics in the list (via Statistic newInstance())
     * before executing generateSamples if persistence is required.
     *
     * If the save bootstrap data option was not turned on during the sampling then the list returned is empty.
     *
     * @return a list of size numBootstrapSamples holding statistics from every bootstrap generated
     */
    val statisticForEachBootstrapSample: List<Statistic>
        get() {
            val list = mutableListOf<Statistic>()
            for(dsa in myBSArrayList){
                list.add(Statistic(dsa.savedData()))
            }
            return list
        }

    /**
     *
     * If the save bootstrap data option was not turned on during the sampling then the list returned is empty.
     *
     * @return a list of size getNumBootstrapSamples() holding a copy of the data from
     * every bootstrap generated
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

    /**
     * @return a Statistic observed across the estimates from the bootstrap samples
     */
    val acrossBootstrapStatistics: Statistic
        get() = myAcrossBSStat.instance()

    /**
     * @return the generated average of the estimates from the bootstrap samples
     */
    val acrossBootstrapAverage: Double
        get() = myAcrossBSStat.average

    /**
     * @return the observations of the estimator for each bootstrap generated, may be zero length if
     * no samples have been generated
     */
    override val bootstrapEstimates: DoubleArray
        get() = myBSEstimates.savedData()

    /**
     * Each element is the bootstrap estimate for sample i minus originalDataEstimate
     *
     * @return the array of bootstrap differences
     */
    override val bootstrapDifferences: DoubleArray
        get() = bootstrapEstimates.subtractConstant(originalDataEstimate)

    /**
     * Each element is the bootstrap estimate for sample i minus getOriginalDataEstimate()
     * divided by getBootstrapStdErrEstimate()
     *
     * @return the array of bootstrap differences
     */
    override val standardizedBootstrapDifferences: DoubleArray
        get() = bootstrapDifferences.divideConstant(bootstrapStdErrEstimate)

    /**
     * the average for the original data
     */
    val originalDataAverage: Double
        get() = myOriginalPopStat.average

    /**
     * This is acrossBootstrapAverage - originalDataEstimate
     *
     * @return an estimate the bias based on bootstrapping
     */
    override val bootstrapBiasEstimate: Double
        get() = acrossBootstrapAverage - originalDataEstimate

    /**
     * This is the standard deviation of the across bootstrap observations of the estimator
     * for each bootstrap generate
     *
     * @return the standard error of the estimate based on bootstrapping
     */
    override val bootstrapStdErrEstimate: Double
        get() = myAcrossBSStat.standardDeviation

    /**
     * @return summary statistics for the original data
     */
    val originalDataStatistics: Statistic
        get() = myOriginalPopStat.instance()

    /**
     * Gets the standard normal based bootstrap confidence interval. Not recommended.
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    override fun stdNormalBootstrapCI(level: Double): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val alpha = 1.0 - level
        val z: Double = Normal.stdNormalInvCDF(1.0 - alpha / 2.0)
        val estimate = originalDataEstimate
        val se = bootstrapStdErrEstimate
        val ll = estimate - z * se
        val ul = estimate + z * se
        return Interval(ll, ul)
    }

    /**
     * The "basic" method, but with no bias correction. This
     * is the so-called centered percentile method (2θ − Bu , 2θ − Bl )
     * where θ is the bootstrap estimator and Bu is the 1 - alpha/2 percentile
     * and Bl is the lower (alpha/2) percentile, where level = 1-alpha of
     * the bootstrap replicates.
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    override fun basicBootstrapCI(level: Double): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val a = 1.0 - level
        val ad2 = a / 2.0
        val bse = myBSEstimates.savedData()
        val llq: Double = Statistic.percentile(bse, ad2)
        val ulq: Double = Statistic.percentile(bse, 1.0 - ad2)
        val estimate = originalDataEstimate
        val ll = 2.0 * estimate - ulq
        val ul = 2.0 * estimate - llq
        return Interval(ll, ul)
    }

    /**
     * The "percentile" method, but with no bias correction. This
     * is the percentile method (Bl , Bu )
     * where Bu is the 1 - alpha/2 percentile
     * and Bl is the lower (alpha/2) percentile, where level = 1-alpha of the
     * bootstrap replicates
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    override fun percentileBootstrapCI(level: Double): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val a = 1.0 - level
        val ad2 = a / 2.0
        val bse = myBSEstimates.savedData()
        val llq: Double = Statistic.percentile(bse, ad2)
        val ulq: Double = Statistic.percentile(bse, 1.0 - ad2)
        return Interval(llq, ulq)
    }

    override fun toString(): String {
        return asString()
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append("------------------------------------------------------")
        sb.append(System.lineSeparator())
        sb.append("Bootstrap statistical results:")
        sb.append(System.lineSeparator())
        sb.append("id = ").append(id)
        sb.append(System.lineSeparator())
        sb.append("name = ").append(name)
        sb.append(System.lineSeparator())
        sb.append("------------------------------------------------------")
        sb.append(System.lineSeparator())
        sb.append("number of bootstrap samples = ").append(numBootstrapSamples)
        sb.append(System.lineSeparator())
        sb.append("size of original sample = ").append(myOriginalPopStat.count)
        sb.append(System.lineSeparator())
        sb.append("original estimate = ").append(originalDataEstimate)
        sb.append(System.lineSeparator())
        sb.append("bias estimate = ").append(bootstrapBiasEstimate)
        sb.append(System.lineSeparator())
        sb.append("across bootstrap average = ").append(acrossBootstrapAverage)
        sb.append(System.lineSeparator())
        sb.append("std. err. estimate = ").append(bootstrapStdErrEstimate)
        sb.append(System.lineSeparator())
        sb.append("default c.i. level = ").append(defaultCILevel)
        sb.append(System.lineSeparator())
        sb.append("norm c.i. = ").append(stdNormalBootstrapCI())
        sb.append(System.lineSeparator())
        sb.append("basic c.i. = ").append(basicBootstrapCI())
        sb.append(System.lineSeparator())
        sb.append("percentile c.i. = ").append(percentileBootstrapCI())
        sb.append(System.lineSeparator())
        sb.append("------------------------------------------------------")
        return sb.toString()
    }

    companion object {

        /**
         * @param name         the name of bootstrap instance
         * @param sampleSize the size of the original sample, must be greater than 1
         * @param sampler something to generate the original sample of the provided size
         * @return an instance of Bootstrap based on the sample
         */
        fun create(sampleSize: Int, sampler: SampleIfc, name: String? = null): Bootstrap {
            require(sampleSize > 1) { "The generate size must be greater than 1" }
            return Bootstrap(sampler.sample(sampleSize), name)
        }
    }
}