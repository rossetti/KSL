package ksl.utilities.statistic

import ksl.utilities.KSLArrays
import ksl.utilities.concatenateTo1DArray
import ksl.utilities.isRectangular
import ksl.utilities.random.rng.RNStreamChangeIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.transpose
import org.hipparchus.stat.regression.OLSMultipleLinearRegression

/**
 *  Given some data, produce multiple estimated statistics
 *  from the data and stores the estimated quantities in
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
     *  The estimates from the estimator based on the original (not resampled) data.
     */
    val originalEstimates: DoubleArray

    /**
     *  The set of case identifiers. This set must hold unique
     *  integers that serve as the sampling population. Elements (cases)
     *  are sampled with replacement from this set to specify
     *  the data that will be used in the estimation process.
     */
    val caseIdentifiers: List<Int>

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

fun interface MatrixEstimatorIfc {
    /**
     *  This function should compute the estimators from the supplied matrix
     *  and return the estimates in the array
     */
    fun estimate(matrix: Array<DoubleArray>): DoubleArray
}

class MatrixBootEstimator(
    private val matrix: Array<DoubleArray>,
    private val matrixEstimator: MatrixEstimatorIfc,
    estimatorNames: List<String> = emptyList()
) : CaseBootEstimatorIfc {

    override val originalEstimates: DoubleArray = matrixEstimator.estimate(matrix)
    override val names: List<String>

    init {
        require(matrix.size > 1) { "There must be at least 2 rows in the matrix" }
        require(matrix.isRectangular()) { "The matrix must be rectangular" }
        names = if (estimatorNames.isEmpty()) {
            List(originalEstimates.size) { "b${it}" }
        } else {
            require(estimatorNames.size == originalEstimates.size) { "There must be a name for each estimator" }
            require(estimatorNames.size == estimatorNames.toSet().size) { "The supplied names were not unique!" }
            estimatorNames.toMutableList()
        }
    }

    override val caseIdentifiers: List<Int> = List(matrix.size) { it }

    override fun estimate(caseIndices: IntArray): DoubleArray {
        // select the rows of the matrix from the supplied indices
        val m = Array(matrix.size) { matrix[caseIndices[it]] }
        return matrixEstimator.estimate(m)
    }
}

object OLSBootEstimator : MatrixEstimatorIfc {

    val regression: OLSMultipleLinearRegression = OLSMultipleLinearRegression()

    override fun estimate(matrix: Array<DoubleArray>): DoubleArray {
        require(matrix.size > 1) { "There must be at least 2 rows in the matrix" }
        val nObs = matrix.size
        val nVars = KSLArrays.numColumns(matrix) - 1
        require(nObs > nVars){"The number of observations must be greater than the number of estimated parameters"}
        val data = matrix.concatenateTo1DArray()
        regression.newSampleData(data, nObs, nVars)
        return regression.estimateRegressionParameters()
    }

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
    val originalDataEstimate: DoubleArray = estimator.originalEstimates.copyOf()

    /**
     *  This represents the population to repeatedly sample from to form
     *  the bootstrap samples for the estimation process.
     */
    protected val myOriginalPopulation: IntArray = estimator.caseIdentifiers.toIntArray()

    /**
     *  An intermediate array to hold the sampled case indices from the original
     *  population of cases.
     */
    protected val mySample: IntArray = IntArray(myOriginalPopulation.size)

    // collects statistics along each dimension of the multi-variate estimates from the bootstrap samples
    protected val myAcrossBSStat: MVStatistic = MVStatistic(estimator.names)

    // if requested holds the bootstrap samples
    protected val myBSArrayList: MutableList<IntArray> = mutableListOf<IntArray>()

    /** Holds the estimated values (for each dimension) from the bootstrap samples.
     * When the MVEstimator is applied to each bootstrap sample, it results in an array of estimates
     * from the sample. This list holds those arrays. It is cleared whenever new
     * samples are generated and then filled during the bootstrapping process.
     */
    protected val myBSEstimates: MutableList<DoubleArray> = mutableListOf<DoubleArray>()

    /**
     *  Tabulates for each bootstrap sample, the frequency of the cases
     *  selected within the sample.
     */
    protected val myCaseFrequencies : MutableList <IntegerFrequency> = mutableListOf<IntegerFrequency>()

    /**
     *  A list holding the observed frequencies of the cases within each
     *  bootstrap sample.
     */
    val caseFrequencies : List<IntegerFrequency>
        get() = myCaseFrequencies

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
        myBSArrayList.clear()
        myCaseFrequencies.clear()
        for (i in 0 until numBootstrapSamples) {
            val caseIndices = sampleCases()
            val x = estimator.estimate(caseIndices)
            if (x.size == estimator.names.size) {
                myAcrossBSStat.collect(x)
                myBSEstimates.add(x)
                myCaseFrequencies.add(IntegerFrequency(caseIndices))
                if (saveBootstrapSamples) {
                    myBSArrayList.add(caseIndices.copyOf())
                }
                innerBoot(x, caseIndices)
            }
        }
        return makeBootStrapEstimates()
    }

    fun sampleCases(): IntArray {
        for (i in myOriginalPopulation.indices) {
            val index = rnStream.randInt(0, myOriginalPopulation.size - 1)
            mySample[i] = myOriginalPopulation[index]
        }
        return mySample
    }

    /**
     *  Can be used by subclasses to implement logic that occurs within
     *  the boot sampling loop. The function is executed at the end of the
     *  main boot sampling loop. The parameter, [estimate] is the estimated
     *  quantities from the current bootstrap sample, [bSample]. For example,
     *  this function could be used to bootstrap on the bootstrap sample.
     */
    @Suppress("UNUSED_PARAMETER")
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
            val originalEstimate = originalDataEstimate[i]
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
     * @return a list of size getNumBootstrapSamples() holding a copy of the case indices from
     * every bootstrap sample
     */
    val caseIndicesForEachBootstrapSample: List<IntArray>
        get() = myBSArrayList

    /**
     *
     * @param b the bootstrap generate number, b = 1, 2, ... to getNumBootstrapSamples()
     * @return the generated case indices for the bth bootstrap, if no samples are saved then
     * the array returned is of zero length
     */
    fun caseIndicesForBootstrapSample(b: Int): IntArray {
        if (myBSArrayList.isEmpty()) {
            return IntArray(0)
        }
        require((b < 0) || (b < myBSArrayList.size)) { "The supplied index was out of range" }
        return myBSArrayList[b]
    }
}