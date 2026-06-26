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

package ksl.service.preview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.materializeDesign
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationJson
import ksl.app.config.optimization.SolverSpec
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.FitConfiguration
import ksl.service.capability.fit.FitDocuments
import ksl.service.capability.run.ExperimentDocuments

/**
 * The last two authoring layers (Phase 8 plan §3.2 layers 6–7), folded into one
 * pre-run operation per document type:
 *
 * - **canonical echo** — the document re-encoded through its authoritative codec,
 *   so the agent sees the exact normalized shape (defaults filled, ordering
 *   canonical) and can diff its draft against it; and
 * - **workload/cost** — the work the document implies *without running it*: the
 *   replication budget, the design-point count (the 2^k blow-up that makes
 *   experiments expensive), the solver iteration budget, the dataset/estimator
 *   counts — the signal an agent needs to decide whether to commit.
 *
 * Pure: every figure is derived from the decoded document (and, for experiments,
 * `materializeDesign()`, which needs no model or database). Each function returns
 * `{ documentType, canonical, workload }`.
 */
object DocumentPreview {

    private val json = Json { allowSpecialFloatingPointValues = true }

    fun forRun(config: RunConfiguration): JsonObject {
        val totalSpecified = config.scenarios.sumOf { it.runOverrides?.numberOfReplications ?: 0 }
        val anyUnspecified = config.scenarios.any { it.runOverrides?.numberOfReplications == null }
        val workload = buildJsonObject {
            put("scenarioCount", config.scenarios.size)
            put("totalReplicationsSpecified", totalSpecified)
            put("someInheritModelDefaults", anyUnspecified)
            putJsonArray("scenarios") {
                config.scenarios.forEach { s ->
                    add(
                        buildJsonObject {
                            put("name", s.name)
                            put("replications", s.runOverrides?.numberOfReplications)
                            put("lengthOfReplication", s.runOverrides?.lengthOfReplication)
                        },
                    )
                }
            }
        }
        return envelope("RunConfiguration", RunConfigurationJson.encode(config), workload)
    }

    fun forOptimization(config: OptimizationRunConfiguration): JsonObject {
        val solver = config.solver
        val maxIterations = solver?.maxIterations
        val repsPerEval = (solver as? SolverSpec.StochasticHillClimbing)?.replicationsPerEvaluation
        val estimated = if (maxIterations != null && repsPerEval != null) maxIterations.toLong() * repsPerEval else null
        val workload = buildJsonObject {
            put("solver", solver?.let { it::class.simpleName } ?: "unspecified")
            put("maxIterations", maxIterations)
            put("replicationsPerEvaluation", repsPerEval)
            put("decisionVariables", config.problem?.inputs?.size ?: 0)
            put("objective", config.problem?.objectiveResponseName ?: "unspecified")
            // A lower bound: real solvers evaluate neighbors per iteration, so the
            // actual replication count is at least this many.
            put("estimatedReplicationsLowerBound", estimated)
        }
        return envelope("OptimizationRunConfiguration", OptimizationRunConfigurationJson.encode(config), workload)
    }

    fun forExperiment(config: ExperimentConfiguration): JsonObject {
        // materializeDesign enumerates the points with no model/DB; this is the
        // 2^k blow-up made visible before anything runs.
        val points = config.materializeDesign().designIterator().asSequence().toList()
        val totalReplications = points.sumOf { it.numReplications.toLong() }
        val workload = buildJsonObject {
            put("factorCount", config.factors.size)
            put("designType", config.designSpec::class.simpleName ?: "unknown")
            put("executionMode", config.executionMode.name)
            put("designPointCount", points.size)
            put("totalReplications", totalReplications)
        }
        return envelope("ExperimentConfiguration", ExperimentDocuments.encode(config), workload)
    }

    fun forFit(config: FitConfiguration): JsonObject {
        val datasetCount: Int? = when (val ds = config.dataSource) {
            is DataSourceReference.Inline -> ds.datasets.size
            is DataSourceReference.Generated -> 1
            else -> null // file / database: unknown without reading the source
        }
        val workload = buildJsonObject {
            put("kind", config.kind.name)
            put("dataSource", config.dataSource::class.simpleName ?: "unknown")
            put("datasetCount", datasetCount)
            put("estimatorCount", config.estimatorIds.size)
            put("usesCatalogDefaultEstimators", config.estimatorIds.isEmpty())
            put("scoringModelCount", config.scoringModelIds.size)
            put("bootstrap", config.bootstrap != null)
        }
        return envelope("FitConfiguration", FitDocuments.encode(config), workload)
    }

    private fun envelope(documentType: String, canonicalJson: String, workload: JsonObject): JsonObject =
        buildJsonObject {
            put("documentType", documentType)
            // The normalized document as a nested object (not an escaped string).
            put("canonical", json.parseToJsonElement(canonicalJson))
            put("workload", workload)
        }
}
