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

import ksl.app.bundle.ConfigRecipeKind
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.ExperimentConfigurationToml
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationRunConfigurationJson
import ksl.app.config.optimization.OptimizationRunConfigurationToml

/**
 * Headless logic for importing an existing app-authored configuration file
 * (a Single/Scenario, Experiment, or Optimization document) as a bundle
 * **recipe** targeted at one bundled model.
 *
 * This is the reusable, testable core behind the Bundle Workbench's recipe
 * import wizard.  It carries no Swing dependency: it operates on raw bytes
 * and the `ksl.app.config.*` document types, reusing their existing TOML/JSON
 * codecs.  The wizard (and any future MCP/CLI surface) drives it in two steps:
 *
 *  1. [summarize] the chosen file — auto-detect its kind and format and list
 *     what models it references (per-scenario for Single/Scenario documents).
 *     The UI presents this so the author understands what they are importing
 *     before committing.
 *  2. [importForModel] — filter (Scenario documents only) to the scenarios the
 *     author kept, optionally **retarget** the selected model references to the
 *     bundled `(bundleId, modelId)`, and re-encode to recipe bytes.
 *
 * ## Why retargeting matters
 *
 * App-authored configuration files reference their model by whatever pointer
 * the originating app used — typically [ModelReference.ByProviderId],
 * [ModelReference.ByJar], or [ModelReference.Embedded], or a
 * [ModelReference.ByBundleAndModelId] pointing at *some other* bundle.  A
 * recipe shipped inside a bundle should instead point at the model it is filed
 * under, so it resolves against the enclosing bundle when a consuming app
 * extracts and runs it.  Retargeting rewrites those references to
 * `ByBundleAndModelId(bundleId, modelId)` and is **on by default** in the
 * wizard — it is the difference between a recipe that "just works" inside the
 * bundle and one that dangles against an external pointer.
 *
 * ## Scenario filtering and resulting kind
 *
 * A Single/Scenario document may carry scenarios spanning several models.  When
 * importing it as a recipe filed under one model, the author keeps the relevant
 * scenarios; the rest are dropped.  The resulting recipe kind follows the count
 * of kept scenarios: exactly one → [ConfigRecipeKind.RUN]; more than one →
 * [ConfigRecipeKind.SCENARIO_BATCH].  Per-scenario [ScenarioSpec.runOverrides],
 * control/RV overrides, and flags are preserved verbatim — only the
 * `modelReference` is rewritten (and only when retargeting).
 *
 * Experiment and Optimization documents bind exactly one model; there is no
 * scenario filtering for them and retargeting rewrites the single reference.
 */
object RecipeImport {

    /** Serialised text format of a configuration document. */
    enum class Format { TOML, JSON }

    /**
     * A single scenario as seen during [summarize].
     *
     * @property index      position of the scenario in the document (stable key
     *                      used by [importForModel] to select scenarios)
     * @property name       the scenario's name
     * @property modelId    the in-bundle/provider model id the scenario points
     *                      at, or `null` when the reference carries no comparable
     *                      id (a `ByJar` or `Embedded` reference)
     * @property bundleId   the bundle id when the reference is
     *                      [ModelReference.ByBundleAndModelId]; `null` otherwise
     * @property referenceType  the reference's serialized `type` tag, for display
     *                      (e.g. `"byProviderId"`, `"byJar"`)
     * @property skipOnRun  the scenario's skip-on-run flag
     */
    data class ScenarioRef(
        val index: Int,
        val name: String,
        val modelId: String?,
        val bundleId: String?,
        val referenceType: String,
        val skipOnRun: Boolean,
    )

    /**
     * What a configuration file contains, surfaced for the import UI before
     * any transformation is applied.
     *
     * @property detected   `true` when the bytes decoded to a known document
     *                      type; when `false`, only [error] is meaningful
     * @property kind       the detected recipe kind, or `null` when undetected
     * @property format     the detected text format, or `null` when undetected
     * @property analysisName the document's analysis/output name, when it
     *                      carries one (for a human-friendly default recipe name)
     * @property scenarios  per-scenario detail for Single/Scenario documents;
     *                      empty for Experiment and Optimization documents
     * @property referencedModelIds  distinct, order-preserving list of the model
     *                      ids the document references (a `null` element means at
     *                      least one reference carried no comparable id)
     * @property error      the decode failure message when [detected] is `false`
     */
    data class RecipeSummary(
        val detected: Boolean,
        val kind: ConfigRecipeKind?,
        val format: Format?,
        val analysisName: String?,
        val scenarios: List<ScenarioRef>,
        val referencedModelIds: List<String?>,
        val error: String?,
    )

    /** The product of [importForModel]: bytes ready to add as a bundle recipe. */
    data class ImportResult(
        /** A suggested recipe name; callers may override it. */
        val suggestedName: String,
        val kind: ConfigRecipeKind,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ImportResult &&
                suggestedName == other.suggestedName &&
                kind == other.kind &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            (suggestedName.hashCode() * 31 + kind.hashCode()) * 31 + bytes.contentHashCode()
    }

    /** A decoded document plus the format it came from (internal detection result). */
    private sealed interface Decoded {
        val format: Format

        data class Run(val config: RunConfiguration, override val format: Format) : Decoded
        data class Experiment(val config: ExperimentConfiguration, override val format: Format) : Decoded
        data class Optimization(val config: OptimizationRunConfiguration, override val format: Format) : Decoded
    }

    /**
     * Inspect [bytes] without modifying anything and report what the file is.
     *
     * Auto-detects across all supported document types and both text formats.
     * Detection is robust because the codecs reject unknown keys: an
     * Optimization document does not silently decode as an (all-defaults)
     * Single document.  A Single/Scenario document is only accepted when it
     * carries at least one scenario, so a structurally-empty file is reported
     * as undetected rather than as an empty run.
     */
    fun summarize(bytes: ByteArray): RecipeSummary {
        val text = bytes.decodeToString()
        val decoded = detect(text)
            ?: return RecipeSummary(
                detected = false, kind = null, format = null, analysisName = null,
                scenarios = emptyList(), referencedModelIds = emptyList(),
                error = "the file did not decode as a Single/Scenario, Experiment, or Optimization configuration",
            )
        return when (decoded) {
            is Decoded.Run -> {
                val cfg = decoded.config
                val scenarios = cfg.scenarios.mapIndexed { i, s ->
                    ScenarioRef(
                        index = i,
                        name = s.name,
                        modelId = modelIdOf(s.modelReference),
                        bundleId = bundleIdOf(s.modelReference),
                        referenceType = referenceTypeOf(s.modelReference),
                        skipOnRun = s.skipOnRun,
                    )
                }
                RecipeSummary(
                    detected = true,
                    kind = if (scenarios.size == 1) ConfigRecipeKind.RUN else ConfigRecipeKind.SCENARIO_BATCH,
                    format = decoded.format,
                    analysisName = cfg.outputConfig.analysisName,
                    scenarios = scenarios,
                    referencedModelIds = scenarios.map { it.modelId }.distinct(),
                    error = null,
                )
            }
            is Decoded.Experiment -> {
                val ref = decoded.config.modelReference
                RecipeSummary(
                    detected = true,
                    kind = ConfigRecipeKind.EXPERIMENT,
                    format = decoded.format,
                    analysisName = decoded.config.outputConfig.analysisName,
                    scenarios = emptyList(),
                    referencedModelIds = listOf(modelIdOf(ref)),
                    error = null,
                )
            }
            is Decoded.Optimization -> {
                val ref = decoded.config.model.modelReference
                RecipeSummary(
                    detected = true,
                    kind = ConfigRecipeKind.OPTIMIZATION,
                    format = decoded.format,
                    analysisName = decoded.config.output.analysisName,
                    scenarios = emptyList(),
                    referencedModelIds = listOf(modelIdOf(ref)),
                    error = null,
                )
            }
        }
    }

    /**
     * Produce recipe bytes for the bundled `(bundleId, modelId)` from an
     * existing configuration file.
     *
     * For Single/Scenario documents:
     *  - keeps only the scenarios in [keepScenarioIndices] (when `null`, keeps
     *    all scenarios),
     *  - when [retarget] is `true`, rewrites every kept scenario's
     *    `modelReference` to `ByBundleAndModelId(bundleId, modelId)`,
     *  - prunes [RunConfiguration.bundleRefs] to those still referenced,
     *  - the resulting kind is [ConfigRecipeKind.RUN] for one kept scenario,
     *    else [ConfigRecipeKind.SCENARIO_BATCH].
     *
     * For Experiment and Optimization documents, [keepScenarioIndices] is
     * ignored; when [retarget] is `true` the single model reference is
     * rewritten.
     *
     * The recipe is re-encoded in the file's original [Format].
     *
     * @throws IllegalArgumentException if the bytes do not decode to a known
     *         document type, or if filtering a Scenario document would keep no
     *         scenarios.
     */
    fun importForModel(
        bytes: ByteArray,
        bundleId: String,
        modelId: String,
        keepScenarioIndices: Set<Int>? = null,
        retarget: Boolean = true,
    ): ImportResult {
        val text = bytes.decodeToString()
        val decoded = detect(text)
            ?: throw IllegalArgumentException(
                "the file did not decode as a Single/Scenario, Experiment, or Optimization configuration"
            )
        return when (decoded) {
            is Decoded.Run -> {
                var cfg = decoded.config
                if (keepScenarioIndices != null) cfg = filterToScenarios(cfg, keepScenarioIndices)
                require(cfg.scenarios.isNotEmpty()) { "the selection keeps no scenarios" }
                if (retarget) cfg = retarget(cfg, bundleId, modelId, scenarioIndices = null)
                cfg = pruneBundleRefs(cfg)
                val kind = if (cfg.scenarios.size == 1) ConfigRecipeKind.RUN else ConfigRecipeKind.SCENARIO_BATCH
                val out = when (decoded.format) {
                    Format.TOML -> RunConfigurationToml.encode(cfg)
                    Format.JSON -> RunConfigurationJson.encode(cfg)
                }
                ImportResult(
                    suggestedName = cfg.outputConfig.analysisName.ifBlank { modelId },
                    kind = kind,
                    bytes = out.toByteArray(),
                )
            }
            is Decoded.Experiment -> {
                var cfg = decoded.config
                if (retarget) cfg = retarget(cfg, bundleId, modelId)
                // Experiment has TOML codec only.
                ImportResult(
                    suggestedName = cfg.outputConfig.analysisName.ifBlank { modelId },
                    kind = ConfigRecipeKind.EXPERIMENT,
                    bytes = ExperimentConfigurationToml.encode(cfg).toByteArray(),
                )
            }
            is Decoded.Optimization -> {
                var cfg = decoded.config
                if (retarget) cfg = retarget(cfg, bundleId, modelId)
                val out = when (decoded.format) {
                    Format.TOML -> OptimizationRunConfigurationToml.encode(cfg)
                    Format.JSON -> OptimizationRunConfigurationJson.encode(cfg)
                }
                ImportResult(
                    suggestedName = cfg.output.analysisName.ifBlank { modelId },
                    kind = ConfigRecipeKind.OPTIMIZATION,
                    bytes = out.toByteArray(),
                )
            }
        }
    }

    // ---- Run-configuration transforms (public for fine-grained reuse/testing) ----

    /**
     * Returns [run] keeping only the scenarios whose index is in
     * [keepScenarioIndices], preserving their original order.  Indices outside
     * the scenario range are ignored.
     */
    fun filterToScenarios(run: RunConfiguration, keepScenarioIndices: Set<Int>): RunConfiguration {
        val kept = run.scenarios.filterIndexed { i, _ -> i in keepScenarioIndices }
        return run.copy(scenarios = kept)
    }

    /**
     * Returns [run] with the `modelReference` of the selected scenarios rewritten
     * to `ByBundleAndModelId(bundleId, modelId)`.  When [scenarioIndices] is
     * `null`, every scenario is retargeted; otherwise only those whose index is
     * listed.  All other scenario fields are preserved verbatim.
     */
    fun retarget(
        run: RunConfiguration,
        bundleId: String,
        modelId: String,
        scenarioIndices: Set<Int>? = null,
    ): RunConfiguration {
        val ref = ModelReference.ByBundleAndModelId(bundleId, modelId)
        val rewritten = run.scenarios.mapIndexed { i, s ->
            if (scenarioIndices == null || i in scenarioIndices) s.copy(modelReference = ref) else s
        }
        return run.copy(scenarios = rewritten)
    }

    /** Returns [config] with its single model reference retargeted to the bundled model. */
    fun retarget(config: ExperimentConfiguration, bundleId: String, modelId: String): ExperimentConfiguration =
        config.copy(modelReference = ModelReference.ByBundleAndModelId(bundleId, modelId))

    /** Returns [config] with its single model reference retargeted to the bundled model. */
    fun retarget(config: OptimizationRunConfiguration, bundleId: String, modelId: String): OptimizationRunConfiguration =
        config.copy(model = config.model.copy(modelReference = ModelReference.ByBundleAndModelId(bundleId, modelId)))

    // ---- internals ----

    /**
     * Drops [BundleRef] entries no longer pointed at by any scenario's
     * `ByBundleAndModelId` reference, so an imported recipe does not carry stale
     * bundle pointers.
     */
    private fun pruneBundleRefs(run: RunConfiguration): RunConfiguration {
        if (run.bundleRefs.isEmpty()) return run
        val referenced = run.scenarios
            .mapNotNull { (it.modelReference as? ModelReference.ByBundleAndModelId)?.bundleId }
            .toSet()
        val kept = run.bundleRefs.filter { it.bundleId in referenced }
        return if (kept.size == run.bundleRefs.size) run else run.copy(bundleRefs = kept)
    }

    /**
     * Try every supported (type, format) combination and return the first that
     * decodes.  Single/Scenario documents are accepted only when they carry at
     * least one scenario, so an all-defaults decode of some other document type
     * cannot masquerade as an empty run.  TOML is tried before JSON because the
     * preferred hand-authored format is TOML.
     */
    private fun detect(text: String): Decoded? {
        // Run / Scenario
        tryDecode { RunConfigurationToml.decode(text) }?.takeIf { it.scenarios.isNotEmpty() }
            ?.let { return Decoded.Run(it, Format.TOML) }
        tryDecode { RunConfigurationJson.decode(text) }?.takeIf { it.scenarios.isNotEmpty() }
            ?.let { return Decoded.Run(it, Format.JSON) }
        // Experiment (TOML only)
        tryDecode { ExperimentConfigurationToml.decode(text) }
            ?.let { return Decoded.Experiment(it, Format.TOML) }
        // Optimization
        tryDecode { OptimizationRunConfigurationToml.decode(text) }
            ?.let { return Decoded.Optimization(it, Format.TOML) }
        tryDecode { OptimizationRunConfigurationJson.decode(text) }
            ?.let { return Decoded.Optimization(it, Format.JSON) }
        return null
    }

    private inline fun <T> tryDecode(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            null
        }

    private fun modelIdOf(ref: ModelReference): String? = when (ref) {
        is ModelReference.ByBundleAndModelId -> ref.modelId
        is ModelReference.ByProviderId -> ref.providerId
        is ModelReference.Embedded -> ref.modelName
        is ModelReference.ByJar -> null
    }

    private fun bundleIdOf(ref: ModelReference): String? =
        (ref as? ModelReference.ByBundleAndModelId)?.bundleId

    private fun referenceTypeOf(ref: ModelReference): String = when (ref) {
        is ModelReference.ByBundleAndModelId -> "byBundleAndModelId"
        is ModelReference.ByProviderId -> "byProviderId"
        is ModelReference.ByJar -> "byJar"
        is ModelReference.Embedded -> "embedded"
    }
}
