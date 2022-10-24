/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
import ksl.simulation.ModelElement
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.BatchStatisticIfc

/** Controls the batching of Response variables within the Model. Used by StatisticalBatchingElement.
 *
 * @author rossetti
 */
class ResponseBatchingElement(modelElement: ModelElement, name: String? = null) : ModelElement(modelElement, name) {
    /**
     * Holds the statistics across the time scheduled batches for the time
     * weighted variables
     *
     */
    private val myBatchStats: MutableMap<Response, BatchStatisticObserver> = mutableMapOf()

    /**
     * Adds the supplied Response variable to the batching
     *
     * @param response the Response variable to add
     * @param minNumBatches minimum number of batches
     * @param minBatchSize minimum batch size
     * @param maxNBMultiple batch size multiple
     * @param name name for BatchStatistic
     * @return the BatchStatisticObserver
     */
    fun add(response: Response,
            minNumBatches: Int = BatchStatistic.MIN_NUM_BATCHES,
            minBatchSize: Int = BatchStatistic.MIN_NUM_OBS_PER_BATCH,
            maxNBMultiple: Int = BatchStatistic.MAX_BATCH_MULTIPLE,
            name: String = response.name
    ): BatchStatisticObserver {
        val bo = BatchStatisticObserver(
            minNumBatches,
            minBatchSize, maxNBMultiple, name
        )
        myBatchStats[response] = bo
        response.attachModelElementObserver(bo)
        return bo
    }

    /**
     * Look up the BatchStatisticObserver for the Response
     *
     * @param response the ResponseVariable to look up
     * @return the BatchStatisticObserver
     */
    fun batchStatisticObserverFor(response: Response): BatchStatisticObserver? {
        return myBatchStats[response]
    }

    /**
     * Removes the supplied Response variable from the batching
     *
     * @param response the Response to be removed
     */
    fun remove(response: Response) {
        val bo: BatchStatisticObserver? = myBatchStats[response]
        if (bo != null){
            response.detachModelElementObserver(bo)
            myBatchStats.remove(response)
        }
    }

    /**
     * Removes all previously added ResponseVariable from the batching
     *
     */
    fun removeAll() {
        // first remove all the observers, then clear the map
        for (response in myBatchStats.keys) {
            val bo: BatchStatisticObserver? = myBatchStats[response]
            if (bo != null){
                response.detachModelElementObserver(bo)
            }
        }
        myBatchStats.clear()
    }

    /**
     * Returns a statistical summary BatchStatistic on the Response
     * variable across the observed batches.
     *
     * @param response the Response to look up
     * @return the returned BatchStatistic
     */
    fun batchStatisticFor(response: Response): BatchStatisticIfc {
        val bo: BatchStatisticObserver? = myBatchStats[response]
        return bo?.batchStatistics ?: BatchStatistic(theName = "${response.name} Across Batch Statistics")
    }

    /**
     * Returns a list of summary statistics on all ResponseVariable variables
     * The list is a copy of originals.
     *
     * @return the filled up list
     */
    val allBatchStatistics: List<BatchStatisticIfc>
        get() {
            val list: MutableList<BatchStatisticIfc> = mutableListOf()
            for (r in myBatchStats.keys) {
                list.add(batchStatisticFor(r))
            }
            return list
        }

    /**
     *
     * @return a map of all batch statistics with the Response variable as the key
     */
    val allBatchStatisticsAsMap: Map<Response, BatchStatisticIfc>
        get() {
            val map: MutableMap<Response, BatchStatisticIfc> = mutableMapOf()
            for (tw in myBatchStats.keys) {
                map[tw] = myBatchStats[tw]!!.batchStatistics
            }
            return map
        }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append("------------------------------------------------------------")
        sb.appendLine()
        sb.append("Batch Statistics")
        sb.appendLine()
        sb.append("------------------------------------------------------------")
        sb.appendLine()
        sb.append("Response Variables")
        sb.appendLine()
        for (bo in myBatchStats.values) {
            sb.append(bo)
            sb.appendLine()
        }
        sb.append("------------------------------------------------------------")
        sb.appendLine()
        return sb.toString()
    }

    /**
     * Gets the CSV row for the ResponseVariable
     *
     * @param response
     * @return the data as a string
     */
    fun getCSVRow(response: Response): String {
        val row = StringBuilder()
        row.append(response.model.name)
        row.append(",")
        row.append("Response")
        row.append(",")
        val b: BatchStatisticIfc = batchStatisticFor(response)
        row.append(b.csvStatistic)
        return row.toString()
    }

    /**
     * Gets the CSV Header for the ResponseVariable
     *
     * @param response
     * @return the header
     */
    fun getCSVHeader(response: Response): String {
        val header = StringBuilder()
        header.append("Model,")
        header.append("StatType,")
        val b: BatchStatisticIfc = batchStatisticFor(response)
        header.append(b.csvHeader)
        return header.toString()
    }
}