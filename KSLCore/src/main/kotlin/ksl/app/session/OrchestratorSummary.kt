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
import kotlin.time.Duration

/**
 * Lightweight aggregate summary for an orchestrated multi-run execution
 * (scenario sweep, designed experiment, or simulation-optimization).
 *
 * Carried by [RunResult.OrchestratorCompleted].
 *
 * @property runId            unique identifier for this orchestrator run
 * @property orchestratorName human-readable name (e.g. `"ScenarioOrchestrator"`)
 * @property totalItems       total number of items submitted (scenarios, design points,
 *                            or iterations)
 * @property completedItems   items that finished without a [RuntimeException]
 * @property failedItems      items that threw a [RuntimeException] during execution
 * @property beginTime        wall-clock instant the orchestrator began
 * @property endTime          wall-clock instant the orchestrator finished
 */
data class OrchestratorSummary(
    val runId: String,
    val orchestratorName: String,
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val beginTime: Instant,
    val endTime: Instant
) {
    /** Wall-clock elapsed time for the entire orchestrated run. */
    val wallClockDuration: Duration get() = endTime - beginTime
}
