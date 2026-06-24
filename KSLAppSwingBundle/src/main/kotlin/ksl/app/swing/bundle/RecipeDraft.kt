/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.bundle

import ksl.app.bundle.ConfigRecipeKind
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec

/** A recipe already shipped in the bundle, for listing in the Recipes tab. */
data class RecipeInfo(val name: String, val kind: ConfigRecipeKind)

/**
 * An editable model for authoring a `RUN` recipe — a single-scenario
 * [RunConfiguration] of run-parameter overrides for the selected model. The
 * Workbench writes its TOML projection to `BundleLayout.runRecipesDir(modelId)`.
 *
 * Only the headline run parameters are surfaced (replications, length, warm-up,
 * antithetic); any field left null inherits the model default at run time
 * (`ExperimentRunOverrides` semantics). Scenario-batch and optimization recipes
 * are out of scope for this draft.
 */
data class RecipeDraft(
    val name: String = "",
    val numberOfReplications: Int? = null,
    val lengthOfReplication: Double? = null,
    val lengthOfReplicationWarmUp: Double? = null,
    val antithetic: Boolean? = null,
) {
    /** Returns human-readable problems; empty when the draft is valid to save. */
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Recipe name must not be blank.")
        else if (name.any { it == '/' || it == '\\' } || name == "." || name == "..") {
            add("Recipe name must be a safe file name (no slashes).")
        }
        if (numberOfReplications != null && numberOfReplications < 1) add("Replications must be >= 1.")
        if (lengthOfReplication != null && lengthOfReplication <= 0.0) add("Length of replication must be > 0.")
        if (lengthOfReplicationWarmUp != null && lengthOfReplicationWarmUp < 0.0) add("Warm-up must be >= 0.")
    }

    /** Builds the single scenario; requires [validate] to be empty. */
    fun toScenarioSpec(bundleId: String, modelId: String): ScenarioSpec {
        check(validate().isEmpty()) { "recipe draft is not valid: ${validate()}" }
        val overrides = ExperimentRunOverrides(
            numberOfReplications = numberOfReplications,
            lengthOfReplication = lengthOfReplication,
            lengthOfReplicationWarmUp = lengthOfReplicationWarmUp,
            antitheticOption = antithetic,
        )
        return ScenarioSpec(
            name = name,
            modelReference = ModelReference.ByBundleAndModelId(bundleId, modelId),
            runOverrides = if (overrides.isEmpty) null else overrides,
        )
    }

    /** Builds the single-scenario [RunConfiguration]; requires [validate] to be empty. */
    fun toRunConfiguration(bundleId: String, modelId: String): RunConfiguration =
        RunConfiguration(scenarios = listOf(toScenarioSpec(bundleId, modelId)))
}
