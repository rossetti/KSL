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

package ksl.service.capability.run.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Wire projection of [ksl.app.session.RunSummary].
 *
 * Near 1:1 with the source type; the derived `wallClockDuration` is dropped
 * (recomputable from [beginTime] / [endTime]) and the [endingStatus] enum is
 * carried by name. [Instant] serializes as ISO-8601.
 */
@Serializable
data class RunSummaryDto(
    val runId: String,
    val modelIdentifier: String,
    val experimentName: String,
    val requestedReplications: Int,
    val completedReplications: Int,
    val endingStatus: String,
    val beginTime: Instant,
    val endTime: Instant,
)

/**
 * Wire projection of [ksl.app.session.OrchestratorSummary] for batch and
 * optimization runs.
 */
@Serializable
data class OrchestratorSummaryDto(
    val runId: String,
    val orchestratorName: String,
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val beginTime: Instant,
    val endTime: Instant,
)
