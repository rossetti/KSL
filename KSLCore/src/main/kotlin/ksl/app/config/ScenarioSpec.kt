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
 * Serialisable specification for a single scenario in a scenario-sweep run.
 *
 * Lives inside [RunConfiguration.scenarios].  In Phase 2 this type is inert data
 * carried in the document for forward-compatibility with the Phase 5
 * `ScenarioOrchestrator`; no scenario-execution logic is added here.
 *
 * [ksl.controls.experiments.Scenario] is not serialisable because it holds a live
 * [ksl.simulation.ModelBuilderIfc] reference.  [ScenarioSpec] is the serialisable
 * counterpart: it stores only the inputs needed to construct a `Scenario` at run time,
 * reusing the parent [RunConfiguration.modelReference] for model construction.
 *
 * Each spec overrides run parameters and inputs relative to the parent
 * [RunConfiguration]; fields that default to empty are inherited from the parent.
 *
 * @property name              unique scenario name within [RunConfiguration.scenarios]
 * @property runParameters     run parameters for this scenario; overrides the parent
 *                             [RunConfiguration.experimentRunParameters]
 * @property controls          control overrides for this scenario; an empty export (the
 *                             default) leaves the parent's controls unchanged
 * @property rvOverrides       RV parameter overrides for this scenario; an empty list
 *                             (the default) leaves the parent's RV parameters unchanged
 * @property modelConfiguration optional `Map<String, String>` forwarded to
 *                             [ksl.simulation.ModelBuilderIfc.build]; `null` inherits
 *                             from the parent [RunConfiguration]
 */
@Serializable
data class ScenarioSpec(
    val name: String,
    val runParameters: ExperimentRunParameters,
    val controls: ModelControlsExport = ModelControlsExport(modelName = ""),
    val rvOverrides: List<RVParameterOverride> = emptyList(),
    val modelConfiguration: Map<String, String>? = null
)
