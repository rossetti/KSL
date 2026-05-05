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

package ksl.app.session

import ksl.observers.ReplicationDataCollector
import ksl.simulation.Model
import kotlinx.coroutines.CoroutineScope

/**
 * Opt-in attachment that captures per-replication observation arrays for all
 * [ksl.modeling.variable.Response] and [ksl.modeling.variable.Counter] elements
 * present in the model at the time [onAttach] is called.
 *
 * Add to [RunRequest.SingleRun.attachments] when box-plot or Welch analysis is
 * needed after the run.  Not attached by default: [RunResult.Completed.snapshot]
 * covers across-replication aggregates for most use cases without the extra memory
 * cost of per-replication arrays.
 *
 * ```kotlin
 * val repData = ReplicationDataAttachment()
 * val handle = runner.submit(RunRequest.SingleRun(model, attachments = listOf(repData)))
 * handle.result.await()
 * val arrays: Map<String, DoubleArray> = repData.replicationData
 * ```
 *
 * [replicationData] is available after [RunHandle.result] resolves.  Each array
 * has length equal to `model.numberOfReplications`; values are the replication
 * average for [ksl.modeling.variable.Response] elements and the final counter
 * value for [ksl.modeling.variable.Counter] elements.
 */
class ReplicationDataAttachment : RunAttachmentIfc {

    private var myCollector: ReplicationDataCollector? = null

    /**
     * Per-replication data arrays keyed by element name.
     * Empty if [onAttach] has not been called or the model had no responses/counters.
     */
    val replicationData: Map<String, DoubleArray>
        get() = myCollector?.allReplicationDataAsMap ?: emptyMap()

    override fun onAttach(model: Model, scope: CoroutineScope) {
        myCollector = ReplicationDataCollector(model, addAll = true)
    }

    override fun onDetach() {
        myCollector?.stopObserving()
    }
}
