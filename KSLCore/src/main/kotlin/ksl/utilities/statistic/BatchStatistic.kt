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

import ksl.utilities.random.rvariable.ExponentialRV

interface BatchStatisticIfc : StatisticIfc {
    /**
     * The minimum number of observations per batch
     */
    val minBatchSize: Int

    /**
     * The minimum number of batches required
     */
    val minNumBatches: Int

    /**
     * The multiple of the minimum number of batches that determines the maximum
     * number of batches e.g. if the min. number of batches is 20 and the max
     * number batches multiple is 2, then we can have at most 40 batches
     */
    val minNumBatchesMultiple: Int

    /**
     * The maximum number of batches as determined by the max num batches
     * multiple
     */
    val maxNumBatches: Int

    /**
     * the number of batches
     */
    val numBatches: Int

    /**
     * the number of times re-batching has occurred
     */
    val numRebatches: Int

    /**
     * the size of the current batch
     */
    val currentBatchSize: Int

    /**
     *
     * @return the amount of data that did not fit into a full batch
     */
    val amountLeftUnbatched: Double

    /**
     * Gets the total number of observations observed
     *
     * @return a double representing the total number of observations
     */
    val totalNumberOfObservations: Double

    /** Takes the current batch means and batches them into the specified
     * number of batches.  This does not change the current batch means
     *
     * @param numBatches the number of batches, must be greater that zero, and less than or equal to
     * the current number of batches
     * @return the array of new batch means
     */
    fun reformBatches(numBatches: Int): DoubleArray

    /**
     * Returns a copy of the StatisticIfc that is tabulating the
     * current batch
     *
     * @return a reference to the StatisticIfc that is tabulating the
     * current batch
     */
    val currentBatchStatistic: StatisticIfc

    /**
     * Checks if the supplied value falls within getAverage() +/- getHalfWidth()
     *
     * @param mean the mean to check
     * @return true if the supplied value falls within getAverage() +/-
     * getHalfWidth()
     */
    fun checkMean(mean: Double): Boolean

    /**
     *  Returns a copy of the BatchStatistic
     */
    fun instance(): BatchStatistic

    /**
     * Returns a copy of the batch means array. Zero index is the
     * first batch mean
     *
     * @return An array holding the batch means
     */
    val batchMeans: DoubleArray
}

/**
 * This class automates the batching of observations that may be dependent. It
 * computes the batch means of the batches and reports statistics across the
 * batches. Suppose we have observations, Y(1), Y(2), Y(3), ... Y(n). This class
 * specifies the minimum number of batches, the minimum number of observations
 * per batch, and a maximum batch multiple. The defaults are 20, 16, and 2,
 * respectively. This implies that the maximum number of batches will be 40 =
 * (min number of batches times the maximum batch multiple). The class computes
 * the average of each batch, which are called the batch means.
 *
 * Once the minimum number of observations are observed, a batch is formed. As
 * more and more observations are collected, more and more batches are formed
 * until the maximum number of batches is reached. Then the batches are
 * re-batched down so that there are 20 batches (the minimum number of batches).
 * This re-batching essentially doubles the batch size and halves the number of
 * batches. In other words, each sequential pair of batches are combined into
 * one batch by averaging their batch means. The purpose of this batching
 * process is to break up correlation structure within the data.
 *
 * Confidence intervals and summary statistics can be reported across the batch
 * means under the assumption that the batch means are independent. The lag-1
 * correlation of the batch means is available as well as the Von-Neumann test
 * statistic for independence of the batch means.
 *
 * Creates a BatchStatistic with the given name For example, if
 * minNumBatches = 20 and maxNBMultiple = 2 then the maximum number of
 * batches allowed will be 40. maxNBMultiple must be 2 or more.
 *
 * @param theMinNumBatches The minimum number of batches, must be &gt;= 2
 * @param theMinBatchSize The minimum number of observations per batch, must be &gt;= 2
 * @param theMinNumBatchesMultiple The maximum number of batches as a multiple of the
 * minimum number of batches.
 * @param theName A String representing the name of the statistic
 * @param values An array of values to collect on
 */
class BatchStatistic constructor(
    theMinNumBatches: Int = MIN_NUM_BATCHES,
    theMinBatchSize: Int = MIN_NUM_OBS_PER_BATCH,
    theMinNumBatchesMultiple: Int = MAX_BATCH_MULTIPLE,
    theName: String? = null,
    values: DoubleArray? = null
) : AbstractStatistic(theName), BatchStatisticIfc {
    init {
        require(theMinNumBatches > 1) { "Number of batches must be >= 2" }
        require(theMinBatchSize > 1) { "Batch size must be >= 2" }
        require(theMinNumBatchesMultiple > 1) { "Maximum number of batches multiple must be >= 2" }
    }

    /**
     * The minimum number of observations per batch
     */
    override val minBatchSize: Int = theMinBatchSize

    /**
     * The minimum number of batches required
     */
    override val minNumBatches: Int = theMinNumBatches

    /**
     * The multiple of the minimum number of batches that determines the maximum
     * number of batches e.g. if the min. number of batches is 20 and the max
     * number batches multiple is 2, then we can have at most 40 batches
     */
    override val minNumBatchesMultiple: Int = theMinNumBatchesMultiple

    /**
     * The maximum number of batches as determined by the max num batches
     * multiple
     */
    override val maxNumBatches: Int = minNumBatches * minNumBatchesMultiple

    /**
     * holds the batch means
     */
    private val bm: DoubleArray = DoubleArray(maxNumBatches + 1)

    /**
     * the number of batches
     */
    override var numBatches = 0
        private set

    /**
     * the number of times re-batching has occurred
     */
    override var numRebatches = 0
        private set

    /**
     * the size of the current batch
     */
    override var currentBatchSize: Int = theMinBatchSize
        private set

    /**
     * collects the within batch statistics
     */
    private var myStatistic: Statistic = Statistic()

    /**
     * collects the across batch statistics
     */
    private var myBMStatistic: Statistic = Statistic(name)

    /**
     * the last observed value
     */
    private var myValue = 0.0

    /**
     * counts the total number of observations
     */
    private var myTotNumObs = 0.0 //counts total number of observations

    init {
        if (values != null){
            for(x in values){
                collect(x)
            }
        }
    }

    override var confidenceLevel: Double
        get() = super.confidenceLevel
        set(value) {
            myBMStatistic.confidenceLevel = value
        }

    override fun reset() {
        super.reset()
        myBMStatistic.reset()
        for (i in 1..maxNumBatches) {
            bm[i] = 0.0
        }
        myStatistic.reset()
        numBatches = 0
        myTotNumObs = 0.0
        currentBatchSize = minBatchSize
    }

    /**
     * Checks if the supplied value falls within getAverage() +/- getHalfWidth()
     *
     * @param mean the mean to check
     * @return true if the supplied value falls within getAverage() +/-
     * getHalfWidth()
     */
    override fun checkMean(mean: Double): Boolean {
        return myBMStatistic.checkMean(mean)
    }

    override fun collect(obs: Double) {
        myTotNumObs = myTotNumObs + 1.0
        myValue = obs
        myStatistic.collect(myValue)
        if (myStatistic.count == currentBatchSize.toDouble()) {
            collectBatch()
        }
        lastValue = obs
        notifyObservers(lastValue)
        emitter.emit(lastValue)
    }

    /**
     * Performs the collection of the batches.
     *
     *
     */
    private fun collectBatch() {
        // increment the current number of batches
        numBatches = numBatches + 1
        // record the average of the batch
        bm[numBatches] = myStatistic.average
        // collect running statistics on the batches
        myBMStatistic.collect(bm[numBatches])
        // reset the within batch statistic for next batch
        myStatistic.reset()
        // if the number of batches has reached the maximum then rebatch down to
        // min number of batches
        if (numBatches == maxNumBatches) {
            numRebatches++
            currentBatchSize = currentBatchSize * minNumBatchesMultiple
            var j = 0 // within batch counter
            var k = 0 // batch counter
            myBMStatistic.reset() // clear for collection across new batches
            // loop through all the batches
            for (i in 1..numBatches) {
                myStatistic.collect(bm[i]) // collect across batches old batches
                j++
                if (j == minNumBatchesMultiple) { // have enough for a batch
                    //collect new batch average
                    myBMStatistic.collect(myStatistic.average)
                    k++ //count the batches
                    bm[k] = myStatistic.average // save the new batch average
                    myStatistic.reset() // reset for next batch
                    j = 0
                }
            }
            numBatches = k // k should be minNumBatches
            myStatistic.reset() //reset for use with new data
        }
    }

    /** Takes the current batch means and batches them into the specified
     * number of batches.  This does not change the current batch means
     *
     * @param numBatches the number of batches, must be greater that zero, and less than or equal to
     * the current number of batches
     * @return the array of new batch means
     */
    override fun reformBatches(numBatches: Int): DoubleArray {
        require(numBatches > 0) { "Number of requested batches must be >= 1" }
        require(numBatches <= count) { "Number of requested batches must be <= the current number of batches" }
        return batchMeans(batchMeans, numBatches)
    }

    /**
     * Returns a copy of the batch means array. Zero index is the
     * first batch mean
     *
     * @return An array holding the batch means
     */
    override val batchMeans: DoubleArray
        get() {
            // only copy the actual batch means
            val nbm = DoubleArray(numBatches)
            System.arraycopy(bm, 1, nbm, 0, nbm.size)
            return nbm
//            return bm.copyOfRange(1, numBatches+1)
        }

    override val average: Double
        get() = myBMStatistic.average

    override val count: Double
        get() = myBMStatistic.count

    override val sum: Double
        get() = myBMStatistic.sum

    override val deviationSumOfSquares: Double
        get() = myBMStatistic.deviationSumOfSquares
    override val negativeCount: Double
        get() = myBMStatistic.negativeCount
    override val zeroCount: Double
        get() = myBMStatistic.zeroCount

    override val variance: Double
        get() = myBMStatistic.variance

    override val standardDeviation: Double
        get() = myBMStatistic.standardDeviation

    override fun halfWidth(level: Double): Double {
        return myBMStatistic.halfWidth(level)
    }

    override val min: Double
        get() = myBMStatistic.min

    override val max: Double
        get() = myBMStatistic.max

    override val kurtosis: Double
        get() = myBMStatistic.kurtosis

    override val skewness: Double
        get() = myBMStatistic.skewness

    override val standardError: Double
        get() = myBMStatistic.standardError

    override val lag1Correlation: Double
        get() = myBMStatistic.lag1Correlation

    override val lag1Covariance: Double
        get() = myBMStatistic.lag1Covariance

    override val vonNeumannLag1TestStatistic: Double
        get() = myBMStatistic.vonNeumannLag1TestStatistic

    override val vonNeumannLag1TestStatisticPValue: Double
        get() = myBMStatistic.vonNeumannLag1TestStatisticPValue


    override fun leadingDigitRule(multiplier: Double): Int {
        return myBMStatistic.leadingDigitRule(multiplier)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(myBMStatistic.toString())
        sb.append("Minimum batch size = ")
        sb.append(minBatchSize)
        sb.append(System.lineSeparator())
        sb.append("Minimum number of batches = ")
        sb.append(minNumBatches)
        sb.append(System.lineSeparator())
        sb.append("Maximum number of batches multiple = ")
        sb.append(minNumBatchesMultiple)
        sb.append(System.lineSeparator())
        sb.append("Maximum number of batches = ")
        sb.append(maxNumBatches)
        sb.append(System.lineSeparator())
        sb.append("Number of rebatches = ")
        sb.append(numRebatches)
        sb.append(System.lineSeparator())
        sb.append("Current batch size = ")
        sb.append(currentBatchSize)
        sb.append(System.lineSeparator())
        sb.append("Amount left unbatched = ")
        sb.append(myStatistic.count)
        sb.append(System.lineSeparator())
        sb.append("Total number observed = ")
        sb.append(myTotNumObs)
        sb.append(System.lineSeparator())
        return sb.toString()
    }

    /**
     *
     * @return the amount of data that did not fit into a full batch
     */
    override val amountLeftUnbatched: Double
        get() = myStatistic.count

    /**
     * Gets the total number of observations observed
     *
     * @return a double representing the total number of observations
     */
    override val totalNumberOfObservations: Double
        get() = myTotNumObs

    /**
     * Returns a copy of the StatisticIfc that is tabulating the
     * current batch
     *
     * @return a reference to the StatisticIfc that is tabulating the
     * current batch
     */
    override val currentBatchStatistic: StatisticIfc
        get() = myStatistic.instance()

    override fun instance(): BatchStatistic {
        return BatchStatistic.instance(this)
    }

    companion object {
        /**
         * the default minimum number of batches
         */
        const val MIN_NUM_BATCHES = 20

        /**
         * the default minimum number of observations per batch
         */
        const val MIN_NUM_OBS_PER_BATCH = 16

        /**
         * the default multiplier that determines the maximum number of batches
         */
        const val MAX_BATCH_MULTIPLE = 2

        fun instance(bStat: BatchStatistic): BatchStatistic {
            val b = BatchStatistic(
                bStat.minNumBatches,
                bStat.minBatchSize,
                bStat.minNumBatchesMultiple,
                bStat.name
            )
            for (i in 1..b.maxNumBatches) {
                b.bm[i] = bStat.bm[i]
            }
            b.numBatches = bStat.numBatches
            b.numRebatches = bStat.numRebatches
            b.currentBatchSize = bStat.currentBatchSize
            b.myStatistic = bStat.myStatistic.instance()
            b.myBMStatistic = bStat.myBMStatistic.instance()
            b.myValue = bStat.myValue
            b.myTotNumObs = bStat.myTotNumObs
            return b
        }

        /** Takes an array of length, n, and computes k batch means where each batch mean
         * is the average of batchSize (b) elements such that b = Math.FloorDiv(n, k).
         * If the number of batches, k, does not divide evenly into n, then n - (k*b) observations are not processed
         * at the end of the array.
         *
         * The batch means are contained in the returned array.
         *
         * @param data the data to batch, must not be null, and must have at least batchSize elements
         * @param numBatches the number of batches (k), must be less than or equal to n and greater than 0
         * @return an array of the batch means
         */
        fun batchMeans(data: DoubleArray, numBatches: Int): DoubleArray {
            require(numBatches > 0) { "The number of batches must be > 0" }
            require(numBatches <= data.size) { "The number of batches must be less than data.length" }
            if (numBatches == data.size) {
                // no need to batch, just return a copy
                return data.copyOf()
            }
            // compute the batch size
            val batchSize = Math.floorDiv(data.size, numBatches)
//            val batchSize = data.size.floorDiv(numBatches)
            val bm = DoubleArray(numBatches)
            val statistic = Statistic()
            var j = 0
            for (datum in data) {
                statistic.collect(datum)
                if (statistic.count == batchSize.toDouble()) {
                    bm[j] = statistic.average
                    j = j + 1
                    statistic.reset()
                }
                if (j == numBatches) {
                    break
                }
            }
            return bm
        }
    }
}

fun main(){
    val d = ExponentialRV(2.0)
    // number of observations
    val n = 1000

    // minimum number of batches permitted
    // there will not be less than this number of batches
    val minNumBatches = 40

    // minimum batch size permitted
    // the batch size can be no smaller than this amount
    val minBatchSize = 25

    // maximum number of batch multiple
    //  The multiple of the minimum number of batches
    //  that determines the maximum number of batches
    //  e.g. if the min. number of batches is 20
    //  and the max number batches multiple is 2,
    //  then we can have at most 40 batches
    val maxNBMultiple = 2

    // In this example, since 40*25 = 1000, the batch multiple does not matter
    val bm = BatchStatistic(minNumBatches, minBatchSize, maxNBMultiple)

    for (i in 1..n) {
        bm.collect(d.value)
    }
    println(bm)

    val bma = bm.batchMeans
    var i = 0
    for (x in bma) {
        println("bm($i) = $x")
        i++
    }
    println()
    // this rebatches the 40 down to 10
    val reformed = bm.reformBatches(10)
    for((j, x) in reformed.withIndex()){
        println("reformed($j) = $x")
    }
    println()

    println(Statistic(reformed))
}