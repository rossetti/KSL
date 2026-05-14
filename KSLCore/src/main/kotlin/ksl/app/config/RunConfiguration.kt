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

/**
 * Serialisable scenarios document — the input directive for a
 * `ksl.app.RunSpec.Scenarios` (or `RunSpec.Single`) submission.
 *
 * The document is a thin container of two lists:
 *
 * - [bundleRefs] — every bundle JAR the document depends on, each
 *   carrying an authoritative `bundleId` plus an ordered list of
 *   candidate filesystem paths.  Resolved at open time by the
 *   consumer (the Scenario app, a programmatic caller, etc.) into
 *   loaded bundles before submission.
 * - [scenarios]  — zero or more named scenarios.  Each scenario is
 *   self-contained: it carries its own [ModelReference], its own
 *   partial overrides on the model's defaults, and its own
 *   skip-on-run flag.  See [ScenarioSpec] for the per-scenario
 *   shape.
 *
 * There are **no document-level model defaults**.  The earlier
 * design tied the whole document to one model (`modelReference`) and
 * one set of run parameters (`experimentRunParameters`) that
 * scenarios inherited.  The reshape removes both: each scenario
 * picks its own model, and runtime [ksl.controls.experiments.ExperimentRunParameters]
 * is computed at submit time as
 * `model.extractRunParameters() + scenario.runOverrides.applyTo(...)`
 * with `experimentName` set to the scenario's name.  Symmetric with
 * how controls already override per key.
 *
 * ## RunSpec.Single
 *
 * A "single run" is a `RunConfiguration` with exactly one
 * `ScenarioSpec`.  The session dispatches to
 * `SingleRunOrchestrator` when wrapped in `RunSpec.Single`, and to
 * `ScenarioOrchestrator` when wrapped in `RunSpec.Scenarios`; both
 * orchestrators consume the same document shape.  The orchestrators
 * themselves enforce the size constraint (`RunSpec.Single` requires
 * exactly one scenario; `RunSpec.Scenarios` requires at least one).
 *
 * ## Not for optimization runs
 *
 * Simulation-optimization runs use a separate top-level type,
 * `ksl.app.config.optimization.OptimizationRunConfiguration`, which
 * composes a `ksl.app.config.ModelRunTemplate` for the model side
 * with the optimization problem and solver specs.  Submit either
 * through `KSLAppSession`; the session dispatches by `RunSpec`
 * variant.
 *
 * ## Codecs
 *
 * Use `RunConfigurationJson` for JSON persistence and
 * `RunConfigurationToml` for TOML.  Both operate on this type via
 * the same `@Serializable` annotations.
 *
 * @property bundleRefs   bundle JARs the scenarios reference; may be
 *                        empty when no scenario uses a
 *                        bundle-backed reference (e.g. a document
 *                        consisting entirely of `ByJar` or
 *                        `ByProviderId` scenarios authored
 *                        programmatically).
 * @property scenarios    the scenarios to run.  Must have unique
 *                        names within the document.
 * @property tracingConfig animation trace capture settings;
 *                        document-wide.
 */
@Serializable
data class RunConfiguration(
    val bundleRefs: List<BundleRef> = emptyList(),
    val scenarios: List<ScenarioSpec> = emptyList(),
    val tracingConfig: TracingConfig = TracingConfig()
) {
    init {
        require(scenarios.map { it.name }.toSet().size == scenarios.size) {
            "scenarios must have unique names"
        }
        require(bundleRefs.map { it.bundleId }.toSet().size == bundleRefs.size) {
            "bundleRefs must have unique bundleIds"
        }
    }
}
