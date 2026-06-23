package ksl.app.config

import ksl.controls.experiments.ExperimentRunParameters

/**
 * Test-only convenience builders that adapt the legacy single-model
 * RunConfiguration shape (Phase 2) to the reshaped Phase-6B shape
 * (self-contained ScenarioSpec carrying its own ModelReference and
 * ExperimentRunOverrides).
 *
 * These helpers exist so test files can express their intent
 * compactly without restating the boilerplate that the reshape
 * introduced at every construction site.
 */

/**
 * Builds a `RunConfiguration` containing exactly one `ScenarioSpec` for
 * the given `modelId` (resolved by-provider-id), with [params] folded
 * into the scenario's `runOverrides`.  The scenario's name defaults to
 * [params].experimentName if non-blank, else `modelId`.
 */
internal fun singleScenarioConfig(
    modelId: String,
    params: ExperimentRunParameters,
    scenarioName: String = params.experimentName.ifBlank { modelId }
): RunConfiguration = RunConfiguration(
    scenarios = listOf(
        ScenarioSpec(
            name = scenarioName,
            modelReference = ModelReference.ByProviderId(modelId),
            runOverrides = params.toOverrides()
        )
    )
)

/**
 * Builds a `RunConfiguration` containing the given list of scenarios,
 * each of which already carries its own `ModelReference`.  No
 * bundleRefs are declared — programmatic tests that use
 * `ByProviderId` or `ByJar` don't need them.
 */
internal fun multiScenarioConfig(
    scenarios: List<ScenarioSpec>
): RunConfiguration = RunConfiguration(scenarios = scenarios)

/**
 * Builds a `ScenarioSpec` for a `ByProviderId` model resolution,
 * folding [params] into `runOverrides`.  Convenience for test sites
 * that produce many scenarios over one model.
 */
internal fun scenarioFor(
    modelId: String,
    name: String,
    params: ExperimentRunParameters,
    controlOverrides: ksl.controls.ModelControlsExport = ksl.controls.ModelControlsExport(modelName = ""),
    rvOverrides: List<RVParameterOverride> = emptyList()
): ScenarioSpec = ScenarioSpec(
    name = name,
    modelReference = ModelReference.ByProviderId(modelId),
    runOverrides = params.toOverrides(),
    controlOverrides = controlOverrides,
    rvOverrides = rvOverrides
)
