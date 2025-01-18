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
package ksl.simulation

import ksl.modeling.variable.*
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
 * @param batchInterval the discretizing interval for TWResponse variables
 * @param name the name of the model element
 * @author rossetti
 */
class StatisticalBatchingElement(
    model: Model,
    batchInterval: Double = 0.0,
    name: String? = null
) : ModelElement(model, name) {

    private val twBatchingElement: TWBatchingElement = TWBatchingElement(this, batchInterval)
    private val responseBatchingElement: ResponseBatchingElement = ResponseBatchingElement(this)

    /**
     *
     * A map of all batch statistics with the ResponseVariable variable as the key
     */
    val allResponseBatchStatisticsAsMap: Map<ResponseCIfc, BatchStatisticIfc>
        get() = responseBatchingElement.allBatchStatisticsAsMap

    /**
     *
     * A map of all batch statistics with the TimeWeighted variable as the key
     */
    val allTimeWeightedBatchStatisticsAsMap: Map<TWResponseCIfc, BatchStatisticIfc>
        get() = twBatchingElement.allBatchStatisticsAsMap

    /**
     * Look up the BatchStatisticObserver for the ResponseVariable
     *
     * @param response the ResponseVariable to look up
     * @return the BatchStatisticObserver
     */
    fun batchStatisticObserverFor(response: ResponseCIfc): BatchStatisticObserver? {
        return if (response is TWResponse) {
            twBatchingElement.timeWeightedBatchObserverFor(response)
        } else {
            responseBatchingElement.batchStatisticObserverFor(response)
        }
    }

    /**
     * Removes the supplied ResponseVariable variable from the batching
     *
     * @param response the ResponseVariable to be removed
     */
    fun remove(response: ResponseCIfc) {
        if (response is TWResponse) {
            twBatchingElement.remove(response)
        } else {
            responseBatchingElement.remove(response)
        }
    }

    /**
     * Removes all previously added responses from the batching
     *
     */
    fun removeAll() {
        twBatchingElement.removeAll()
        responseBatchingElement.removeAll()
    }

    /**
     * Returns a statistical summary BatchStatistic on the Response
     * variable across the observed batches. This returns a view of the summary
     * statistics.
     *
     * @param response the Response to look up
     * @return the returned BatchStatisticIfc
     */
    fun batchStatisticFor(response: ResponseCIfc): BatchStatisticIfc {
        return if (response is TWResponse) {
            twBatchingElement.batchStatisticFor(response)
        } else {
            responseBatchingElement.batchStatisticFor(response)
        }
    }

    /**
     * Returns a list of summary statistics on all Response variables
     * The list is a copy of originals.
     */
    val allBatchStatistcs: List<BatchStatisticIfc>
        get() {
            val list: MutableList<BatchStatisticIfc> = mutableListOf()
            list.addAll(twBatchingElement.allBatchStatistics)
            list.addAll(responseBatchingElement.allBatchStatistics)
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
        val list: List<ResponseCIfc> = model.responses
        for (r in list) {
            if (r is TWResponse) {
                twBatchingElement.add(r)
            } else {
                responseBatchingElement.add(r)
            }
        }
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append(twBatchingElement.asString())
        sb.append(responseBatchingElement.asString())
        return sb.toString()
    }
}