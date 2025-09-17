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

package ksl.utilities.random.mcmc

import ksl.utilities.observers.Observable
import ksl.utilities.random.rng.RNStreamChangeIfc
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.MVSampleIfc
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.averages

/**
 * An implementation for a multi-variable Metropolis Hasting process. The
 * process is observable at each step
 * @param initialX the initial value to start generation process
 * @param targetFun the target function
 * @param proposalFun the proposal function
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param batchStatistics a list of BatchStatistics one for each dimension that have
 * been configured to collect batch statistics on the dimensions. Default batch statistics
 * are provided.
 */
open class MetropolisHastingsMV @JvmOverloads constructor(
    initialX: DoubleArray,
    val targetFun: FunctionMVIfc,
    val proposalFun: ProposalFunctionMVIfc,
    streamNum: Int = 0,
    val streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    batchStatistics: List<BatchStatistic> = createBatchStatistics(initialX.size)
) : MVSampleIfc, RNStreamChangeIfc, RNStreamControlIfc, Observable<MetropolisHastingsMV>() {

    init {
        require(initialX.size == targetFun.dimension)
        { "The initial array must have the same dimension as the multi-variate target and proposal functions" }
        require(targetFun.dimension == proposalFun.dimension)
        { "The multi-variate target function must have the same dimension as the multi-variate proposal function" }
        require(batchStatistics.size == initialX.size)
        { "The number of supplied batch statistics was not equal to the number of dimensions" }
    }

    override var rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)

    override val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    override val dimension: Int = initialX.size

    var initialX: DoubleArray = initialX.copyOf()
        //       get() = field.copyOf()
        set(value) {
            require(value.size == field.size) { "The supplied initial state array size must be = ${field.size}" }
            field = value.copyOf()
        }

    var isInitialized : Boolean = false
        protected set

    var isWarmedUp : Boolean = false
        protected set

    private val myAcceptanceStatistics = Statistic("Acceptance Statistics")

    val acceptanceStatistics: Statistic
        get() = myAcceptanceStatistics.instance()

    private val myBatchStatistics: List<BatchStatistic> = batchStatistics

    private val myObservationStatistics: List<Statistic> = buildList {
        for (i in initialX.indices) {
            this.add(Statistic(name = "X_" + (i + 1)))
        }
    }

    protected var currentX: DoubleArray = DoubleArray(initialX.size)

    protected var proposedY: DoubleArray = DoubleArray(initialX.size)

    protected var previousX: DoubleArray = DoubleArray(initialX.size)

    var lastAcceptanceProbability : Double = Double.NaN
        protected set

    var targetFunctionAtProposedY : Double = Double.NaN
        protected set

    var targetFunctionAtCurrentX : Double = Double.NaN
        protected set

    fun currentX(): DoubleArray = currentX.copyOf()
    fun proposedY(): DoubleArray = proposedY.copyOf()
    fun previousX(): DoubleArray = previousX.copyOf()

    /**
     *  Returns a list of batching statistics for each dimension.
     *  the observations for each dimension are batched using the default
     *  batching algorithm in class BatchStatistic.
     */
    fun batchStatistics(): List<BatchStatistic> {
        val mutableList = mutableListOf<BatchStatistic>()
        for (statistic in myBatchStatistics) {
            mutableList.add(statistic.instance())
        }
        return mutableList
    }

    /**
     * Returns a list of the statistics collected across every dimension
     * from all the observations without batching.
     */
    fun observedStatistics(): List<Statistic> {
        val mutableList = mutableListOf<Statistic>()
        for (statistic in myObservationStatistics) {
            mutableList.add(statistic.instance())
        }
        return mutableList
    }

    /**
     *  Returns the average for each dimension based on all observed
     *  values, without batching.
     */
    fun averages() : DoubleArray {
        return myObservationStatistics.averages()
    }

    /**
     * Resets the automatically collected statistics
     */
    fun resetStatistics() {
        for (s in myBatchStatistics) {
            s.reset()
        }
        for (s in myObservationStatistics) {
            s.reset()
        }
        myAcceptanceStatistics.reset()
    }

    /** Runs a warmup period and assigns the initial value of the process to the last
     * value from the warmup process.
     *
     * @param warmUpAmount the amount to warmup
     */
    fun runWarmUpPeriod(warmUpAmount: Int) {
        val x: DoubleArray = runAll(warmUpAmount)
        isWarmedUp = true
        isInitialized = false
        initialX = x
        resetStatistics()
    }

    /**  Resets statistics and sets the initial state  to the initial value or to the value
     * found via the burn in period (if the burn in period was run).
     *
     */
    fun initialize() {
        lastAcceptanceProbability = Double.NaN
        targetFunctionAtProposedY = Double.NaN
        targetFunctionAtCurrentX = Double.NaN
        isInitialized = true
        currentX = initialX
        resetStatistics()
    }

    /**  The state of the sampler is initialized before running all the steps.
     *
     * @param n  runs the process for n steps after initializing the process
     * @return the value of the process after n steps
     */
    fun runAll(n: Int): DoubleArray {
        require(n > 0) { "The number of iterations to run was less than or equal to zero." }
        initialize()
        var value = DoubleArray(initialX.size)
        for (i in 1..n) {
            value = next()
        }
        return value.copyOf()
    }

    /**
     *  Causes the process to advance through the number of [steps]
     *  and return the state associated with the last step taken. This
     *  allows the sampling to skip the number of steps between returned
     *  observations. If the sampler is not initialized, it will be initialized.
     *  If it has already been initialized, it will not be re-initialized.
     *  This is a convenience method which repeatedly calls the function
     *  next() for the specified number of [steps].
     */
    fun next(steps: Int): DoubleArray {
        require(steps >= 1) { "The number of steps to advance must be >= 1" }
        var sample: DoubleArray? = null
        for (i in 1..steps) {
            sample = next()
        }
        return sample!!
    }

    /** Moves the process one step. If the sampler is not initialized, it will be initialized.
     *  If it has already been initialized, it will not be re-initialized.
     *
     * @return the next value of the process after proposing the next state (y)
     */
    fun next(): DoubleArray {
        if (!isInitialized) {
            initialize()
        }
        previousX = currentX
        proposedY = proposalFun.generateProposedGivenCurrent(currentX)
        lastAcceptanceProbability = acceptanceFunction(currentX, proposedY)
        if (rnStream.randU01() <= lastAcceptanceProbability) {
            currentX = proposedY
            myAcceptanceStatistics.collect(1.0)
        } else {
            myAcceptanceStatistics.collect(0.0)
        }
        for (i in currentX.indices) {
            myBatchStatistics[i].collect(currentX[i])
            myObservationStatistics[i].collect(currentX[i])
        }
        notifyObservers(this)
        return currentX
    }

    /** Computes the acceptance function for each step
     *
     * @param currentX the current state
     * @param proposedY the proposed state
     * @return the evaluated acceptance function
     */
    protected fun acceptanceFunction(currentX: DoubleArray, proposedY: DoubleArray): Double {
        val fRatio = functionRatio(currentX, proposedY)
        val pRatio = proposalFun.proposalRatio(currentX, proposedY)
        val ratio = fRatio * pRatio
        return minOf(ratio, 1.0)
    }

    /**
     *
     * @param currentX the current state
     * @param proposedY the proposed state
     * @return the ratio of f(y)/f(x) for the generation step
     */
    protected fun functionRatio(currentX: DoubleArray, proposedY: DoubleArray): Double {
        val fx = targetFun.f(currentX)
        val fy = targetFun.f(proposedY)
        check(fx >= 0.0) {
            "The target function was $fx < 0 at current state ${
                currentX.joinToString(
                    prefix = "[",
                    postfix = "]"
                )
            }"
        }
        check(fy >= 0.0) {
            "The target function was $fy < 0 at proposed state ${
                proposedY.joinToString(
                    prefix = "[",
                    postfix = "]"
                )
            }"
        }
        val ratio: Double = if (fx != 0.0) {
            fy / fx
        } else {
            Double.POSITIVE_INFINITY
        }
        targetFunctionAtCurrentX = fx
        targetFunctionAtProposedY = fy
        return ratio
    }

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }

    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "The array size must be $dimension" }
        next().copyInto(array)
    }

    override fun toString(): String {
        return asString()
    }

    open fun asString(): String {
        val sb = StringBuilder("MetropolisHastings1D")
        sb.appendLine()
        sb.append("Initialized Flag = ").append(isInitialized)
        sb.appendLine()
        sb.append("Burn In Flag = ").append(isWarmedUp)
        sb.appendLine()
        sb.append("Initial X =").append(initialX.contentToString())
        sb.appendLine()
        sb.append("Current X = ").append(currentX.contentToString())
        sb.appendLine()
        sb.append("Previous X = ").append(previousX.contentToString())
        sb.appendLine()
        sb.append("Last Proposed Y= ").append(proposedY.contentToString())
        sb.appendLine()
        sb.append("Last Prob. of Acceptance = ").append(lastAcceptanceProbability)
        sb.appendLine()
        sb.append("Last f(Y) = ").append(targetFunctionAtProposedY)
        sb.appendLine()
        sb.append("Last f(X) = ").append(targetFunctionAtCurrentX)
        sb.appendLine()
        sb.append("Acceptance Statistics")
        sb.appendLine()
        sb.append(myAcceptanceStatistics.asString())
        sb.appendLine()
        for (s in myBatchStatistics) {
            sb.append(s.asString())
            sb.appendLine()
        }
        return sb.toString()
    }

    companion object {
        /**
         *
         * @param initialX the initial value to start the burn in period
         * @param warmUpPeriod the number of samples in the burn in period
         * @param targetFun the target function
         * @param proposalFun the proposal function
         */
        fun create(
            initialX: DoubleArray, warmUpPeriod: Int, targetFun: FunctionMVIfc,
            proposalFun: ProposalFunctionMVIfc
        ): MetropolisHastingsMV {
            val m = MetropolisHastingsMV(initialX, targetFun, proposalFun)
            m.runWarmUpPeriod(warmUpPeriod)
            return m
        }

        /**
         *  Can be used to create a list of BatchStatistic instances that
         *  are pre-configured based on the batch settings.
         *  @param theMinNumBatches The minimum number of batches, must be &gt;= 2
         *  @param theMinBatchSize The minimum number of observations per batch, must be &gt;= 2
         *  @param theMinNumBatchesMultiple The maximum number of batches as a multiple of the
         * minimum number of batches.
         *  @param numToCreate the number of statistics to create.
         */
        fun createBatchStatistics(
            numToCreate: Int,
            theMinNumBatches: Int = BatchStatistic.MIN_NUM_BATCHES,
            theMinBatchSize: Int = BatchStatistic.MIN_NUM_OBS_PER_BATCH,
            theMinNumBatchesMultiple: Int = BatchStatistic.MAX_BATCH_MULTIPLE
        ): List<BatchStatistic> {
            require(numToCreate >= 1) { "The number of batch statistics to create must be >= 1" }
            val list = mutableListOf<BatchStatistic>()
            for (i in 1..numToCreate) {
                list.add(BatchStatistic(theMinNumBatches, theMinBatchSize, theMinNumBatchesMultiple, theName = "X_$i"))
            }
            return list
        }
    }

}