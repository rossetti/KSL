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

import kotlinx.datetime.Instant
import ksl.simulation.IterativeProcessIfc
import kotlin.time.Duration

/**
 * Lightweight in-memory summary of a completed (or terminated) simulation run.
 *
 * `RunSummary` is a *summary*, not a results container.  Per-replication
 * observations and across-replication statistics live in whatever output sinks
 * the user configured on the model (database, CSV files, in-memory responses,
 * etc.) and are accessed through the normal KSL APIs after the run completes.
 *
 * An instance is attached to the terminal [RunEvent] ([RunEvent.RunCompleted])
 * and is also available via [RunResult.Completed].
 *
 * @property runId globally unique identifier assigned by [Runner] when the run
 *           is submitted; useful for correlating log entries and database rows
 * @property modelIdentifier the [ksl.simulation.Model.modelIdentifier] of the
 *           model that was executed
 * @property experimentName the experiment name from the model at run time
 * @property requestedReplications the number of replications that were
 *           requested (`model.numberOfReplications`)
 * @property completedReplications how many replications actually finished;
 *           may be less than [requestedReplications] on cancellation or if the
 *           execution-time limit was reached
 * @property endingStatus the [IterativeProcessIfc.EndingStatus] value that
 *           describes why the replication loop terminated; derived from the
 *           model's public status properties after the loop exits
 * @property beginTime wall-clock instant at which [Runner] called
 *           `model.initializeReplications()`
 * @property endTime wall-clock instant at which the run finished; set by
 *           `model.endSimulation()` on the normal path, or by [Runner] on
 *           the cancellation and error paths
 */
data class RunSummary(
    val runId: String,
    val modelIdentifier: String,
    val experimentName: String,
    val requestedReplications: Int,
    val completedReplications: Int,
    val endingStatus: IterativeProcessIfc.EndingStatus,
    val beginTime: Instant,
    val endTime: Instant,
) {
    /** Elapsed wall-clock time for the entire run. */
    val wallClockDuration: Duration get() = endTime - beginTime
}
