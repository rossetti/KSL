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

/**
 * Serializable model-construction template for app-layer workflows that need
 * configured model instances without depending on a full [RunConfiguration].
 *
 * This type contains only persisted data. It does not hold a live
 * [ksl.simulation.ModelBuilderIfc] or [ksl.simulation.ModelProviderIfc].
 * Execution code should resolve this template into an internal configured
 * [ksl.simulation.ModelBuilderIfc] before handing work to lower-level
 * simulation or optimization APIs.
 *
 * [ModelRunTemplate] is intentionally narrower than [RunConfiguration]. It
 * captures the reusable baseline model-building pieces needed by workflows
 * such as simulation optimization, where the model source and fixed baseline
 * configuration are separate from the optimization problem and solver settings.
 *
 * @property modelReference serializable pointer to the model source. This is
 *                          either [ModelReference.ByProviderId] or
 *                          [ModelReference.ByJar], so persisted documents do
 *                          not contain live builder objects.
 * @property modelConfiguration optional builder-level string configuration
 *                              passed into
 *                              [ksl.simulation.ModelBuilderIfc.build]. This is
 *                              distinct from controls and RV overrides because
 *                              some models may need structured build-time
 *                              configuration before model elements exist.
 * @property runParameters baseline simulation experiment settings for models
 *                         built from this template. A configured builder should
 *                         use these as defaults while allowing call-site
 *                         [ksl.simulation.ExperimentRunParametersIfc] values
 *                         to override them for a specific build.
 * @property controls fixed model control overrides applied after the model is
 *                    built. An empty export leaves the model's control defaults
 *                    unchanged.
 * @property rvOverrides fixed random-variable parameter overrides applied after
 *                       controls. An empty list leaves the model's random
 *                       variable parameter defaults unchanged.
 */
@Serializable
data class ModelRunTemplate(
    val modelReference: ModelReference,
    val modelConfiguration: Map<String, String>? = null,
    val runParameters: ExperimentRunParameters,
    val controls: ModelControlsExport = ModelControlsExport(modelName = ""),
    val rvOverrides: List<RVParameterOverride> = emptyList()
) {
    init {
        require(modelConfiguration == null || modelConfiguration.keys.all { it.isNotBlank() }) {
            "every key in modelConfiguration must be non-blank when modelConfiguration is non-null"
        }
    }
}
