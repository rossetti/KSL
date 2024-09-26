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
import kotlin.math.pow


interface BootstrapEstimateIfc {

    var label: String?

    /**
     *  A name for the estimate
     */
    val name: String

    /**
     * the default confidence interval level
     */
    val defaultCILevel: Double

    /**
     *
     * @return a copy of the original data
     */
//    val originalData: DoubleArray

    /**
     *  The sample size of the original data set
     */
    val originalDataSampleSize: Int

    /**
     * @return the estimate from the supplied EstimatorIfc based on the original data
     */
    val originalDataEstimate: Double

    /**
     *  The estimator involved in the bootstrapping
     */
//    val estimator: BSEstimatorIfc

    /**
     * @return the observations of the estimator for each bootstrap generated, may be zero length if
     * no samples have been generated
     */
    val bootstrapEstimates: DoubleArray

    /**
     *  The number of bootstrap samples that were used to produce the bootstrap estimate
     */
    val numberOfBootstraps: Int
        get() = bootstrapEstimates.size

    /**
     * Each element is the bootstrap estimate for sample i minus originalDataEstimate
     *
     * @return the array of bootstrap differences
     */
    val bootstrapDifferences: DoubleArray
        get() = bootstrapEstimates.subtractConstant(originalDataEstimate)

    /**
     * @return a Statistic observed across the estimates from the bootstrap samples
     */
    val acrossBootstrapStatistics: StatisticIfc

    /**
     * Each element is the bootstrap estimate for sample i minus getOriginalDataEstimate()
     * divided by getBootstrapStdErrEstimate()
     *
     * @return the array of bootstrap differences
     */
    val standardizedBootstrapDifferences: DoubleArray
        get() = bootstrapDifferences.divideConstant(bootstrapStdErrEstimate)

    /**
     * @return the generated average of the estimates from the bootstrap samples
     */
    val acrossBootstrapAverage: Double
        get() = acrossBootstrapStatistics.average

    /**
     * This is acrossBootstrapAverage - originalDataEstimate
     *
     * @return an estimate the bias based on bootstrapping
     */
    val bootstrapBiasEstimate: Double
        get() = acrossBootstrapAverage - originalDataEstimate

    /**
     *  An estimate of the mean squared error of the estimator based on bootstrapping.
     *  This is the bootstrap bias squared plus the bootstrap estimate of the variance of the estimator
     */
    val bootstrapMSEEstimate: Double
        get() = bootstrapBiasEstimate*bootstrapBiasEstimate + acrossBootstrapStatistics.variance

    /**
     * This is the standard deviation of the across bootstrap observations of the estimator
     * for each bootstrap generated
     *
     * @return the standard error of the estimate based on bootstrapping
     */
    val bootstrapStdErrEstimate: Double
        get() = acrossBootstrapStatistics.standardDeviation
//        get() = acrossBootstrapStatistics.standardError  fixed 9/19/2024 MDR

    /**
     * Gets the standard normal based bootstrap confidence interval. Not recommended.
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    fun stdNormalBootstrapCI(level: Double = defaultCILevel): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val alpha = 1.0 - level
        val z: Double = Normal.stdNormalInvCDF(1.0 - (alpha / 2.0))
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
    fun basicBootstrapCI(level: Double = defaultCILevel): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val a = 1.0 - level
        val ad2 = a / 2.0
        val bse = bootstrapEstimates
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
    fun percentileBootstrapCI(level: Double = defaultCILevel): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val a = 1.0 - level
        val ad2 = a / 2.0
        val bse = bootstrapEstimates
        val llq: Double = Statistic.percentile(bse, ad2)
        val ulq: Double = Statistic.percentile(bse, 1.0 - ad2)
        return Interval(llq, ulq)
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.appendLine("------------------------------------------------------")
        sb.appendLine("Bootstrap statistical results:")
        if (label != null) {
            sb.appendLine("label = $label")
        }
        sb.appendLine("------------------------------------------------------")
        sb.appendLine("statistic name = $name")
        sb.appendLine("number of bootstrap samples = $numberOfBootstraps")
        sb.appendLine("size of original sample = $originalDataSampleSize")
        sb.appendLine("original estimate = $originalDataEstimate")
        sb.appendLine("bias estimate = $bootstrapBiasEstimate")
        sb.appendLine("across bootstrap average = $acrossBootstrapAverage")
        sb.appendLine("bootstrap std. err. estimate = $bootstrapStdErrEstimate")
        sb.appendLine("default c.i. level = $defaultCILevel")
        sb.appendLine("norm c.i. = ${stdNormalBootstrapCI()}")
        sb.appendLine("basic c.i. = ${basicBootstrapCI()}")
        sb.appendLine("percentile c.i. = ${percentileBootstrapCI()}")
        return sb.toString()
    }
}

open class BootstrapEstimate(
    final override val name: String,
    final override val originalDataSampleSize: Int,
    final override val originalDataEstimate: Double,
    final override val bootstrapEstimates: DoubleArray
) : BootstrapEstimateIfc {

    /**
     * @return a Statistic observed across the estimates from the bootstrap samples
     */
    override val acrossBootstrapStatistics: StatisticIfc = Statistic(bootstrapEstimates)

    override var label: String? = null

    /**
     * the default confidence interval level
     */
    override var defaultCILevel: Double = 0.95
        set(level) {
            require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
            field = level
        }

    override fun toString(): String {
        return super.asString()
    }
}

/**
 * A class to do statistical bootstrapping.  The calculations occur via the method generateSamples().
 * Until generateSamples() is called the results are meaningless.
 *
 * It is possible to save the individual
 * bootstrap samples from which the bootstrap samples can be retrieved. Recognize that
 * this could be a lot of data.  The class implements four classic bootstrap confidence
 * intervals normal, basic, percentile, and BCa.  To estimate the quantiles it uses algorithm 8 from
 * Hyndman, R. J. and Fan, Y. (1996) Sample quantiles in statistical packages,
 * American Statistician 50, 361–365 as the default.  This can be changed by the user.
 *
 * @param originalData the data to sample from to form the bootstraps
 * @param estimator    a function to be applied to the data
 * @param stream the random number stream for forming the bootstraps
 * @param name a name for the bootstrap statistics
 */
open class Bootstrap(
    originalData: DoubleArray,
    val estimator: BSEstimatorIfc = BSEstimatorIfc.Average(),
    stream: RNStreamIfc = KSLRandom.nextRNStream(),
    name: String? = null
) : IdentityIfc by Identity(name), RNStreamControlIfc, RNStreamChangeIfc, BootstrapEstimateIfc {

    init {
        require(originalData.size > 1) { "The supplied bootstrap generate had only 1 data point" }
    }

    protected val myOriginalData: DoubleArray = originalData.copyOf()
    protected val myOriginalPop: DPopulation = DPopulation(originalData, stream)
    protected val myAcrossBSStat: Statistic = Statistic("Across Bootstrap Statistics")
    protected val myBSArrayList = mutableListOf<DoubleArraySaver>()
    protected val myOriginalPopStat: Statistic = Statistic("Original Pop Statistics", originalData)
    protected val myBSEstimates = DoubleArraySaver()
    protected val myStudentizedTValues = DoubleArraySaver()

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
        protected set

    /**
     *  The size of the original population of data from which to sample
     */
    override val originalDataSampleSize = originalData.size

    /**
     * the default confidence interval level
     */
    override var defaultCILevel: Double = 0.95
        set(level) {
            require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
            field = level
        }

    /** This method changes the underlying state of the Bootstrap instance by performing
     * the bootstrap sampling.
     *
     * @param numBootstrapSamples the number of bootstrap samples to generate
     * @param saveBootstrapSamples   indicates that the statistics and data of each bootstrap generate should be saved
     * @param numBootstrapTSamples    The number of bootstrap samples to use if using the studentized bootstrap-t
     *   confidence interval method
     */
    fun generateSamples(
        numBootstrapSamples: Int,
        saveBootstrapSamples: Boolean = false,
        numBootstrapTSamples: Int = 0
    ) {
        require(numBootstrapSamples > 1) { "The number of bootstrap samples must be greater than 1" }
        if (numBootstrapTSamples > 0) {
            require(numBootstrapTSamples > 1) { "The number of bootstrap-t samples must be greater than 1" }
        }
        this.numBootstrapSamples = numBootstrapSamples
        myAcrossBSStat.reset()
        myBSEstimates.clearData()
        myStudentizedTValues.clearData()
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
            if (numBootstrapTSamples > 1) {
                bootstrapTSampling(numBootstrapTSamples, x, sample)
            }
            innerBoot(x, sample)
        }
    }

    private fun bootstrapTSampling(numBootstrapTSamples: Int, estimate: Double, bSample: DoubleArray) {
        // need to estimate the standard error of the bootstrap sample
        // if the estimator is the average this can be computed directly
        val se = if (estimator is BSEstimatorIfc.Average) {
            // compute directly from bootstrap sample
            //bSample.statistics().standardError fixed 9/19/2024 MDR
            bSample.statistics().standardDeviation
        } else {
            // compute se from additional bootstrapping process
            val bs = Bootstrap(bSample, estimator)
            bs.generateSamples(numBootstrapTSamples)
            bs.bootstrapStdErrEstimate
        }
        // compute the studentized T-Value
        val tValue = (estimate - originalDataEstimate) / se
        myStudentizedTValues.save(tValue)
    }

    /**
     *  Can be used by subclasses to implement logic that occurs within
     *  the boot sampling loop. The function is executed at the end of the
     *  main boot sampling loop. The parameter, [estimate] is the estimated
     *  quantity from the current bootstrap sample, [bSample]. For example,
     *  this function could be used to bootstrap on the bootstrap sample.
     */
    @Suppress("UNUSED_PARAMETER")
    protected fun innerBoot(estimate: Double, bSample: DoubleArray) {

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
            for (dsa in myBSArrayList) {
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
     * @param b the bootstrap sample number, b = 1, 2, ... to getNumBootstrapSamples()
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

    /** This is the statistics of the bootstrap replicates.
     *
     * @return a Statistic observed across the estimates from the bootstrap samples
     */
    override val acrossBootstrapStatistics: StatisticIfc
        get() = myAcrossBSStat

    /**  These are the bootstrap replicates.
     *
     * @return the observations of the estimator for each bootstrap generated, may be zero length if
     * no samples have been generated
     */
    override val bootstrapEstimates: DoubleArray
        get() = myBSEstimates.savedData()

    /**
     * the average for the original data
     */
    val originalDataAverage: Double
        get() = myOriginalPopStat.average

    /**
     *  For the so called, BCa, interval, the approach requires a bias
     *  correction factor which in essence measures the median bias of the
     *  bootstrap replicates for the estimated quantity. This function
     *  computes the bias correction factor based on the bootstrap estimates
     *  and the original estimated quantity.
     */
    fun biasCorrectionFactor(): Double {
        return Companion.biasCorrectionFactor(bootstrapEstimates, originalDataEstimate)
    }

    /**
     *  For the so called, BCa, interval, the approach requires an acceleration factor.
     *  The acceleration factor measures the rate of change of the standard error
     *  of the estimator with respect to the target parameter on a normalized scale.
     *  This function computes the acceleration factor based on the bootstrap estimates
     *  and the original estimated quantity using jackknifing.
     */
    fun accelerationFactor(): Double {
        return Companion.accelerationFactor(originalData, estimator)
    }

    /**
     * The BCa bootstrap confidence interval which accounts for bias correction
     * and adjusted for acceleration.
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    fun bcaBootstrapCI(level: Double = defaultCILevel): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val a = 1.0 - level
        val ad2 = a / 2.0
        val z0 = biasCorrectionFactor()
        val ac = accelerationFactor()
        val z1ad2: Double = Normal.stdNormalInvCDF(1.0 - ad2)
        val zad2 = Normal.stdNormalInvCDF(ad2)
        val alpha1 = Normal.stdNormalCDF(z0 + (z0 + zad2) / (1.0 - ac * (z0 + zad2)))
        val alpha2 = Normal.stdNormalCDF(z0 + (z0 + z1ad2) / (1.0 - ac * (z0 + z1ad2)))
        val bse = bootstrapEstimates
        val llq: Double = Statistic.percentile(bse, alpha1)
        val ulq: Double = Statistic.percentile(bse, alpha2)
        return Interval(llq, ulq)
    }

    /**
     *  This is the bootstrap-t or sometimes called percentile-t
     *  confidence interval.  It is formed by capturing a t-type statistic
     *  which standardizes the individual bootstrap estimates.
     *  This confidence interval is only available if the parameter (numBootstrapTSamples)
     *  in the generateSamples() function is greater than 1; otherwise,
     *  the returned interval is (-infinity, +infinity).
     *
     * @param level the confidence level, must be between 0 and 1
     * @return the confidence interval
     */
    fun bootstrapTCI(level: Double = defaultCILevel): Interval {
        require((level <= 0.0) || (level < 1.0)) { "Confidence Level must be (0,1)" }
        val tValues = myStudentizedTValues.savedData()
        if (tValues.isEmpty()) {
            return Interval()
        }
        val a = 1.0 - level
        val ad2 = a / 2.0
        val t1ma2: Double = Statistic.percentile(tValues, 1.0 - ad2)
        val ta2: Double = Statistic.percentile(tValues, ad2)
        val ll = originalDataEstimate - t1ma2 * bootstrapStdErrEstimate
        val ul = originalDataEstimate - ta2 * bootstrapStdErrEstimate
        return Interval(ll, ul)
    }

    override fun toString(): String {
        val sb = StringBuilder(asString())
        sb.appendLine("BCa c.i. = ${bcaBootstrapCI()}")
        val btci = bcaBootstrapCI()
        if (btci.lowerLimit.isFinite() && btci.upperLimit.isFinite()){
            sb.appendLine("bootstrap-t c.i. = $btci")
        }
        sb.appendLine("------------------------------------------------------")
        return sb.toString()
    }

    companion object {

        /**
         * @param name         the name of bootstrap instance
         * @param sampleSize the size of the original sample, must be greater than 1
         * @param estimator    a function to be applied to the data
         * @param sampler something to generate the original sample of the provided size
         * @param stream the random number stream for forming the bootstraps
         * @return an instance of Bootstrap based on the sample
         */
        fun create(
            sampleSize: Int,
            sampler: SampleIfc,
            estimator: BSEstimatorIfc,
            stream: RNStreamIfc = KSLRandom.nextRNStream(),
            name: String? = null
        ): Bootstrap {
            require(sampleSize > 1) { "The sample size must be greater than 1" }
            return Bootstrap(sampler.sample(sampleSize), estimator, stream, name)
        }

        /**
         *  For the so called, BCa, interval, the approach requires a bias
         *  correction factor which in essence measures the median bias of the
         *  bootstrap replicates for the estimated quantity. This function
         *  computes the bias correction factor based on the bootstrap estimates
         *  and the original estimated quantity.
         *  @param bootstrapEstimates the bootstrap replicates from the bootstrapping process
         *  @param originalDataEstimate the original estimate from the original data using the estimator
         */
        fun biasCorrectionFactor(bootstrapEstimates: DoubleArray, originalDataEstimate: Double): Double {
            val s = Statistic()
            for (estimate in bootstrapEstimates) {
                s.collect(estimate < originalDataEstimate)
            }
            val p = s.average
            return Normal.stdNormalInvCDF(p)
        }

        /**
         *  For the so called, BCa, interval, the approach requires an acceleration factor.
         *  The acceleration factor measures the rate of change of the standard error
         *  of the estimator with respect to the target parameter on a normalized scale.
         *  This function computes the acceleration factor based on the bootstrap estimates
         *  and the original estimated quantity using jackknifing.
         *  @param originalData the original data used in the bootstrapping process
         *  @param estimator the estimator used in the bootstrapping process
         */
        fun accelerationFactor(originalData: DoubleArray, estimator: BSEstimatorIfc): Double {
            val jackKnifeEstimator = JackKnifeEstimator(originalData, estimator)
            val je = jackKnifeEstimator.jackKnifeEstimate
            val jr = jackKnifeEstimator.jackKnifeReplicates
            var nom = 0.0
            var dnom = 0.0
            for (x in jr) {
                val s2 = (je - x) * (je - x)
                nom = nom + s2 * (je - x)
                dnom = dnom + s2.pow(1.5)
            }
            return nom / (6.0 * dnom)
        }


    }
}