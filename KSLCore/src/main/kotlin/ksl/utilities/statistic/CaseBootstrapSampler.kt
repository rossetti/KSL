package ksl.utilities.statistic

import ksl.utilities.DoubleArraySaver
import ksl.utilities.random.rng.RNStreamChangeIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.EmpiricalRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistics
import ksl.utilities.transpose

/**
 *  Given some data, produce multiple estimated statistics
 *  from the data and store the estimated quantities in
 *  the returned array. It is up to the user to interpret
 *  the array values appropriately.
 *
 *  The data is assumed to be from some population represented
 *  by a set of cases (e.g. items, objects, elements, rows, exemplars, etc.).
 *  Each case is assumed to be identified with a unique integer.
 */
interface CaseBootEstimatorIfc {

    /**
     * The name to associate with each dimension of the
     * array that is returned by estimate(). The names
     * should be unique. The order of the list of names should
     * match the order of elements in the returned array. This
     * list is used to label the elements that are estimated.
     */
    val names: List<String>

    /**
     *  The estimates from the estimator based on the original (not resampled)
     *  data.
     */
    val originalEstimates: DoubleArray

    /**
     *  The set of case identifiers. This set must hold unique
     *  integers that serve as the sampling population. Elements (cases)
     *  are sampled with replacement from this set to specify
     *  the data that will be used in the estimation process.
     */
    val caseIdentifiers: LinkedHashSet<Int>

    /**
     *  The [caseIndices] array contains the case identifiers that
     *  should be used to select the data on which the estimation
     *  process should be executed. The function produces an
     *  array of estimates during the estimation process, which
     *  are associated with the labels in the names list. The
     *  case indices array may have repeated case identifiers
     *  due to sampling with replacement.
     */
    fun estimate(caseIndices: IntArray): DoubleArray
}

/**
 *  This class facilitates bootstrap sampling.  The [estimator] provides the mechanism
 *  for estimating statistical quantities from the original data. From the
 *  data, it can produce 1 or more estimated quantities. Bootstrap estimates
 *  are computed on the observed estimates from each bootstrap sample.
 *  The specified stream controls the bootstrap sampling process.
 */
open class CaseBootstrapSampler(
    val estimator: CaseBootEstimatorIfc,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RNStreamControlIfc, RNStreamChangeIfc {

    init {
        require(estimator.names.isNotEmpty()) { "The estimator has no defined names!" }
        require(estimator.originalEstimates.isNotEmpty()) { "The estimator provided no original estimates" }
    }

    /**
     * @return the estimate from the supplied CaseBootEstimatorIfc based on the original data
     */
    val originalDataEstimates = estimator.originalEstimates.copyOf()

    /**
     *  This represents the population to repeatedly sample from to form
     *  the bootstrap samples for the estimation process.
     */
    protected val myOriginalPopulation = estimator.caseIdentifiers.toIntArray()
    protected val myIndices = IntArray(myOriginalPopulation.size)

    // collects statistics along each dimension of the multi-variate estimates from the bootstrap samples
    protected val myAcrossBSStat = MVStatistic(estimator.names)

    // if requested holds the bootstrap samples
    protected val myBSArrayList = mutableListOf<DoubleArraySaver>()

    /** Holds the estimated values (for each dimension) from the bootstrap samples.
     * When the MVEstimator is applied to each bootstrap sample, it results in an array of estimates
     * from the sample. This list holds those arrays. It is cleared whenever new
     * samples are generated and then filled during the bootstrapping process.
     */
    protected val myBSEstimates = mutableListOf<DoubleArray>()

    /**
     *  Returns an 2-D array representation of the estimates from
     *  the bootstrapping process. The rows of the array are the
     *  multi-variate estimates from each bootstrap sample. The columns
     *  of the array represent the bootstrap estimates for each dimension
     *  across all the bootstrap samples.
     */
    val bootStrapData: Array<DoubleArray>
        get() = myBSEstimates.toTypedArray()

    override var rnStream: RNStreamIfc = stream

    /**
     * Tells the stream to start producing antithetic variates
     *
     */
    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(b) {
            rnStream.advanceToNextSubStreamOption = b
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(b) {
            rnStream.resetStartStreamOption = b
        }

    /**
     * The resetStartStream method will position the RNG at the beginning of its
     * stream. This is the same location in the stream as assigned when the RNG
     * was created and initialized.
     */
    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    /**
     * Resets the position of the RNG at the start of the current sub-stream
     */
    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    /**
     * Positions the RNG at the beginning of its next sub-stream
     */
    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

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
     * @param saveBootstrapSamples   indicates that the statistics and data of each bootstrap generated should be saved
     */
    fun bootStrapEstimates(
        numBootstrapSamples: Int,
        saveBootstrapSamples: Boolean = false
    ): List<BootstrapEstimate> {
        require(numBootstrapSamples > 1) { "The number of bootstrap samples must be greater than 1" }
        myAcrossBSStat.reset()
        myBSEstimates.clear()
        for (s in myBSArrayList) {
            s.clearData()
        }
        myBSArrayList.clear()
        for (i in 0 until numBootstrapSamples) {
            val caseIndices = sampleCases()
            val x = estimator.estimate(caseIndices)
            if (x.size == estimator.names.size) {
                myAcrossBSStat.collect(x)
                myBSEstimates.add(x)
                if (saveBootstrapSamples) {
                    val das = DoubleArraySaver()
                    das.save(caseIndices)
                    myBSArrayList.add(das)
                }
                innerBoot(x, caseIndices)
            }
        }
        return makeBootStrapEstimates()
    }

    private fun sampleCases(): IntArray {
        for (i in myOriginalPopulation.indices) {
            val index = rnStream.randInt(0, myOriginalPopulation.size - 1)
            myIndices[i] = myOriginalPopulation[index]
        }
        return myIndices
    }

    /**
     *  Can be used by subclasses to implement logic that occurs within
     *  the boot sampling loop. The function is executed at the end of the
     *  main boot sampling loop. The parameter, [estimate] is the estimated
     *  quantities from the current bootstrap sample, [bSample]. For example,
     *  this function could be used to bootstrap on the bootstrap sample.
     */
    protected fun innerBoot(estimate: DoubleArray, bSample: IntArray) {

    }

    /**
     *  The returned list contains the bootstrap estimates for
     *  each of the dimensions. From these elements the
     *  bootstrap confidence intervals and other statistical analysis
     *  can be performed.
     */
    protected fun makeBootStrapEstimates(): List<BootstrapEstimate> {
        val list = mutableListOf<BootstrapEstimate>()
        // transpose the collected data, each row represents a dimension and the
        // row contents are the bootstrap estimates for the dimension
        val estimates = bootStrapData.transpose()
        // now process the rows
        for ((i, estimatesArray) in estimates.withIndex()) {
            // make the bootstrap estimates
            val originalEstimate = originalDataEstimates[i]
            val be =
                BootstrapEstimate(estimator.names[i], estimator.caseIdentifiers.size, originalEstimate, estimatesArray)
            list.add(be)
        }
        return list
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