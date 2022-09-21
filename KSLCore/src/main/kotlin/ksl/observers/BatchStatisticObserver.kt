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
package ksl.observers

import ksl.modeling.variable.Response
import ksl.simulation.ModelElement
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.BatchStatisticIfc

/** An observer for batching of statistics on Response variable
 * The user can control the collection rule and the batching criteria
 * of the underlying BatchStatistic
 * @param minNumBatches The minimum number of batches, must be &gt;= 2
 * @param minBatchSize The minimum number of observations per batch, must be &gt;= 2
 * @param maxNBMultiple The maximum number of batches as a multiple of the
 * minimum number of batches.
 * @author rossetti
 */
open class BatchStatisticObserver (
    minNumBatches: Int = BatchStatistic.MIN_NUM_BATCHES,
    minBatchSize: Int = BatchStatistic.MIN_NUM_OBS_PER_BATCH,
    maxNBMultiple: Int = BatchStatistic.MAX_BATCH_MULTIPLE,
    name: String? = null
) : ModelElementObserver(name) {
    /**
     * The underlying BatchStatistic
     */
    protected val myBatchStats: BatchStatistic = BatchStatistic(minNumBatches, minBatchSize, maxNBMultiple, name)

    /**
     * false means no warm up event in parent hierarchy
     */
    var warmUpEventCheckFlag = false //TODO not sure what to do with this
        private set

    /**
     * The collected BatchStatistic
     */
    val batchStatistics: BatchStatisticIfc
        get() = myBatchStats

    override fun beforeExperiment(modelElement: ModelElement) {
        myBatchStats.reset()
        warmUpEventCheckFlag = false
    }

    override fun beforeReplication(modelElement: ModelElement) {
        myBatchStats.reset()
        val r: Response = modelElement as Response
        val mElement: ModelElement? = r.findModelElementWithWarmUpEvent()
        warmUpEventCheckFlag = mElement != null
    }

    override fun update(modelElement: ModelElement) {
        val r: Response = modelElement as Response
        myBatchStats.collect(r.value)
    }

    override fun warmUp(modelElement: ModelElement) {
        myBatchStats.reset()
    }

    override fun toString(): String {
        return myBatchStats.toString()
    }
}