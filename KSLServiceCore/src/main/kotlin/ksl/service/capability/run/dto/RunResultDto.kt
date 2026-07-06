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

import ksl.service.dto.DtoVersion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire projection of [ksl.app.session.RunResult] — the structured terminal
 * outcome a transport returns to a client. Sealed so it serializes
 * polymorphically with a `type` discriminator, mirroring the source sealed
 * hierarchy one variant for one.
 *
 * The inline payload follows the Phase 7 strategic plan §9.1 policy: summary
 * statistics travel inline (here), while full reports and bulk per-replication
 * data are exposed as downloadable [ArtifactRef]s.
 */
@Serializable
sealed class RunResultDto {
    abstract val version: DtoVersion

    /** Projection of `RunResult.Completed` (a single-model run). */
    @Serializable
    @SerialName("completed")
    data class Completed(
        val summary: RunSummaryDto,
        val responses: List<ResponseStatDto>,
        val artifacts: List<ArtifactRef> = emptyList(),
        override val version: DtoVersion = DtoVersion(),
    ) : RunResultDto()

    /** Projection of `RunResult.BatchCompleted` (scenario sweep / designed experiment). */
    @Serializable
    @SerialName("batch")
    data class BatchCompleted(
        val summary: OrchestratorSummaryDto,
        val items: List<BatchItemDto>,
        val artifacts: List<ArtifactRef> = emptyList(),
        override val version: DtoVersion = DtoVersion(),
    ) : RunResultDto()

    /** Projection of `RunResult.OptimizationCompleted`. */
    @Serializable
    @SerialName("optimization")
    data class OptimizationCompleted(
        val summary: OrchestratorSummaryDto,
        val best: SolutionDto,
        val iterations: List<IterationDto>,
        val artifacts: List<ArtifactRef> = emptyList(),
        override val version: DtoVersion = DtoVersion(),
    ) : RunResultDto()

    /** Projection of `RunResult.Failed`. */
    @Serializable
    @SerialName("failed")
    data class Failed(
        val errorType: String,
        val message: String,
        override val version: DtoVersion = DtoVersion(),
    ) : RunResultDto()

    /** Projection of `RunResult.Cancelled`. */
    @Serializable
    @SerialName("cancelled")
    data class Cancelled(
        val reason: String,
        override val version: DtoVersion = DtoVersion(),
    ) : RunResultDto()
}

/** One scenario / design point within a [RunResultDto.BatchCompleted]. */
@Serializable
data class BatchItemDto(
    val itemName: String,
    val responses: List<ResponseStatDto>,
    /**
     * Per-replication response values behind `responses`, keyed by response name, in
     * replication (repId) order — the raw observations an after-the-fact multiple-comparison
     * (MCB) analysis needs, which the aggregate statistics alone cannot reconstruct. Defaulted
     * for backward compatibility: results retained before this field decode with an empty map,
     * and the request-hashed cache key is unchanged so nothing is invalidated.
     */
    val replicationObservations: Map<String, List<Double>> = emptyMap(),
    /** The number of completed replications the observations span (0 when none were captured). */
    val numReplications: Int = 0,
)

/**
 * A reference to a server-local run artifact (full HTML/Markdown report, bulk
 * per-replication data, database capture). The REST transport mints a download
 * URL from [path]; the MCP transport exposes it as a resource.
 */
@Serializable
data class ArtifactRef(
    val name: String,
    val mediaType: String,
    val path: String,
)

/** Projection of a simulation-optimization `Solution`. */
@Serializable
data class SolutionDto(
    val inputs: Map<String, Double>,
    val estimatedObjFncValue: Double,
    val penalizedObjFncValue: Double,
    val isValid: Boolean,
)

/** Projection of one `SolverStateSnapshot` for convergence inspection. */
@Serializable
data class IterationDto(
    val iterationNumber: Int,
    val numOracleCalls: Int,
    val estimatedObjFncValue: Double,
    val penalizedObjFncValue: Double,
)
