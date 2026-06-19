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

package ksl.service.capability.run.dto.mapping

import ksl.app.session.KSLRuntimeError
import ksl.app.session.OrchestratorSummary
import ksl.app.session.RunResult
import ksl.app.session.RunSummary
import ksl.service.capability.run.dto.ArtifactRef
import ksl.service.capability.run.dto.BatchItemDto
import ksl.service.capability.run.dto.IterationDto
import ksl.service.capability.run.dto.OrchestratorSummaryDto
import ksl.service.capability.run.dto.ResponseStatDto
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.capability.run.dto.RunSummaryDto
import ksl.service.capability.run.dto.SolutionDto
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.utilities.io.dbutil.AcrossRepStatTableData

/**
 * Projects a [RunResult] (whose result types are database-row-shaped and not
 * `@Serializable`) into the wire-clean [RunResultDto] hierarchy. This is the
 * load-bearing translation of Phase 7.1: it is pure, reads only public fields
 * of the engine result types, and therefore requires no KSLCore change
 * (strategic plan §4.6).
 *
 * @param artifacts references to any reports/bulk data the caller has already
 *        materialized for this run (Phase 7 strategic plan §9.1).
 */
fun RunResult.toDto(artifacts: List<ArtifactRef> = emptyList()): RunResultDto = when (this) {
    is RunResult.Completed -> RunResultDto.Completed(
        summary = summary.toDto(),
        responses = snapshot.acrossRepStats.map { it.toDto() },
        artifacts = artifacts,
    )

    is RunResult.BatchCompleted -> RunResultDto.BatchCompleted(
        summary = summary.toDto(),
        items = snapshots.map { snap ->
            BatchItemDto(
                itemName = snap.experiment.exp_name,
                responses = snap.acrossRepStats.map { it.toDto() },
            )
        },
        artifacts = artifacts,
    )

    is RunResult.OptimizationCompleted -> RunResultDto.OptimizationCompleted(
        summary = summary.toDto(),
        best = bestSolution.bestSolutionSoFar.toDto(),
        iterations = iterationHistory.map { it.toDto() },
        artifacts = artifacts,
    )

    is RunResult.Failed -> RunResultDto.Failed(
        errorType = error::class.simpleName ?: "KSLRuntimeError",
        message = error.describe(),
    )

    is RunResult.Cancelled -> RunResultDto.Cancelled(reason = reason)
}

internal fun RunSummary.toDto(): RunSummaryDto = RunSummaryDto(
    runId = runId,
    modelIdentifier = modelIdentifier,
    experimentName = experimentName,
    requestedReplications = requestedReplications,
    completedReplications = completedReplications,
    endingStatus = endingStatus.name,
    beginTime = beginTime,
    endTime = endTime,
)

internal fun OrchestratorSummary.toDto(): OrchestratorSummaryDto = OrchestratorSummaryDto(
    runId = runId,
    orchestratorName = orchestratorName,
    totalItems = totalItems,
    completedItems = completedItems,
    failedItems = failedItems,
    beginTime = beginTime,
    endTime = endTime,
)

internal fun AcrossRepStatTableData.toDto(): ResponseStatDto = ResponseStatDto(
    name = stat_name,
    count = stat_count,
    average = average,
    stdDev = std_dev,
    stdErr = std_err,
    halfWidth = half_width,
    confLevel = conf_level,
    min = minimum,
    max = maximum,
    sum = sum_of_obs,
    deviationSumOfSquares = dev_ssq,
)

internal fun ksl.simopt.evaluator.Solution.toDto(): SolutionDto = SolutionDto(
    inputs = inputMap.toMap(),
    estimatedObjFncValue = estimatedObjFncValue,
    penalizedObjFncValue = penalizedObjFncValue,
    isValid = isValid,
)

internal fun SolverStateSnapshot.toDto(): IterationDto = IterationDto(
    iterationNumber = iterationNumber,
    numOracleCalls = numOracleCalls,
    estimatedObjFncValue = estimatedObjFncValue,
    penalizedObjFncValue = penalizedObjFncValue,
)

/** Human-readable message for a [KSLRuntimeError]; the source types carry the
 *  message on different fields, so unify them here for the wire. */
internal fun KSLRuntimeError.describe(): String = when (this) {
    is KSLRuntimeError.ModelBuildError -> message
    is KSLRuntimeError.JarLoadError -> message
    is KSLRuntimeError.ConfigurationError -> message
    is KSLRuntimeError.ExecutiveError ->
        "Executive error at simulated time $simTime (replication $replicationNumber): ${cause.message}"
}
