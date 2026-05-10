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

package ksl.app.config

import kotlinx.serialization.Serializable
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunParameters
import ksl.simulation.Model
import ksl.simulation.ModelProviderIfc
import ksl.utilities.io.JARModelBuilder
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * Serialisable input directive for a simulation run.
 *
 * [RunConfiguration] is the *input* counterpart to [ksl.simulation.ModelDescriptor],
 * which is the *output snapshot*.  Both types carry [ModelControlsExport] and
 * [ExperimentRunParameters], but they serve different roles:
 *
 * - [ksl.simulation.ModelDescriptor] records a complete snapshot of a model after it
 *   has been built, for archiving and inspection.
 * - [RunConfiguration] specifies what you *want* to build and run; it may contain only
 *   the overrides that differ from the model's compiled defaults, and it drives
 *   [buildModel] rather than being produced by a model.
 *
 * ## Typical workflow
 *
 * ```kotlin
 * // Load from TOML file:
 * val config  = RunConfigurationToml.decode(File("mm1.toml").readText())
 *
 * // Build and configure the model:
 * val provider: ModelProviderIfc = MapModelProvider("MM1", Mm1Builder)
 * val model   = config.buildModel(provider)
 *
 * // Submit to the Phase 1 run driver:
 * val handle  = Runner().submit(RunRequest.SingleRun(model))
 * val result  = handle.result.await()
 * ```
 *
 * ## Codecs
 *
 * Use [RunConfigurationJson] for JSON persistence and [RunConfigurationToml] for TOML.
 * Both operate on this type via the same `@Serializable` annotations — no separate
 * DTO is needed.
 *
 * ## Note on `experimentId`
 *
 * [ExperimentRunParameters.experimentId] is a runtime-assigned identifier that KSL
 * generates when the experiment is created.  When a [RunConfiguration] is built from
 * a reference model via `model.extractRunParameters()`, the captured `experimentId`
 * belongs to that reference model instance.  [buildModel] applies it unchanged to the
 * newly built model via `changeRunParameters()`, which is harmless but means the built
 * model will carry the reference model's id.  Phase 3 validation may warn on this if
 * id uniqueness becomes a concern.
 *
 * @property modelReference          identifies the model source; see [ModelReference]
 * @property experimentRunParameters run parameters for the experiment (replications,
 *                                   length, warm-up, etc.)
 * @property controls                control overrides applied after model construction;
 *                                   an empty export (the default) leaves model defaults unchanged
 * @property rvOverrides             RV parameter overrides applied after controls;
 *                                   an empty list (the default) leaves model defaults unchanged
 * @property scenarios               serialisable scenario specs consumed by
 *                                   `ksl.app.RunSpec.Scenarios`; ignored by
 *                                   `ksl.app.RunSpec.Single`
 * @property simoptProblemId         optional problem id metadata for optimisation
 *                                   configs consumed by `ksl.app.RunSpec.Optimization`;
 *                                   it is not used for automatic routing
 * @property tracingConfig           animation trace capture settings; defaults to disabled
 *                                   (`animationTraceFile = null`)
 */
@Serializable
data class RunConfiguration(
    val modelReference: ModelReference,
    val experimentRunParameters: ExperimentRunParameters,
    val controls: ModelControlsExport = ModelControlsExport(modelName = ""),
    val rvOverrides: List<RVParameterOverride> = emptyList(),
    val scenarios: List<ScenarioSpec> = emptyList(),
    val simoptProblemId: String? = null,
    val tracingConfig: TracingConfig = TracingConfig()
) {
    init {
        require(simoptProblemId == null || simoptProblemId.isNotBlank()) {
            "simoptProblemId must be non-blank when non-null"
        }
        require(scenarios.map { it.name }.toSet().size == scenarios.size) {
            "scenarios must have unique names"
        }
    }

    /**
     * Constructs a fully configured [Model] from this [RunConfiguration].
     *
     * Steps performed in order:
     * 1. Resolve [modelReference] to a [Model]:
     *    - [ModelReference.ByProviderId] → delegates to [provider]
     *    - [ModelReference.ByJar]        → creates a [JARModelBuilder] and calls `build()`;
     *      the loader is closed immediately after `build()` returns (`.use {}`)
     * 2. Apply [experimentRunParameters] via `Model.changeRunParameters()`.
     * 3. Apply [controls] via `model.controls().importAll(controls)` — skipped when
     *    [ModelControlsExport.totalControls] == 0.
     * 4. Apply [rvOverrides] via [RVParameterSetter.changeParameters] — skipped when
     *    [rvOverrides] is empty.
     *
     * Phase 3 will add structured pre-flight validation (`ksl.app.validation.ValidationResult`)
     * before this method is called.  Until then, unresolvable references or unrecognised
     * control/RV keys throw [IllegalArgumentException] from the underlying KSL APIs.
     *
     * @param provider required when [modelReference] is [ModelReference.ByProviderId];
     *                 unused (and may be `null`) for [ModelReference.ByJar]
     * @return a [Model] ready to be submitted to [ksl.app.session.Runner.submit]
     * @throws IllegalArgumentException if the provider id is not registered, the JAR path
     *         is invalid, or a control/RV key is not recognised by the model
     * @throws IllegalStateException if [modelReference] is [ModelReference.ByProviderId]
     *         and [provider] is `null`
     */
    fun buildModel(provider: ModelProviderIfc? = null): Model {
        val model: Model = when (val ref = modelReference) {
            is ModelReference.ByProviderId -> {
                requireNotNull(provider) {
                    "A ModelProviderIfc must be supplied when modelReference is " +
                    "ByProviderId(\"${ref.providerId}\")"
                }
                provider.provideModel(ref.providerId)
            }
            is ModelReference.ByJar ->
                JARModelBuilder(ref.jarPath, ref.builderClassName).use { it.build() }
        }
        model.changeRunParameters(experimentRunParameters)
        if (controls.totalControls > 0) {
            model.controls().importAll(controls)
        }
        if (rvOverrides.isNotEmpty()) {
            val paramMap = rvOverrides
                .groupBy { it.rvName }
                .mapValues { (_, list) -> list.associate { it.paramName to it.value } }
            // changeParameters mutates only the in-memory RVParameters snapshot;
            // applyParameterChanges writes those changes back to the live RandomVariable
            // instances by reassigning rv.initialRandomSource.
            val setter = RVParameterSetter(model)
            setter.changeParameters(paramMap)
            setter.applyParameterChanges(model)
        }
        return model
    }
}
