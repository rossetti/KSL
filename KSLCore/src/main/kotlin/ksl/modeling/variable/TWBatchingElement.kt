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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.modeling.variable

import ksl.observers.BatchStatisticObserver
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.BatchStatisticIfc
import ksl.utilities.statistic.WeightedStatistic

/**
 * This class controls the batching of time weighted variables within the Model.
 *
 * The batch interval is used to schedule events during a replication and must
 * be the same throughout the replication. If the supplied interval is 0.0, then
 * the method getApproximateBatchInterval() will be used to determine the
 * interval for the replication.
 *
 * Time-based variables (TimeWeighted) are first discretized based on a batching
 * interval. The default batching interval is based on the value of the initial
 * number of batches. This is by default set to DEFAULT_NUM_TW_BATCHES = 512.
 * These initial batches are then rebatched according to the procedures within
 * BatchStatistic
 *
 * Use addTimeWeighted(TimeWeighted tw) to add TimeWeighted variables to the
 * batching.
 * @param modelElement the model element
 * @param batchingInterval the batching interval, must be greater than 0
 * @param name a name for the element
 * @author rossetti
 */
class TWBatchingElement(
    modelElement: ModelElement,
    batchingInterval: Double = 0.0,
    name: String? = null
) : ModelElement(modelElement, name) {
    init {
        require(batchingInterval>= 0.0) {"The supplied batching interval must be >= 0.0"}
    }
    /**
     * A reference to the Batching event.
     */
    private var myBatchEvent: KSLEvent<Nothing>? = null

    /**
     * The priority for the batching events.
     */
    var batchEventPriority: Int = KSLEvent.DEFAULT_BATCH_PRIORITY

    /**
     * The time interval between batching events.
     */
    private var myTimeBtwBatches = 0.0

    /**
     * Sets the batch interval length. Changing this during a replication has no
     * effect. The batch interval is used to schedule events during a
     * replication and must be the same throughout the replication. If the
     * supplied interval is 0.0, then the method getApproximateBatchInterval()
     * will be used to determine the interval for the replication
     *
     * The batch interval size in time units must be &gt;=0, if it is larger than run length it will not occur
     */
    var batchInterval: Double  = batchingInterval
        set(batchInterval) {
            require(batchInterval >= 0.0) { "The batch interval cannot be less than zero" }
            field = batchInterval
        }

    /**
     * The starting number of batches for time weighted batching. Used in
     * approximating a batch interval size
     */
    var timeWeightedStartingNumberOfBatches = DEFAULT_NUM_TW_BATCHES
        set(numBatches){
            require(numBatches > 0) { "The number of batches must be > 0" }
            if (numBatches < 10) {
                val sb = StringBuilder()
                sb.append("The number of initial batches < 10")
                sb.appendLine()
                sb.append("is not recommended for batching time-based variables")
                sb.appendLine()
                Model.logger.warn { sb.toString() }
                System.out.flush()
            }
            field = numBatches
        }

    /**
     * Holds the statistics across the time scheduled batches for the time
     * weighted variables
     *
     */
    private val myBatchStatsMap: MutableMap<TWResponse, TWBatchStatisticObserver> = mutableMapOf()

    private val myEventHandler: EventHandler = EventHandler()

    /** Look up the TWBatchStatisticObserver for the given TimeWeighted variable
     *
     * @param tw the TimeWeighted variable
     * @return the TWBatchStatisticObserver
     */
    fun timeWeightedBatchObserverFor(tw: TWResponseCIfc): TWBatchStatisticObserver? {
        return myBatchStatsMap[tw]
    }

    /**
     * Adds the supplied TWResponse variable to the batching
     *
     * @param tw the TimeWeighted variable to add
     * @param minNumBatches minimum number of batches
     * @param minBatchSize minimum batch size
     * @param maxNBMultiple batch size multiple
     * @param name name for BatchStatistic
     * @return the TWBatchStatisticObserver
     */
    fun add(
        tw: TWResponseCIfc,
        minNumBatches: Int = BatchStatistic.MIN_NUM_BATCHES,
        minBatchSize: Int = BatchStatistic.MIN_NUM_OBS_PER_BATCH,
        maxNBMultiple: Int = BatchStatistic.MAX_BATCH_MULTIPLE,
        name: String = tw.name
    ): TWBatchStatisticObserver {
        val bo = TWBatchStatisticObserver(tw as TWResponse, minNumBatches, minBatchSize, maxNBMultiple, name)
        myBatchStatsMap[tw] = bo
        tw.attachModelElementObserver(bo)
        return bo
    }

    /**
     * Removes the supplied TWResponse variable from the batching
     *
     * @param tw the TimeWeighted to be removed
     */
    fun remove(tw: TWResponse) {
        val bo = myBatchStatsMap[tw]
        if (bo != null){
            tw.detachModelElementObserver(bo)
            myBatchStatsMap.remove(tw)
        }
    }

    /**
     * Removes all previously added TWResponse from the batching
     *
     */
    fun removeAll() {
        // first detach all the observers, then clear the map
        for (tw in myBatchStatsMap.keys) {
            val bo = myBatchStatsMap[tw]
            if (bo != null) {
                tw.detachModelElementObserver(bo)
            }
        }
        myBatchStatsMap.clear()
    }

    /**
     * Returns a statistical summary BatchStatistic on the TimeWeighted variable
     * across the observed batches This returns a copy of the summary
     * statistics.
     *
     * @param tw the TimeWeighted to look up
     * @return the returned BatchStatistic
     */
    fun batchStatisticFor(tw: TWResponseCIfc): BatchStatisticIfc {
        val bo = myBatchStatsMap[tw]
        return bo?.batchStatistics ?: BatchStatistic(theName = tw.name + " Across Batch Statistics")
    }

    /**
     * Returns a list of summary statistics on all TimeWeighted variables. The
     * list is a copy of originals.
     *
     * @return the filled up list
     */
    val allBatchStatistics: List<BatchStatisticIfc>
        get() {
            val list: MutableList<BatchStatisticIfc> = mutableListOf()
            for (tw in myBatchStatsMap.keys) {
                list.add(batchStatisticFor(tw))
            }
            return list
        }

    /**
     *
     * @return a map of all batch statistics with the TimeWeighted variable as the key
     */
    val allBatchStatisticsAsMap: Map<TWResponseCIfc, BatchStatisticIfc>
        get() {
            val map: MutableMap<TWResponseCIfc, BatchStatisticIfc> = mutableMapOf()
            for (tw in myBatchStatsMap.keys) {
                map[tw] = myBatchStatsMap[tw]!!.batchStatistics
            }
            return map
        }

    /**
     * Checks if a batching event has been scheduled for this model element
     *
     * @return True means that it has been scheduled.
     */
    val isBatchEventScheduled: Boolean
        get() = if (myBatchEvent == null) {
            false
        } else {
            myBatchEvent!!.isScheduled
        }

    /**
     * This method returns a suggested batching interval based on the length of
     * the run, the warm-up period, and default number of batches.
     *
     * @return a double representing an approximate batch interval
     */
    val approximateBatchInterval: Double
       get() = approximateBatchInterval(model.lengthOfReplication, model.lengthOfReplicationWarmUp)

    /**
     * This method returns a suggested batching interval based on the length of
     * the replication and warm up length for TimeWeighted variables.
     *
     * This value is used in the calculation of the approximate batching
     * interval if batching is turned on and there is a finite run length.
     *
     * If the run length is finite, then the batch interval is approximated as
     * follows:
     *
     * t = length of replication - length of warm up n =
     * getTimeWeightedStartingNumberOfBatches()
     *
     * batching interval = t/n
     *
     * DEFAULT_NUM_TW_BATCHES = 512.0
     *
     * @param repLength the length of the replication
     * @param warmUp the warm-up period for the replication
     * @return the recommended batching interval
     */
    fun approximateBatchInterval(repLength: Double, warmUp: Double): Double {
        require(repLength > 0.0) { "The length of the replication must be > 0" }
        require(warmUp >= 0) { "The warm up length must be >= 0" }
        val deltaT: Double = if (repLength.isInfinite()) {
            // runlength is infinite
            DEFAULT_BATCH_INTERVAL
        } else { // runlength is finite
            var t = repLength
            t = t - warmUp // actual observation length
            val n = timeWeightedStartingNumberOfBatches
            t / n
        }
        return deltaT
    }

    override fun beforeReplication() {
        if (batchInterval == 0.0) {
            batchInterval = approximateBatchInterval
        }
        myTimeBtwBatches = batchInterval
    }

    override fun initialize() {
        myBatchEvent = myEventHandler.schedule(myTimeBtwBatches, priority = batchEventPriority)
    }

    /**
     * The batch method is called during each replication when the batching
     * event occurs This method ensures that each time weighted variable gets
     * within replication batch statistics collected across batches
     */
    protected fun batch() {
        for (tw in myBatchStatsMap.keys) {
            tw.value = tw.value
            val bo = myBatchStatsMap[tw]
            bo!!.batch()
        }
    }

    private inner class EventHandler : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            batch()
            myBatchEvent = schedule(myTimeBtwBatches)
        }
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append("------------------------------------------------------------")
        sb.append("\n")
        sb.append("Batch Statistics")
        sb.append("\n")
        sb.append("------------------------------------------------------------")
        sb.append("\n")
        sb.append("TimeWeighted Variables \n")
        sb.append("Batching based on ")
        sb.append("batch interval = ")
        sb.append(batchInterval)
        sb.append(" time units \n")
        sb.append("Initial number of batches = ")
        sb.append(timeWeightedStartingNumberOfBatches)
        sb.append("\n")
        for (bo in myBatchStatsMap.values) {
            sb.append(bo)
            sb.append("\n")
        }
        sb.append("------------------------------------------------------------")
        sb.append("\n")
        return sb.toString()
    }

    /**
     * Gets the CSV row for the TimeWeighted
     *
     * @param tw
     * @return the data as a string
     */
    fun getCSVRow(tw: TWResponseCIfc): String {
        val row = StringBuilder()
        row.append(model.name)
        row.append(",")
        row.append("TimeWeighted")
        row.append(",")
        val b: BatchStatisticIfc = batchStatisticFor(tw)
        row.append(b.csvStatistic)
        return row.toString()
    }

    /**
     * Gets the CSV Header for the TimeWeighted
     *
     * @param tw the time weighted variable
     * @return the header
     */
    fun getCSVHeader(tw: TWResponse): String {
        val header = StringBuilder()
        header.append("Model,")
        header.append("StatType,")
        val b: BatchStatisticIfc = batchStatisticFor(tw)
        header.append(b.csvHeader)
        return header.toString()
    }

    inner class TWBatchStatisticObserver(
        tw: TWResponse,
        minNumBatches: Int = BatchStatistic.MIN_NUM_BATCHES,
        minBatchSize: Int = BatchStatistic.MIN_NUM_OBS_PER_BATCH,
        maxNBMultiple: Int = BatchStatistic.MAX_BATCH_MULTIPLE,
        name: String? = null
    ) : BatchStatisticObserver(minNumBatches, minBatchSize, maxNBMultiple, name) {

        private var myWithinBatchStats: WeightedStatistic = WeightedStatistic()
        private val myTW: TWResponse = tw

        /**
         * Returns the observed TimeWeighted
         */
       val timeWeighted: TWResponseCIfc
                get() = myTW

        override fun beforeReplication(modelElement: ModelElement) {
            super.beforeReplication(modelElement)
            myWithinBatchStats.reset()
        }

        override fun update(modelElement: ModelElement) {
            val weight: Double = myTW.weight
            val prev: Double = myTW.previousValue
            myWithinBatchStats.collect(prev, weight)
        }

        override fun warmUp(modelElement: ModelElement) {
            super.warmUp(modelElement)
            myWithinBatchStats.reset()
        }

        /**
         * Causes the observer to collect the batch statistics
         *
         */
        internal fun batch() {
            val avg: Double = myWithinBatchStats.average()
            myBatchStats.collect(avg)
            myWithinBatchStats.reset()
        }
    }

    companion object {
        /**
         * A constant for the default batch interval for a replication If there is
         * no run length specified and the user turns on default batching, then the
         * time interval between batches will be equal to this value. The default
         * value is 10.0
         */
        const val DEFAULT_BATCH_INTERVAL = 10.0

        /**
         * A constant for the default number of batches for TimeWeighted variables.
         * This value is used in the calculation of the approximate batching
         * interval if batching is turned on and there is a finite run length.
         *
         * If the run length is finite, then the batch interval is approximated as
         * follows:
         *
         * t = length of replication - length of warm up
         * n = getTimeWeightedStartingNumberOfBatches()
         *
         * batching interval = t/n
         *
         * DEFAULT_NUM_TW_BATCHES = 512.0
         *
         */
        const val DEFAULT_NUM_TW_BATCHES = 512.0
    }
}