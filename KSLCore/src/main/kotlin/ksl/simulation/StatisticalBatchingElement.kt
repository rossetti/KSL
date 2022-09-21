/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.simulation

import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseBatchingElement
import ksl.modeling.variable.TWBatchingElement
import ksl.modeling.variable.TWResponse
import ksl.observers.BatchStatisticObserver
import ksl.utilities.io.StatisticReporter
import ksl.utilities.statistic.BatchStatisticIfc
import ksl.utilities.statistic.StatisticIfc

/**
 * When added to a Model, this class will cause batch statistics to be collected
 * for Response and TWResponse variables. It uses the
 * TWBatchingElement and the ResponseBatchingElement to perform this
 * functionality.
 *
 * Time weighted variables are first discretized using a supplied batch interval. Then,
 * observation based batching is applied to the discretized batches.  Response variables
 * are batched by observation number.
 *
 * @param model the model for the batching
 * @param batchInterval the discretizing interval for TimeWeighted variables
 * @param name the name of the model element
 * @author rossetti
 */
class StatisticalBatchingElement(
    model: Model,
    batchInterval: Double = 0.0,
    name: String? = null
) : ModelElement(model, name) {

    private val myTWBatcher: TWBatchingElement = TWBatchingElement(this, batchInterval)
    private val myRVBatcher: ResponseBatchingElement = ResponseBatchingElement(this)

    /**
     *
     * Amap of all batch statistics with the ResponseVariable variable as the key
     */
    val allResponseBatchStatisticsAsMap: Map<Response, BatchStatisticIfc>
        get() = myRVBatcher.allBatchStatisticsAsMap

    /**
     *
     * A map of all batch statistics with the TimeWeighted variable as the key
     */
    val allTimeWeightedBatchStatisticsAsMap: Map<TWResponse, BatchStatisticIfc>
        get() = myTWBatcher.allBatchStatisticsAsMap

    /**
     * Look up the BatchStatisticObserver for the ResponseVariable
     *
     * @param response the ResponseVariable to look up
     * @return the BatchStatisticObserver
     */
    fun batchStatisticObserverFor(response: Response): BatchStatisticObserver? {
        return if (response is TWResponse) {
            myTWBatcher.timeWeightedBatchObserverFor(response)
        } else {
            myRVBatcher.batchStatisticObserverFor(response)
        }
    }

    /**
     * Removes the supplied ResponseVariable variable from the batching
     *
     * @param response the ResponseVariable to be removed
     */
    fun remove(response: Response) {
        if (response is TWResponse) {
            myTWBatcher.remove(response)
        } else {
            myRVBatcher.remove(response)
        }
    }

    /**
     * Removes all previously added responses from the batching
     *
     */
    fun removeAll() {
        myTWBatcher.removeAll()
        myRVBatcher.removeAll()
    }

    /**
     * Returns a statistical summary BatchStatistic on the Response
     * variable across the observed batches. This returns a view of the summary
     * statistics.
     *
     * @param response the Response to look up
     * @return the returned BatchStatisticIfc
     */
    fun batchStatisticFor(response: Response): BatchStatisticIfc {
        return if (response is TWResponse) {
            myTWBatcher.batchStatisticFor(response)
        } else {
            myRVBatcher.batchStatisticFor(response)
        }
    }

    /**
     * Returns a list of summary statistics on all Response variables
     * The list is a copy of originals.
     */
    val allBatchStatistcs: List<BatchStatisticIfc>
        get() {
            val list: MutableList<BatchStatisticIfc> = mutableListOf()
            list.addAll(myTWBatcher.allBatchStatistics)
            list.addAll(myRVBatcher.allBatchStatistics)
            return list
        }

    /**
     * Returns a list of the batch statistics in the form of StatisticIfc
     *
     */
    val allStatistics: List<StatisticIfc>
        get() {
            val list: MutableList<StatisticIfc> = mutableListOf()
            list.addAll(allBatchStatistcs)
            return list
        }

    /**
     * Returns a StatisticReporter for reporting the statistics across the batches.
     */
    val statisticReporter: StatisticReporter
        get() {
            val list: MutableList<StatisticIfc> = mutableListOf()
            list.addAll(allStatistics)
            val sr = StatisticReporter(list)
            sr.reportTitle = "Batch Summary Report"
            return sr
        }

    override fun beforeExperiment() {
        removeAll()
        // now add all appropriate responses to the batching
        val list: List<Response> = model.responses
        for (r in list) {
            if (r is TWResponse) {
                myTWBatcher.add(r)
            } else {
                myRVBatcher.add(r)
            }
        }
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append(myTWBatcher.asString())
        sb.append(myRVBatcher.asString())
        return sb.toString()
    }
}