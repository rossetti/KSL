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

package ksl.service.capability.run

import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.controls.ModelControlsExport
import ksl.simulation.ModelDescriptor

/**
 * Generates ready-to-edit config-document *scaffolds* — the third layer of the
 * authoring stack (Phase 8 plan §3.2): when a model ships no author recipe, the
 * server synthesizes a complete, runnable document of the requested shape with
 * the model's controls and run parameters pre-filled at their defaults. The
 * agent edits the values it cares about and submits the document.
 */
object RunTemplates {

    /**
     * A single-scenario [RunConfiguration] scaffold for [modelId]: the model's
     * run parameters at their declared defaults and its numeric controls
     * surfaced at their current values, so the document shows every knob the
     * agent can turn. Reports are suppressed (headless default).
     */
    fun runDocument(descriptor: ModelDescriptor, modelId: String): RunConfiguration {
        val defaults = descriptor.experimentRunDefaults
        val runOverrides = ExperimentRunOverrides(
            numberOfReplications = defaults.numberOfReplications,
            lengthOfReplication = defaults.lengthOfReplication,
            lengthOfReplicationWarmUp = defaults.lengthOfReplicationWarmUp,
        )
        val controls = ModelControlsExport(
            modelName = descriptor.modelName,
            numericControls = descriptor.controls.numericControls,
        )
        return RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = modelId,
                    modelReference = ModelReference.ByProviderId(modelId),
                    runOverrides = runOverrides,
                    controlOverrides = controls,
                ),
            ),
            outputConfig = OutputConfig(reports = emptySet()),
        )
    }
}
