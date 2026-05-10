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

package ksl.app.config.optimization

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import ksl.utilities.io.JARModelBuilder
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * App-layer [ModelBuilderIfc] implementation that builds a configured
 * [Model] from a [ModelRunTemplate].
 *
 * `ConfiguredModelBuilder` is the bridge between the persisted, app-layer
 * [ModelRunTemplate] and the engine-layer factories that consume a
 * `ModelBuilderIfc` (notably [ksl.simopt.solvers.Solver]'s `create…`
 * factories and [ksl.simopt.evaluator.Evaluator.createProblemEvaluator]).
 *
 * Construction is internal because the only legitimate consumer of this
 * type today is [OptimizationSolverFactory]; users should not instantiate
 * it directly.
 *
 * ## Override semantics
 *
 * The engine factories pass `modelConfiguration` and
 * `experimentRunParameters` through to [build] when constructing the
 * evaluator's probe model.  This implementation treats those parameters as
 * **overrides**:
 *
 * - if [modelConfiguration] is non-null, it is used; otherwise
 *   [ModelRunTemplate.modelConfiguration] is used;
 * - if [experimentRunParameters] is non-null, it is used; otherwise
 *   [ModelRunTemplate.runParameters] is used.
 *
 * After the model is built from the appropriate source, the template's
 * baseline [ModelRunTemplate.controls] and [ModelRunTemplate.rvOverrides]
 * are applied — matching
 * [ksl.app.config.RunConfiguration.buildModel]'s pattern.
 *
 * ## JAR resolution
 *
 * For [ModelReference.ByJar], a [JARModelBuilder] is opened, used, and
 * closed inside this method via `.use { }`.  The evaluator retains the
 * built [Model] but no reference to the loader, so the loader can be
 * released immediately.
 *
 * @property template the persisted model-construction template
 * @property provider required when [ModelRunTemplate.modelReference] is
 *           [ModelReference.ByProviderId]; ignored for [ModelReference.ByJar]
 */
internal class ConfiguredModelBuilder(
    private val template: ModelRunTemplate,
    private val provider: ModelProviderIfc?
) : ModelBuilderIfc {

    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val effectiveConfig: Map<String, String>? =
            modelConfiguration ?: template.modelConfiguration
        val effectiveRunParams: ExperimentRunParametersIfc =
            experimentRunParameters ?: template.runParameters

        val model: Model = when (val ref = template.modelReference) {
            is ModelReference.ByProviderId -> {
                requireNotNull(provider) {
                    "A ModelProviderIfc must be supplied when modelReference is ByProviderId(\"${ref.providerId}\")"
                }
                provider.provideModel(ref.providerId, effectiveConfig, effectiveRunParams)
            }
            is ModelReference.ByJar ->
                JARModelBuilder(ref.jarPath, ref.builderClassName).use {
                    it.build(effectiveConfig, effectiveRunParams)
                }
        }

        if (template.controls.totalControls > 0) {
            model.controls().importAll(template.controls)
        }
        if (template.rvOverrides.isNotEmpty()) {
            val paramMap = template.rvOverrides
                .groupBy { it.rvName }
                .mapValues { (_, list) -> list.associate { it.paramName to it.value } }
            val setter = RVParameterSetter(model)
            setter.changeParameters(paramMap)
            setter.applyParameterChanges(model)
        }
        return model
    }
}
