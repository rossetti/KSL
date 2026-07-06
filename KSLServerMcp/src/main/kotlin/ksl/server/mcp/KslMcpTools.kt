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

package ksl.server.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ksl.animation.AnimationLayout
import ksl.animation.io.AnimationSource
import ksl.animation.replay.ReplayModel
import ksl.animation.replay.autoLayout
import ksl.animation.scaffoldLayout
import ksl.animation.validateAgainst
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationRunConfigurationJson
import ksl.service.capability.run.CacheVersion
import ksl.service.capability.run.ExperimentDocuments
import ksl.service.capability.run.IncrementalCombine
import ksl.service.capability.run.IncrementalRunCache
import ksl.service.preview.DocumentPreview
import ksl.service.capability.run.ExperimentFactorSpec
import ksl.service.capability.run.ResultKeys
import ksl.service.capability.run.RunInputs
import ksl.service.capability.run.RunTemplates
import ksl.service.store.ArtifactStore
import ksl.service.store.CachedResult
import ksl.service.store.ResultKind
import ksl.service.store.ResultStore
import ksl.service.store.StoredResult
import ksl.app.validation.ValidationResult
import ksl.app.dist.catalog.DistributionFamilyDescriptor
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.result.FitResultData
import ksl.app.dist.reporting.toDocument
import ksl.app.dist.session.FitResult
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.utilities.io.report.toHtml
import ksl.service.capability.fit.FitDocuments
import ksl.service.capability.fit.FitService
import ksl.service.capability.run.BundleRegistry
import ksl.service.capability.run.RunService
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.capability.run.dto.mapping.toDto
import ksl.service.capability.run.dto.mapping.withArtifacts
import ksl.service.capability.run.schema.SchemaTranslator
import ksl.service.config.ConfigDocuments
import ksl.service.config.ServerConfig
import ksl.service.job.JobAtCapacityException
import ksl.service.job.JobManager
import ksl.service.job.JobStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * The MCP tool logic for the run capability, backed by [KSLServiceCore]'s
 * [BundleRegistry] and [SchemaTranslator]. The logic lives here as plain
 * functions returning [CallToolResult] so it can be unit-tested directly,
 * independent of the MCP transport machinery; [KslMcpServer] merely registers
 * these on a `Server`.
 *
 * Run execution is JobManager-backed: runs submit through a long-lived
 * [JobManager] whose replayable event journal lets a client poll progress from
 * any offset (`submit_run` + `get_run_events` + `get_run_result`), independent
 * of when it asks. (The MCP SDK's tool-handler API exposes no push-notification
 * channel, so streaming is delivered by journal-backed polling rather than
 * server-pushed progress notifications.)
 *
 * The logic lives here as plain functions returning [CallToolResult] so it can
 * be unit-tested directly; [KslMcpServer] merely registers them on a `Server`.
 */
class KslMcpTools(
    private val registry: BundleRegistry,
    private val resultStore: ResultStore = ResultStore(),
    private val artifactStore: ArtifactStore = ArtifactStore(),
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true // ControlData bounds can be ±∞
    },
    // The shared per-user settings store (~/.ksl/settings.toml). Resolves the
    // active workspace the same way every other KSL app does, so MCP file
    // output lands beside theirs. Injectable so tests stay off the real ~/.ksl.
    private val settingsStore: ksl.app.settings.UserSettingsStore = ksl.app.settings.UserSettingsStore(),
    maxConcurrentJobs: Int = Runtime.getRuntime().availableProcessors(),
    runDeadline: kotlin.time.Duration? = null,
) : AutoCloseable {

    /** Hard cap on a single variate-generation request; guards against runaway samples. */
    private val MAX_VARIATES = 10_000

    /** Above this sample size the full sample is written to a CSV and the inline `values`
     *  carry only a leading preview, so a large request never returns a giant array. */
    private val INLINE_THRESHOLD = 1_000

    /**
     * Resolves `<activeWorkspace>/KSL_MCP_APPS/<sub>/`, creating it on demand. Every file-writing tool routes
     * through this so the server's artifacts (reports, data) land in the SAME app folder as its bundles and run
     * outputs (`ServerConfig.SERVER_APP_FOLDER`) — not a separate one. `~/.ksl/` stays settings-only.
     */
    private fun workspaceAppDir(sub: String): java.nio.file.Path {
        val appDir = ksl.app.session.AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), ServerConfig.SERVER_APP_FOLDER)
        val dir = appDir.resolve(sub)
        java.nio.file.Files.createDirectories(dir)
        return dir
    }

    // Scope for the JobManager's bookkeeping coroutines (collector / awaiter /
    // TTL). The RunService owns its own KSLAppSession scope (the simulation
    // dispatcher), kept separate so runs execute off this scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runService = RunService.fromRegistry(registry, runDeadline = runDeadline)
    private val runJobs = JobManager<RunEvent, RunResult>(scope, maxConcurrentJobs)
    private val reportArtifacts = ksl.service.capability.report.ReportArtifactService()
    private val resultDb = ksl.service.capability.dbanalysis.ResultDatabaseService()

    // Session-scoped raw fit observations (resultId -> data), bounded LRU. The fit
    // result keeps only summary stats, so get_fit_report needs the data to render
    // the diagnostic plots; it survives the session and degrades to a re-supplied
    // `data` argument otherwise.
    private val recentFitData: MutableMap<String, DoubleArray> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, DoubleArray>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, DoubleArray>): Boolean = size > 16
            },
        )

    /** `list_bundles` — every bundle the server makes available. */
    fun listBundles(): CallToolResult {
        val bundles = registry.listBundles()
        val skipped = registry.skipped()
        // bundleId collisions the registry resolved newest-wins (shadowed copies stay loaded,
        // inactive) — logged at startup, now surfaced so an agent can flag them to the operator.
        val conflicts = registry.conflicts()
        val structured = buildJsonObject {
            put("bundles", json.parseToJsonElement(json.encodeToString(bundles)))
            if (skipped.isNotEmpty()) {
                putJsonArray("skipped") {
                    skipped.forEach { add(buildJsonObject { put("jar", it.jar.toString()); put("reason", it.reason) }) }
                }
            }
            if (conflicts.isNotEmpty()) {
                putJsonArray("conflicts") {
                    conflicts.forEach { conflict ->
                        add(buildJsonObject {
                            put("bundleId", conflict.bundleId)
                            conflict.activeSource?.let { put("activeSource", it) }
                            putJsonArray("shadowedSources") { conflict.shadowedSources.forEach { s -> if (s != null) add(s) } }
                        })
                    }
                }
            }
        }
        val summary = buildString {
            if (bundles.isEmpty()) {
                append("No bundles available.")
            } else {
                appendLine("${bundles.size} bundle(s):")
                bundles.forEach { b ->
                    val note = b.notice?.let { " ($it)" } ?: ""
                    appendLine("  - ${b.bundleId} — models: ${b.modelIds.joinToString(", ")}$note")
                }
            }
            if (skipped.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Skipped ${skipped.size} JAR(s) (not loadable bundles — (re)assemble them):")
                skipped.forEach { appendLine("  - ${it.jar.fileName}: ${it.reason}") }
            }
            if (conflicts.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("${conflicts.size} bundle-id conflict(s) (newest-wins; shadowed copies stay loaded):")
                conflicts.forEach { appendLine("  - ${it.bundleId}: active=${it.activeSource}, shadowed=${it.shadowedSources}") }
            }
        }.trimEnd()
        return result(summary, structured)
    }

    /**
     * The live bundle catalog (id + model ids), reflecting any bundles loaded at
     * runtime (Phase 8.6). Used by the guided-workflow prompts to show the agent
     * a concrete menu of what is currently available.
     */
    internal fun availableBundles(): List<ksl.service.capability.run.BundleInfo> =
        registry.listBundles()

    /** `get_started` — the turn-one orientation tool: what the server can do, how to ask, and the
     *  live catalog. The text is single-sourced from [KslMcpPrompts.getStartedGuidance] (which the
     *  get_started prompt also uses) so guidance stays consistent; structuredContent carries the
     *  catalog so a client can machine-read it too. */
    fun getStarted(): CallToolResult {
        val bundles = availableBundles()
        val structured = buildJsonObject {
            put("bundles", json.parseToJsonElement(json.encodeToString(bundles)))
        }
        return result(KslMcpPrompts.getStartedGuidance(bundles), structured)
    }

    /** `list_models` — the models a bundle provides. */
    fun listModels(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId")
            ?: return error("missing required argument 'bundleId'")
        val models = registry.listModels(bundleId)
        if (models.isEmpty()) return error("no bundle '$bundleId', or it provides no models")
        val structured = buildJsonObject {
            put("bundleId", bundleId)
            putJsonArray("models") { models.forEach { add(it) } }
        }
        return result("Bundle $bundleId provides ${models.size} model(s): ${models.joinToString(", ")}", structured)
    }

    /**
     * `describe_model` — the agent-tool bridge: a model's responses plus JSON
     * Schemas for its run arguments and outputs, catalog-led when the model
     * nominates a catalog (via [SchemaTranslator]).
     */
    fun describeModel(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        val descriptor = try {
            registry.describeModel(bundleId, modelId)
        } catch (e: Exception) {
            return error("failed to describe model '$modelId' in bundle '$bundleId': ${e.message}")
        } ?: return error("no model '$modelId' in bundle '$bundleId'")

        val kinds = registry.modelKinds(bundleId, modelId)
        val inputSchema = SchemaTranslator.inputSchema(descriptor)
        val payload = buildJsonObject {
            put("modelIdentifier", descriptor.modelIdentifier)
            put("modelName", descriptor.modelName)
            // The model's declared task kinds — the agent's menu of intents.
            putJsonArray("supportedApps") { kinds.forEach { add(it.name) } }
            putJsonArray("responseNames") { descriptor.responseNames.forEach { add(it) } }
            put("hasCatalog", descriptor.catalog != null)
            put("inputSchema", inputSchema)
            put("outputSchema", SchemaTranslator.outputSchema(descriptor))
        }
        val inputCount = (inputSchema as? JsonObject)?.get("properties")?.jsonObject?.size ?: 0
        val summary = buildString {
            appendLine("Model ${descriptor.modelName} (${descriptor.modelIdentifier})")
            appendLine("  task kinds: ${kinds.joinToString(", ") { it.name }}")
            appendLine("  responses: ${descriptor.responseNames.joinToString(", ")}")
            append("  inputs: $inputCount (catalog-led: ${descriptor.catalog != null})")
        }
        return result(summary, payload)
    }

    /** `animation_layout_template` — a valid starter `AnimationLayout` for a bundled model (a rough
     *  auto-placement the author then refines in the desktop editor). No run, no trace — reads only
     *  the model's static structure. */
    fun animationLayoutTemplate(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        val model = try {
            registry.modelProvider().provideModel(bundleId, modelId)
        } catch (e: Exception) {
            return error("failed to build model '$modelId' in bundle '$bundleId': ${e.message}")
        }
        val layout = model.scaffoldLayout()
        return if (arguments.string("format")?.equals("toml", ignoreCase = true) == true) {
            result(layout.toToml(), buildJsonObject { put("documentType", "AnimationLayout"); put("format", "toml") })
        } else {
            documentResult("AnimationLayout", layout.toJson())
        }
    }

    /** `validate_animation_layout` — validate a (possibly LLM-edited) `AnimationLayout` against a
     *  model's structure. Reports unmatched queue / resource / movable-resource / response / selector
     *  bindings, each with a nearest-name "did you mean" hint. Station and objectClass names are NOT
     *  checked here — those bind to a produced trace, not static structure. */
    fun validateAnimationLayout(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        val layoutText = arguments.string("layout")
            ?: return error("missing required argument 'layout' (a JSON or TOML AnimationLayout)")
        val model = try {
            registry.modelProvider().provideModel(bundleId, modelId)
        } catch (e: Exception) {
            return error("failed to build model '$modelId' in bundle '$bundleId': ${e.message}")
        }
        val layout = try {
            if (layoutText.trimStart().startsWith("{")) AnimationLayout.fromJson(layoutText)
            else AnimationLayout.fromToml(layoutText)
        } catch (e: Exception) {
            return error("could not parse the layout (expected a JSON or TOML AnimationLayout): ${e.message}")
        }
        val report = layout.validateAgainst(model)
        val structured = buildJsonObject {
            put("isValid", report.isValid)
            putJsonArray("issues") {
                report.issues.forEach { issue ->
                    add(buildJsonObject {
                        put("kind", issue.kind.name); put("name", issue.name); put("message", issue.message)
                    })
                }
            }
        }
        val summary = if (report.isValid) {
            "Animation layout is valid — every checked binding matches a model element."
        } else {
            "Animation layout has ${report.issues.size} unmatched binding(s):\n" +
                report.issues.joinToString("\n") { "  - [${it.kind}] ${it.message}" }
        }
        return result(summary, structured)
    }

    /** `animation_layout_from_trace` — a richer layout inferred from a run's captured animation trace:
     *  empirically observed flow order, real centroids, conveyor anchors, storages. Needs a run made
     *  with tracing on (run_model tracing=true); complements the structural scaffold of
     *  animation_layout_template. */
    fun animationLayoutFromTrace(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val atf = artifactStore.list(resultId).firstOrNull { it.name.endsWith(".atf") }
            ?: return error("no animation trace (.atf) for result '$resultId' — re-run with tracing enabled " +
                "(run_model tracing=true, or a run_config whose tracingConfig names a trace file).")
        val layout = try {
            val source = AnimationSource.load(null, java.nio.file.Path.of(atf.path))
            ReplayModel.build(source).autoLayout(source.events, source.header.description)
        } catch (e: Exception) {
            return error("could not infer a layout from the trace: ${e.message}")
        }
        return if (arguments.string("format")?.equals("toml", ignoreCase = true) == true) {
            result(layout.toToml(), buildJsonObject { put("documentType", "AnimationLayout"); put("format", "toml") })
        } else {
            documentResult("AnimationLayout", layout.toJson())
        }
    }

    /** `render_animation_layout` — render a (proposed or edited) AnimationLayout to a static PNG preview,
     *  returned inline as an image (so the model can see it) plus a downloadable artifact — the
     *  propose → render → look → revise loop. */
    fun renderAnimationLayout(arguments: JsonObject?): CallToolResult {
        val layoutText = arguments.string("layout")
            ?: return error("missing required argument 'layout' (a JSON or TOML AnimationLayout)")
        val layout = try {
            if (layoutText.trimStart().startsWith("{")) AnimationLayout.fromJson(layoutText)
            else AnimationLayout.fromToml(layoutText)
        } catch (e: Exception) {
            return error("could not parse the layout (expected a JSON or TOML AnimationLayout): ${e.message}")
        }
        // A standalone render (no run) — key the artifact by the layout's content hash.
        val resultId = "layout-" + ResultStore.sha256(layoutText).take(16)
        val pngPath = artifactStore.dirFor(resultId).resolve("layout.png")
        try {
            ksl.service.capability.render.AnimationLayoutRenderer.renderToPng(layout, pngPath)
        } catch (e: Exception) {
            return error("could not render the layout: ${e.message}")
        }
        val bytes = java.nio.file.Files.readAllBytes(pngPath)
        val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
        val refs = artifactStore.list(resultId)
        val structured = buildJsonObject {
            put("resultId", resultId)
            putJsonArray("artifacts") {
                refs.forEach { add(buildJsonObject { put("name", it.name); put("mediaType", it.mediaType); put("path", it.path) }) }
            }
        }
        // Inline the image so a vision model can see the layout; the artifact lets the user open it.
        return CallToolResult(
            content = listOf(
                ImageContent(data = base64, mimeType = "image/png"),
                TextContent(
                    "Rendered the layout to layout.png (${bytes.size} bytes). Fetch it with " +
                        "get_artifact(resultId=\"$resultId\", name=\"layout.png\").",
                ),
            ),
            structuredContent = structured,
        )
    }

    /** A config-document scaffold result: the raw document as text (to edit and submit) plus
     *  the same parsed into `structuredContent` for typed access. */
    private fun documentResult(documentType: String, encoded: String): CallToolResult {
        val parsed = runCatching { json.parseToJsonElement(encoded) }.getOrNull()?.let(::sanitizeNonFinite)
        val structured = buildJsonObject {
            put("documentType", documentType)
            if (parsed is JsonObject) put("document", parsed)
        }
        return result(encoded, structured)
    }

    /**
     * JSON — and the MCP transport's response serializer — cannot represent the
     * non-finite doubles (±Infinity, NaN) that a model's unbounded `ControlData`
     * bounds carry (a control with no upper limit reports `upperBound = +∞`).
     * Left in `structuredContent`, such a value makes the SDK's stricter
     * serializer throw while writing the reply, so no response is ever sent and
     * the call hangs. Map every non-finite numeric primitive to null — the same
     * "±∞ → null" convention the database-analysis path uses — so the structured
     * payload is always wire-serializable.
     */
    private fun sanitizeNonFinite(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { sanitizeNonFinite(it.value) })
        is JsonArray -> JsonArray(element.map(::sanitizeNonFinite))
        is JsonPrimitive ->
            if (!element.isString && element.doubleOrNull?.isFinite() == false) JsonNull else element
    }

    /**
     * `run_template` — a ready-to-edit `RunConfiguration` document scaffold for a
     * model (controls and run parameters pre-filled at defaults). The agent edits
     * the values it wants and submits the document to `run_config`.
     */
    fun runTemplate(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        val descriptor = registry.describeModel(bundleId, modelId)
            ?: return error("no model '$modelId' in bundle '$bundleId'")
        return documentResult("RunConfiguration", RunConfigurationJson.encode(RunTemplates.runDocument(descriptor, modelId)))
    }

    /** `validate_run_config` — validates a RunConfiguration document without running it. */
    fun validateRun(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "RunConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeRun(text!!)
        } catch (e: Exception) {
            return error("invalid RunConfiguration document: ${e.message}")
        }
        return validationResult(runService.validateRunConfig(config))
    }

    /** `validate_optimization_config` — validates an OptimizationRunConfiguration without running. */
    fun validateOptimization(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "OptimizationRunConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeOptimization(text!!)
        } catch (e: Exception) {
            return error("invalid OptimizationRunConfiguration document: ${e.message}")
        }
        return validationResult(runService.validateOptimizationConfig(config))
    }

    // ----- preview: canonical echo + workload/cost (Phase 8 Tier C) -----

    /** `preview_run_config` — the canonical RunConfiguration + its workload (no run). */
    fun previewRun(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "RunConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeRun(text!!)
        } catch (e: Exception) {
            return error("invalid RunConfiguration document: ${e.message}")
        }
        return previewResult(DocumentPreview.forRun(config))
    }

    /** `preview_optimization_config` — the canonical document + its iteration/replication budget. */
    fun previewOptimization(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "OptimizationRunConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeOptimization(text!!)
        } catch (e: Exception) {
            return error("invalid OptimizationRunConfiguration document: ${e.message}")
        }
        return previewResult(DocumentPreview.forOptimization(config))
    }

    /** `preview_experiment_config` — the canonical document + the design-point/replication count. */
    fun previewExperiment(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "ExperimentConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeExperiment(text!!)
        } catch (e: Exception) {
            return error("invalid ExperimentConfiguration document: ${e.message}")
        }
        return try {
            previewResult(DocumentPreview.forExperiment(config))
        } catch (e: Exception) {
            error("cannot preview experiment: ${e.message}")
        }
    }

    /** `preview_fit_config` — the canonical document + the dataset/estimator counts. */
    fun previewFit(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "FitConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeFit(text!!)
        } catch (e: Exception) {
            return error("invalid FitConfiguration document: ${e.message}")
        }
        return previewResult(DocumentPreview.forFit(config))
    }

    /** submit_run envelope: the job's identity + cache disposition as `structuredContent`. */
    private fun jobResult(payload: JsonObject): CallToolResult = result(jobSummary(payload), payload)

    private fun jobSummary(payload: JsonObject): String = buildString {
        val status = payload.str("status")
        append("Job ${payload.str("jobId")} — status: $status")
        if (payload["cached"]?.jsonPrimitive?.booleanOrNull == true) append(" (cached)")
        append("; resultId: ${payload.str("resultId")}.")
        payload["reusedReplications"]?.jsonPrimitive?.intOrNull
            ?.let { append(" Reused $it replication(s) from a cached shorter run.") }
        if (status == JobStatus.RUNNING.name) append(" Poll get_run_events for progress and get_run_result for the result.")
    }

    /** get_run_events envelope: the journaled-progress snapshot as `structuredContent`. */
    private fun eventsResult(payload: JsonObject): CallToolResult = result(eventsSummary(payload), payload)

    private fun eventsSummary(payload: JsonObject): String {
        val events = payload["events"]?.jsonArray ?: JsonArray(emptyList())
        return buildString {
            appendLine(
                "Run ${payload.str("jobId")} — status: ${payload.str("status")}; " +
                    "${events.size} new event(s) (nextOffset ${payload.str("nextOffset")}).",
            )
            events.forEach { e -> appendLine("  - ${e.jsonObject.str("type")}: ${e.jsonObject.str("detail")}") }
        }.trimEnd()
    }

    /** Preview envelope: the canonical document + workload as `structuredContent`, with a
     *  human-readable workload digest in the text so the agent can report the cost before running. */
    private fun previewResult(preview: JsonObject): CallToolResult =
        result(previewSummary(preview), preview)

    private fun previewSummary(preview: JsonObject): String = buildString {
        val type = preview["documentType"]?.jsonPrimitive?.contentOrNull ?: "document"
        appendLine("Preview of $type — workload (not run):")
        preview["workload"]?.jsonObject?.forEach { (k, v) ->
            val rendered = when (v) {
                is JsonArray -> "[${v.size} item(s)]"
                is JsonObject -> "{…}"
                else -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            }
            appendLine("  - $k: $rendered")
        }
    }.trimEnd()

    /** Validation envelope: a one-line verdict + field-level errors/warnings as `structuredContent`. */
    private fun validationResult(validation: ValidationResult): CallToolResult =
        result(validationSummary(validation), validationStructured(validation))

    private fun validationStructured(validation: ValidationResult): JsonObject =
        buildJsonObject {
            put("valid", validation.isValid)
            putJsonArray("errors") {
                validation.errors.forEach { add(buildJsonObject { put("path", it.path); put("message", it.message); put("code", it.code) }) }
            }
            putJsonArray("warnings") {
                validation.warnings.forEach { add(buildJsonObject { put("path", it.path); put("message", it.message); put("code", it.code) }) }
            }
        }

    private fun validationSummary(validation: ValidationResult): String = buildString {
        if (validation.isValid) {
            append("VALID")
            if (validation.warnings.isEmpty()) append(" — no errors.") else appendLine(" — ${validation.warnings.size} warning(s):")
        } else {
            appendLine("INVALID — ${validation.errors.size} error(s):")
            validation.errors.forEach { appendLine("  - ${it.path}: ${it.message}") }
            if (validation.warnings.isNotEmpty()) appendLine("${validation.warnings.size} warning(s):")
        }
        validation.warnings.forEach { appendLine("  - ${it.path}: ${it.message}") }
    }.trimEnd()

    /**
     * `run_model` — the blocking convenience: builds the single-run document from
     * flattened arguments and runs it through the *same* cached document path as
     * `run_config`, returning a compact result card (Phase 8.5) with a `resultId`.
     * Because it keys on the built `RunConfiguration`, an identical run authored
     * either way shares one cache entry. Optional `numberOfReplications` /
     * `lengthOfReplication` override the model defaults; `inputs` is a
     * `{inputKey: value}` map; `useCache` (default true) can force a re-run.
     */
    suspend fun runModel(arguments: JsonObject?): CallToolResult {
        val (built, argError) = buildRun(arguments)
        if (argError != null) return argError
        built!!
        return incrementalRunConfig(built.config, useCache(arguments))
    }

    /**
     * The cache-aware single-run path shared by `run_model` and `run_config`.
     * Routes through [IncrementalRunCache], so an escalated replication count
     * reuses a cached shorter run (running only the missing replications and
     * combining) instead of recomputing from scratch — exact, and bounded in
     * storage (sufficient statistics, no per-replication arrays).
     */
    private suspend fun incrementalRunConfig(config: ksl.app.config.RunConfiguration, useCache: Boolean): CallToolResult {
        val salt = CacheVersion.forRun(registry, config)
        // resultId is keyed off the original config (matches IncrementalRunCache's
        // own exactKey), so redirecting the run's output below is an execution
        // detail that does not affect caching.
        val resultId = ResultKeys.forRunConfig(config, salt)
        val reportRequest = reportRequestFor(config.outputConfig)
        // An animation trace is requested when the config names a trace file; redirect it into the
        // result's artifact dir (preserving the chosen filename) so get_artifact returns the .atf.
        // The attachment writes animationTraceFile verbatim, so the server owns the location.
        val traceArtifact = config.tracingConfig.animationTraceFile
            ?.let { artifactStore.dirFor(resultId).resolve(java.nio.file.Path.of(it).fileName.toString()) }
        // Redirect output into the server-owned per-result dir when the run produces any managed
        // output: Welch/trace reports OR a KSL database (for analysis) OR an animation trace.
        val capturesOutput = reportRequest != null || config.outputConfig.enableKSLDatabase || traceArtifact != null
        val captureDir = if (capturesOutput) artifactStore.outputDirFor(resultId) else null
        val cached = try {
            IncrementalRunCache.run(resultStore, json, config, useCache, salt) { cfg ->
                var runCfg = if (captureDir != null) {
                    cfg.copy(outputConfig = cfg.outputConfig.copy(outputDirectory = captureDir.toString()))
                } else cfg
                if (traceArtifact != null) {
                    runCfg = runCfg.copy(tracingConfig = runCfg.tracingConfig.copy(animationTraceFile = traceArtifact.toString()))
                }
                val result = runJobs.result(runJobs.register { runService.submitRunConfig(runCfg) }.jobId)
                    ?: kotlin.error("run vanished")
                if (reportRequest != null && captureDir != null) {
                    runCatching { reportArtifacts.materialize(artifactStore.dirFor(resultId), captureDir, reportRequest) }
                }
                result.toDto().withArtifacts(artifactStore.list(resultId))
            }
        } catch (e: JobAtCapacityException) {
            return error("server is at capacity (${e.limit} concurrent jobs); try again shortly")
        } catch (e: Exception) {
            return error("run failed: ${e.message}")
        }
        return runResult(cached)
    }

    /**
     * The default reports to render for a run, derived from its capture toggles:
     * a Welch report when Welch analysis was captured, a trace report when
     * response tracing was captured; null when neither (no post-run reporting).
     */
    private fun reportRequestFor(outputConfig: ksl.app.config.OutputConfig): ksl.service.capability.report.ReportRequest? {
        val request = ksl.service.capability.report.ReportRequest(
            welch = if (outputConfig.enableWelchAnalysis) ksl.service.capability.report.WelchReport() else null,
            trace = if (outputConfig.enableResponseTrace) ksl.service.capability.report.TraceReport() else null,
        )
        return if (request.isEmpty) null else request
    }

    /**
     * `submit_run` — starts a run and returns its job id immediately (does not
     * wait). Poll [getRunEvents] for journaled progress and [getRunResult] for
     * the terminal result. Like the blocking tools it is cache-aware: an
     * identical request already retained returns `cached:true` with a terminal
     * `jobId` (the content key) — no new run — and the live path records the key
     * the result will be stored under on completion. The response carries the
     * `resultId` so the caller can project the result either way.
     */
    fun submitRun(arguments: JsonObject?): CallToolResult {
        val (built, argError) = buildRun(arguments)
        if (argError != null) return argError
        built!!
        val useCache = useCache(arguments)
        if (useCache && resultStore.get(built.key) != null) {
            return jobResult(buildJsonObject {
                put("jobId", built.key)
                put("status", JobStatus.TERMINAL.name)
                put("resultId", built.key)
                put("cached", true)
            })
        }

        // Incremental planning: if the rep count grew over a cached shorter run of
        // the same identity, run only the missing replications; the combine happens
        // on result fetch. The identity (when eligible) also feeds the family index.
        val m = IncrementalRunCache.replications(built.config)
        val identity = if (useCache && m != null && IncrementalRunCache.eligible(built.config)) {
            IncrementalRunCache.runIdentity(built.config, CacheVersion.forRun(registry, built.config))
        } else {
            null
        }
        val topUp = identity?.let { planTopUp(it, m!!) }
        val runConfig = if (topUp != null) IncrementalRunCache.topUpConfig(built.config, topUp.reuseN) else built.config

        val jobId = try {
            runJobs.register { runService.submitRunConfig(runConfig) }.jobId
        } catch (e: JobAtCapacityException) {
            return error("server is at capacity (${e.limit} concurrent runs); try again shortly")
        } catch (e: Exception) {
            return error("could not start run: ${e.message}")
        }
        pendingRuns[jobId] = PendingRun(built.key, ResultKind.RUN, built.request, identity, m, topUp)
        return jobResult(buildJsonObject {
            put("jobId", jobId)
            put("status", JobStatus.RUNNING.name)
            put("resultId", built.key)
            put("cached", false)
            topUp?.let { put("reusedReplications", it.reuseN) }
        })
    }

    /** The largest cached shorter run (with sufficient stats) to extend, or null. */
    private fun planTopUp(identity: String, target: Int): TopUp? {
        val best = resultStore.familyMembers(identity).filterKeys { it < target }.maxByOrNull { it.key } ?: return null
        val dto = resultStore.get(best.value)?.payload
            ?.let { runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), it) }.getOrNull() }
        val usable = dto is RunResultDto.Completed && dto.responses.all { it.sum != null && it.deviationSumOfSquares != null }
        return if (usable) TopUp(best.value, best.key) else null
    }

    /**
     * `get_run_events` — a non-blocking snapshot of a run's journaled events
     * from `fromOffset`. The journal retains every event, so a late or
     * reconnecting caller replays from any offset. Returns the events, the next
     * offset to poll from, and the job's status.
     */
    fun getRunEvents(arguments: JsonObject?): CallToolResult {
        val jobId = arguments.string("jobId") ?: return error("missing required argument 'jobId'")
        val fromOffset = (arguments.int("fromOffset") ?: 0).coerceAtLeast(0)
        val events = runJobs.eventsNow(jobId, fromOffset)
            ?: return if (resultStore.get(jobId) != null) {
                // A cached / content-key id: the run already completed; no live journal.
                eventsResult(buildJsonObject {
                    put("jobId", jobId)
                    put("fromOffset", fromOffset)
                    put("nextOffset", fromOffset)
                    put("status", JobStatus.TERMINAL.name)
                    putJsonArray("events") {}
                })
            } else {
                error("unknown jobId '$jobId'")
            }
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("fromOffset", fromOffset)
            put("nextOffset", fromOffset + events.size)
            put("status", runJobs.status(jobId)?.name ?: "UNKNOWN")
            putJsonArray("events") {
                events.forEach { event ->
                    add(buildJsonObject {
                        put("type", event::class.simpleName ?: "RunEvent")
                        put("detail", event.toString())
                    })
                }
            }
        }
        return eventsResult(payload)
    }

    /**
     * `get_run_result` — once the run has finished, a compact result card with a
     * `resultId` (the full payload is retrievable via `get_result`); a
     * `{status: RUNNING}` marker while still in flight. On the first terminal
     * fetch the result is stored under its content key (store-on-completion), so
     * it is projectable and an identical future submit is a cache hit. A cached /
     * content-key `jobId` resolves straight from the store.
     */
    suspend fun getRunResult(arguments: JsonObject?): CallToolResult {
        val jobId = arguments.string("jobId") ?: return error("missing required argument 'jobId'")
        val liveStatus = runJobs.status(jobId)
        if (liveStatus != null) {
            if (liveStatus != JobStatus.TERMINAL) {
                return result(
                    "Run status: ${liveStatus.name} — not finished; poll get_run_result again.",
                    buildJsonObject { put("status", liveStatus.name) },
                )
            }
            val result = runJobs.result(jobId) ?: return error("result for '$jobId' is unavailable")
            val cached = storeRun(jobId, result)
                ?: return error("the incremental base run was evicted before completion; please re-submit")
            return runResult(cached)
        }
        val stored = resultStore.get(jobId) ?: return error("unknown jobId '$jobId'")
        return runResult(stored, fromCache = true)
    }

    /**
     * `cancel_run` — requests cancellation of a still-running job started by
     * `submit_run`. Reports `cancelled:true` only when the job was running and a
     * cancel was issued; an unknown, already-terminal, or evicted job is a clean
     * `cancelled:false` (not an error), so an agent can cancel idempotently.
     */
    fun cancelRun(arguments: JsonObject?): CallToolResult {
        val jobId = arguments.string("jobId") ?: return error("missing required argument 'jobId'")
        val reason = arguments.string("reason") ?: "Cancelled by user"
        val status = runJobs.status(jobId)
        if (status != JobStatus.RUNNING) {
            val why = if (status == JobStatus.TERMINAL) "already finished" else "unknown or evicted"
            return result(
                "No running job '$jobId' to cancel ($why).",
                buildJsonObject { put("jobId", jobId); put("cancelled", false); put("message", why) },
            )
        }
        runJobs.cancel(jobId, reason)
        return result(
            "Cancellation requested for job '$jobId'.",
            buildJsonObject { put("jobId", jobId); put("cancelled", true); put("message", "cancellation requested: $reason") },
        )
    }

    // jobId -> the content key / kind / request an async run will be stored under
    // when it terminates (store-on-completion; mirrors the REST path). Bounded by
    // the JobManager's own retention; cleared when the result is stored.
    private val pendingRuns = ConcurrentHashMap<String, PendingRun>()

    private class PendingRun(
        val resultId: String,
        val kind: ResultKind,
        val request: JsonElement,
        // Set for an eligible single-scenario run so the result is recorded in the
        // run-identity family on completion (a producer the incremental path reuses).
        val identity: String? = null,
        val replications: Int? = null,
        // Non-null when the registered job runs only the top-up: its result must be
        // combined with the cached shorter run before being served as the full result.
        val topUp: TopUp? = null,
    )

    private class TopUp(val cachedResultId: String, val reuseN: Int)

    /** A built single-run document plus its content key and canonical request. */
    private class BuiltRun(val config: RunConfiguration, val key: String, val request: JsonElement)

    /**
     * Builds the single-run document from flattened arguments (shared by
     * `run_model` and `submit_run`). The second slot of the pair is a fatal
     * argument error when the first is null.
     */
    private fun buildRun(arguments: JsonObject?): Pair<BuiltRun?, CallToolResult?> {
        val bundleId = arguments.string("bundleId") ?: return null to error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return null to error("missing required argument 'modelId'")
        val descriptor = registry.describeModel(bundleId, modelId)
            ?: return null to error("no model '$modelId' in bundle '$bundleId'")
        // Route the agent's input map (the keys describe_model advertises) to
        // control / RV overrides; an unknown key is a clear error.
        val bound = try {
            RunInputs.bind(descriptor, parseInputs(arguments?.get("inputs")))
        } catch (e: IllegalArgumentException) {
            return null to error(e.message ?: "invalid inputs")
        }
        // Random-stream control: replicationSet selects an independent, reproducible
        // realization (0 = the canonical run); the stride is k × the effective rep
        // count so the substream blocks don't overlap. antithetic opts into antithetic
        // variates for variance reduction.
        val replicationSet = arguments.int("replicationSet")
        if (replicationSet != null && replicationSet < 0) {
            return null to error("'replicationSet' must be >= 0")
        }
        val antithetic = arguments?.get("antithetic")?.jsonPrimitive?.booleanOrNull
        // Opt-in KSL database capture (default off): produces the SQLite DB the db_* tools inspect.
        val enableKSLDatabase = arguments?.get("enableKSLDatabase")?.jsonPrimitive?.booleanOrNull ?: false
        // Opt-in animation trace (default off): names a trace file so the run path captures + registers
        // the .atf as an artifact (get_artifact). The server rewrites the location under the result id.
        val tracing = arguments?.get("tracing")?.jsonPrimitive?.booleanOrNull ?: false
        val numReps = arguments.int("numberOfReplications")
        val effectiveReps = numReps ?: descriptor.experimentRunDefaults.numberOfReplications
        val streamAdvances = replicationSet?.let { RunService.streamAdvancesFor(it, effectiveReps) }
        val baseConfig = runService.singleRunConfig(
            modelId,
            numReps,
            arguments.double("lengthOfReplication"),
            bound.controlOverrides,
            bound.rvOverrides,
            streamAdvances = streamAdvances,
            antithetic = antithetic,
            enableKSLDatabase = enableKSLDatabase,
        )
        val config = if (tracing) {
            baseConfig.copy(tracingConfig = ksl.app.config.TracingConfig(animationTraceFile = "animation.atf"))
        } else baseConfig
        return BuiltRun(
            config = config,
            key = ResultKeys.forRunConfig(config, CacheVersion.forRun(registry, config)),
            request = json.parseToJsonElement(RunConfigurationJson.encode(config)),
        ) to null
    }

    /**
     * Store-on-completion for an async run (idempotent). For a plain run, stores
     * the DTO and (when eligible) records it in the run-identity family so later
     * escalations can reuse it. For an incremental top-up, combines the job's
     * (M−N)-rep result with the cached N-rep run into the full M-rep result before
     * storing. Returns null only when the incremental base was evicted before the
     * top-up finished (so the full result cannot be assembled).
     */
    private fun storeRun(jobId: String, result: RunResult): CachedResult? {
        val meta = pendingRuns.remove(jobId)
        val resultId = meta?.resultId ?: jobId
        resultStore.get(resultId)?.let { return CachedResult(it, fromCache = false) }

        val dto = result.toDto()
        val topUp = meta?.topUp
        if (topUp != null) {
            val cachedDto = resultStore.get(topUp.cachedResultId)?.payload
                ?.let { runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), it) }.getOrNull() }
            if (cachedDto !is RunResultDto.Completed || dto !is RunResultDto.Completed) return null
            val stored = persistRun(resultId, meta.request, IncrementalCombine.completed(cachedDto, dto))
            indexFamily(meta, resultId)
            return CachedResult(stored, fromCache = false, reusedReplications = topUp.reuseN)
        }
        val stored = persistRun(resultId, meta?.request ?: JsonNull, dto)
        if (dto is RunResultDto.Completed) indexFamily(meta, resultId)
        return CachedResult(stored, fromCache = false)
    }

    private fun indexFamily(meta: PendingRun?, resultId: String) {
        val identity = meta?.identity ?: return
        val replications = meta.replications ?: return
        resultStore.indexFamily(identity, replications, resultId)
    }

    private fun persistRun(resultId: String, request: JsonElement, dto: RunResultDto): StoredResult {
        val enriched = dto.withArtifacts(artifactStore.list(resultId))
        val stored = StoredResult(
            resultId = resultId,
            kind = ResultKind.RUN,
            createdAt = Clock.System.now(),
            request = request,
            payload = json.encodeToJsonElement(RunResultDto.serializer(), enriched),
        )
        resultStore.put(stored)
        return stored
    }

    /**
     * Parses an `inputs` argument (a JSON object of input key → value) into a map that
     * preserves each value's JSON kind, so `RunInputs.bind` can route it to the matching
     * control family (numeric / string / JSON) and type-check it there. A `JsonObject`
     * already is a `Map<String, JsonElement>`, so it passes straight through.
     */
    private fun parseInputs(element: JsonElement?): Map<String, JsonElement> =
        element as? JsonObject ?: emptyMap()

    /**
     * `run_config` — the document-centric path: runs a complete, author-shaped
     * `RunConfiguration` document (one scenario → a single run, many → a
     * scenario batch) submitted exactly as built. The document is validated
     * before running; field errors come back without a run. This is the
     * full-fidelity counterpart to `run_model` (which is sugar over it).
     */
    suspend fun runConfig(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "RunConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeRun(text!!)
        } catch (e: Exception) {
            return error("invalid RunConfiguration document: ${e.message}")
        }
        val validation = runService.validateRunConfig(config)
        if (!validation.isValid) return validationError(validation)
        return incrementalRunConfig(config, useCache(arguments))
    }

    /**
     * `run_optimization_config` — the document-centric path for optimization:
     * runs a complete `OptimizationRunConfiguration` document as authored,
     * validated first.
     */
    suspend fun runOptimizationConfig(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "OptimizationRunConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeOptimization(text!!)
        } catch (e: Exception) {
            return error("invalid OptimizationRunConfiguration document: ${e.message}")
        }
        val validation = runService.validateOptimizationConfig(config)
        if (!validation.isValid) return validationError(validation)
        return runCached(
            key = ResultKeys.forOptimizationConfig(config, CacheVersion.forOptimization(registry, config)),
            kind = ResultKind.OPTIMIZATION,
            request = json.parseToJsonElement(OptimizationRunConfigurationJson.encode(config)),
            useCache = useCache(arguments),
            failMessage = "optimization failed",
        ) {
            runJobs.result(runJobs.register { runService.submitOptimizationConfig(config) }.jobId)
                ?: kotlin.error("optimization vanished")
        }
    }

    /**
     * The one cached run-and-card path every run tool funnels through (Phase 8
     * §4/§5): on a cache miss it runs [produce], maps the terminal result to a
     * `RunResultDto`, stores it under [key], and returns a compact card; on a hit
     * it returns the stored card without running. So caching and progressive
     * disclosure are identical across the flattened and document tools.
     */
    private suspend fun runCached(
        key: String,
        kind: ResultKind,
        request: JsonElement,
        useCache: Boolean,
        failMessage: String,
        produce: suspend () -> RunResult,
    ): CallToolResult {
        val cached = try {
            resultStore.cachedRun(key, kind, request, useCache) {
                json.encodeToJsonElement(
                    RunResultDto.serializer(),
                    produce().toDto().withArtifacts(artifactStore.list(key)),
                )
            }
        } catch (e: JobAtCapacityException) {
            return error("server is at capacity (${e.limit} concurrent jobs); try again shortly")
        } catch (e: Exception) {
            return error("$failMessage: ${e.message}")
        }
        return runResult(cached)
    }

    /** The optional `useCache` flag (default true); `false` forces a re-run. */
    private fun useCache(arguments: JsonObject?): Boolean =
        arguments?.get("useCache")?.jsonPrimitive?.booleanOrNull ?: true

    /**
     * Extracts the configuration-document text from the `config` argument, which may be
     * either a JSON document object (back-compat) or a string carrying the document as
     * JSON **or TOML** text. The typed [ksl.service.config.ConfigDocuments] decoder then
     * accepts either format, so an agent can pass a `.toml` file's contents verbatim —
     * e.g. a scenario file saved by the KSL desktop app — without converting it to JSON.
     */
    private fun configDocText(arguments: JsonObject?, docType: String): Pair<String?, CallToolResult?> =
        when (val element = arguments?.get("config")) {
            null -> null to error("missing required argument 'config' (a $docType document object, or a JSON/TOML string)")
            is JsonObject -> element.toString() to null
            is JsonPrimitive ->
                if (element.isString) element.content to null
                else null to error("'config' must be a $docType document object or a JSON/TOML string")
            else -> null to error("'config' must be a $docType document object or a JSON/TOML string")
        }

    /**
     * The structured-output envelope for an execution result (structured-output
     * Phase 1): the text content is a **complete, presentation-ready summary**
     * (every response with its full statistics, every design point, the
     * optimization best), and [CallToolResult.structuredContent] carries the
     * **full** result payload — so the agent has the exact data without a second
     * `get_result` round-trip and an ad-hoc parse. The projection tools
     * ([getResult] / [getResponse] / [getDesignPoint]) remain for deep drill-down
     * on very large retained results.
     */
    private fun runResult(stored: StoredResult, fromCache: Boolean, reused: Int = 0): CallToolResult =
        result(runSummary(stored, fromCache), runStructured(stored, fromCache, reused))

    private fun runResult(cached: CachedResult): CallToolResult =
        runResult(cached.stored, cached.fromCache, cached.reusedReplications)

    /** Full result payload + run metadata as one object (conforms to the run outputSchema). */
    private fun runStructured(stored: StoredResult, fromCache: Boolean, reused: Int): JsonObject =
        buildJsonObject {
            put("resultId", stored.resultId)
            put("cached", fromCache)
            if (reused > 0) put("reusedReplications", reused)
            // The full payload (type, summary, responses, items, best, iterations, …) wins on key conflicts.
            stored.payload.jsonObject.forEach { (k, v) -> put(k, v) }
        }

    /** A complete, lossless human summary of a run / experiment / optimization result. */
    private fun runSummary(stored: StoredResult, fromCache: Boolean): String {
        val p = stored.payload.jsonObject
        val type = p["type"]?.jsonPrimitive?.contentOrNull
        return buildString {
            appendLine("Result ${stored.resultId}${if (fromCache) " (cached)" else ""} — status: ${type ?: "unknown"}")
            when (type) {
                "completed" -> {
                    p["summary"]?.jsonObject?.let { s ->
                        appendLine(
                            "Model ${s.str("modelIdentifier")} — replications " +
                                "${s.str("completedReplications")}/${s.str("requestedReplications")} (${s.str("endingStatus")})",
                        )
                    }
                    appendLine()
                    appendLine("| Response | Average | Std Err | 95% CI half-width | Count |")
                    appendLine("|---|---|---|---|---|")
                    p["responses"]?.jsonArray?.forEach { r ->
                        val o = r.jsonObject
                        appendLine("| ${o.str("name")} | ${o.str("average")} | ${o.str("stdErr")} | ${o.str("halfWidth")} | ${o.str("count")} |")
                    }
                }
                "batch" -> {
                    val items = p["items"]?.jsonArray ?: JsonArray(emptyList())
                    appendLine("Batch of ${items.size} design-point/scenario result(s):")
                    items.forEach { item ->
                        val o = item.jsonObject
                        appendLine("- ${o["itemName"]?.jsonPrimitive?.contentOrNull ?: "(item)"}")
                        o["responses"]?.jsonArray?.forEach { r ->
                            appendLine("    ${r.jsonObject.str("name")} = ${r.jsonObject.str("average")}")
                        }
                    }
                }
                "optimization" -> {
                    p["best"]?.let { appendLine("Best solution: $it") }
                    appendLine("Iterations evaluated: ${p["iterations"]?.jsonArray?.size ?: 0}")
                }
                "failed" -> appendLine("Error: ${p.str("message")}")
                "cancelled" -> appendLine("Cancelled: ${p.str("reason")}")
            }
            nextSteps(type).takeIf { it.isNotBlank() }?.let { appendLine(); append(it) }
        }.trimEnd()
    }

    /** A short "what to do next" line appended to heavy result summaries (B0 signposting) so the
     *  model keeps moving without re-deriving the workflow. The batch line also carries the
     *  database-gate note (4a): headless comparison works, but reports + experiment_regression
     *  need the run's database (enableKSLDatabase). */
    private fun nextSteps(type: String?): String = when (type) {
        "completed" -> "Next steps: compare settings with a scenario batch (run_config) · optimize " +
            "inputs (run_optimization) · drill into one response (get_response)."
        "batch" -> "Next steps: rank the configurations with db_compare (works headless) · if this was " +
            "a designed experiment, find which factors matter with experiment_regression · render a " +
            "report with db_compare_report / db_summary_report. Reports and experiment_regression need " +
            "the run's database — set enableKSLDatabase on the run."
        "optimization" -> "Next steps: refine by tightening the decision-variable bounds or raising " +
            "replicationsPerEvaluation · inspect the search with get_design_point / get_run_events."
        else -> ""
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""

    /** `get_result` — the full retained result by id (no re-run). Projected back through
     *  the SAME envelope the run/fit tools use, so the agent gets the complete summary +
     *  structuredContent it would have on the original call. */
    fun getResult(arguments: JsonObject?): CallToolResult {
        val id = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val stored = resultStore.get(id) ?: return error("no retained result '$id'")
        return when (stored.kind) {
            ResultKind.FIT -> fitResult(CachedResult(stored, fromCache = true))
            else -> runResult(stored, fromCache = true)
        }
    }

    /** `list_responses` — the response names available in a retained result. */
    fun listResponses(arguments: JsonObject?): CallToolResult {
        val id = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val stored = resultStore.get(id) ?: return error("no retained result '$id'")
        val names = responsesFrom(stored.payload.jsonObject).map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val payload = buildJsonObject { putJsonArray("responses") { names.forEach { add(it) } } }
        val summary = if (names.isEmpty()) {
            "No responses in result '$id'."
        } else {
            "${names.size} response(s): ${names.joinToString(", ")}"
        }
        return result(summary, payload)
    }

    /** `get_artifacts` — the rendered artifacts (reports, plot images, exports) retained for a result. */
    fun getArtifacts(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val refs = artifactStore.list(resultId)
        val structured = buildJsonObject {
            putJsonArray("artifacts") {
                refs.forEach {
                    add(buildJsonObject { put("name", it.name); put("mediaType", it.mediaType); put("path", it.path) })
                }
            }
        }
        val summary = if (refs.isEmpty()) {
            "No artifacts for result '$resultId'."
        } else {
            buildString {
                appendLine("${refs.size} artifact(s) for $resultId:")
                refs.forEach { appendLine("  - ${it.name} (${it.mediaType})") }
            }.trimEnd()
        }
        return result(summary, structured)
    }

    /**
     * `get_artifact` — one artifact by name. Text artifacts (HTML/Markdown/text/
     * CSV/JSON/SVG) are returned inline as the text content; for any type the
     * on-disk `path` is included so a local agent can open the file directly.
     */
    fun getArtifact(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val name = arguments.string("name") ?: return error("missing required argument 'name'")
        val file = artifactStore.resolve(resultId, name)
            ?: return error("no artifact '$name' for result '$resultId'")
        val mediaType = artifactStore.list(resultId).firstOrNull { it.name == name }?.mediaType
            ?: "application/octet-stream"
        val isText = mediaType.startsWith("text/") || mediaType == "application/json" || mediaType == "image/svg+xml"
        val content = if (isText) runCatching { java.nio.file.Files.readString(file) }.getOrNull() else null
        val structured = buildJsonObject {
            put("name", name)
            put("mediaType", mediaType)
            put("path", file.toString())
            if (content != null) put("content", content)
        }
        val summary = content ?: "Artifact '$name' ($mediaType) is at: $file"
        return result(summary, structured)
    }

    // External KSL databases opened via db_open_external: resultId -> the directory holding the
    // database, so a foreign database (one the server did not produce) is analyzed by the same db_* tools.
    private val externalDbDirs = ConcurrentHashMap<String, java.nio.file.Path>()

    /** The database directory for [resultId]: a registered external database, else the result's own output dir. */
    private fun dbDirFor(resultId: String): java.nio.file.Path =
        externalDbDirs[resultId] ?: artifactStore.outputDirFor(resultId)

    /**
     * `db_open_external` — open a pre-existing KSL database the server did not produce (a SQLite
     * `.db` file, or an embedded Derby directory) so the db_* tools can analyze it. Opening runs
     * KSLDatabase's schema check, so a non-KSL file fails fast with a clear error. Returns a
     * `resultId` (keyed by the path) plus the database's experiments; pass that resultId to
     * db_status / db_experiments / db_summary / db_compare / db_view like any other result.
     */
    fun dbOpenExternal(arguments: JsonObject?): CallToolResult {
        val pathStr = arguments.string("path") ?: return error("missing required argument 'path'")
        val path = try {
            java.nio.file.Path.of(pathStr).toAbsolutePath().normalize()
        } catch (e: Exception) {
            return error("invalid path '$pathStr': ${e.message}")
        }
        if (!java.nio.file.Files.exists(path)) return error("no database found at '$pathStr'")
        // A Derby database is a directory; a SQLite database is a file located within its directory.
        val dir = if (java.nio.file.Files.isDirectory(path)) path else path.parent
            ?: return error("cannot resolve a directory for '$pathStr'")
        if (!java.nio.file.Files.isDirectory(path)) {
            val located = resultDb.locate(dir)
            if (located != null && located.toAbsolutePath().normalize() != path) {
                return error(
                    "the directory of '$pathStr' holds a different database ('${located.fileName}'); " +
                        "pass that database's directory, or isolate the file",
                )
            }
        }
        // Opening validates the schema (KSLDatabase.init throws for a non-ksl_db file).
        val experiments = try {
            resultDb.experiments(dir)
        } catch (e: Exception) {
            return error("'$pathStr' is not a readable KSL database: ${e.message}")
        } ?: return error("no KSL database found at '$pathStr'")
        val resultId = "external-" + ksl.service.store.ResultStore.sha256(path.toString()).take(16)
        externalDbDirs[resultId] = dir
        val element = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(ksl.service.capability.dbanalysis.ExperimentInfoDto.serializer()),
            experiments,
        )
        val structured = buildJsonObject {
            put("resultId", resultId)
            put("path", path.toString())
            put("experiments", element)
        }
        val summary = buildString {
            appendLine("Opened external KSL database as resultId '$resultId' (${experiments.size} experiment(s)):")
            experiments.forEach { appendLine("  - ${it.name}") }
            append("Analyze it with db_status / db_experiments / db_summary / db_compare using this resultId.")
        }
        return result(summary, structured)
    }

    /** `db_status` — whether a result has an analyzable KSL database, with guidance when not. */
    fun dbStatus(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val status = resultDb.status(dbDirFor(resultId))
        val structured = buildJsonObject {
            put("present", status.present)
            put("experimentCount", status.experimentCount)
            put("message", status.message)
        }
        return result(status.message, structured)
    }

    /** `db_experiments` — the experiments recorded in a result's database. */
    fun dbExperiments(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val experiments = resultDb.experiments(dbDirFor(resultId))
            ?: return result(ksl.service.capability.dbanalysis.NO_DATABASE_MESSAGE, buildJsonObject { put("present", false) })
        val element = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(ksl.service.capability.dbanalysis.ExperimentInfoDto.serializer()),
            experiments,
        )
        val structured = buildJsonObject { put("experiments", element) }
        val summary = "${experiments.size} experiment(s): " + experiments.joinToString(", ") { it.name }
        return result(summary, structured)
    }

    /** `db_summary` — across-replication summary statistics for one experiment, as JSON. */
    fun dbSummary(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val experiment = arguments.string("experimentName") ?: return error("missing required argument 'experimentName'")
        val dbOutcome = resultDb.summary(dbDirFor(resultId), experiment)
        // With no database, project the retained batch item's across-replication statistics.
        val outcome = if (dbOutcome is ksl.service.capability.dbanalysis.DbQueryResult.NoDatabase) {
            inMemorySummary(resultId, experiment) ?: dbOutcome
        } else {
            dbOutcome
        }
        return dbJsonResult(outcome, "summary")
    }

    /** `db_compare` — multiple-comparison (MCB) analysis of a response, as JSON. */
    fun dbCompare(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val response = arguments.string("responseName") ?: return error("missing required argument 'responseName'")
        val experiments = arguments?.get("experiments")?.let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
        val delta = arguments?.get("delta")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val level = arguments?.get("level")?.jsonPrimitive?.doubleOrNull ?: 0.95
        return dbJsonResult(
            resultDb.compare(
                dbDirFor(resultId), response, experiments, delta, level,
                // With no database (a headless batch), analyze the retained per-replication
                // observations instead — same analyzer, same JSON, transparent to the caller.
                fallbackSource = inMemoryComparisonSource(resultId),
            ),
            "comparison",
        )
    }

    /** The retained result for [resultId] decoded as a batch, or null when it is not a retained batch. */
    private fun retainedBatch(resultId: String): RunResultDto.BatchCompleted? {
        val payload = resultStore.get(resultId)?.payload ?: return null
        val dto = runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), payload) }.getOrNull()
        return dto as? RunResultDto.BatchCompleted
    }

    /** A comparison source rehydrated from a retained batch's per-replication observations (C1),
     *  or null when there is no retained batch — so the database path's NoDatabase result stands. */
    private fun inMemoryComparisonSource(resultId: String): ksl.app.comparison.ComparisonDataSourceIfc? =
        retainedBatch(resultId)?.let { ksl.service.capability.dbanalysis.RunResultComparisonSource.fromBatch(it) }

    /** The retained batch item's across-replication statistics as a summary JSON — the headless
     *  counterpart to the database summary — or null when there is no matching retained item. */
    private fun inMemorySummary(resultId: String, experimentName: String): ksl.service.capability.dbanalysis.DbQueryResult? {
        val item = retainedBatch(resultId)?.items?.firstOrNull { it.itemName == experimentName } ?: return null
        val payload = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(ksl.service.capability.run.dto.ResponseStatDto.serializer()),
            item.responses,
        )
        return ksl.service.capability.dbanalysis.DbQueryResult.Json(payload.toString())
    }

    /** `db_views` — the statistical DataFrame views available for a result's database. */
    fun dbViews(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val names = resultDb.viewNames(dbDirFor(resultId))
            ?: return result(ksl.service.capability.dbanalysis.NO_DATABASE_MESSAGE, buildJsonObject { put("present", false) })
        val structured = buildJsonObject { putJsonArray("views") { names.forEach { add(it) } } }
        return result("${names.size} view(s): ${names.joinToString(", ")}", structured)
    }

    /** `db_view` — one named statistical view as a JSON envelope ({view,total,returned,truncated,rows}). */
    fun dbView(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val view = arguments.string("view") ?: return error("missing required argument 'view'")
        val experiment = arguments.string("experiment")
        val limit = arguments.string("limit")?.toIntOrNull() ?: ksl.service.capability.dbanalysis.DEFAULT_VIEW_ROW_LIMIT
        return dbJsonResult(resultDb.viewJson(dbDirFor(resultId), view, experiment, limit), "view")
    }

    /** `db_compare_report` — render a comparison (MCB) report (with plots) as an artifact. */
    fun dbCompareReport(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val response = arguments.string("responseName") ?: return error("missing required argument 'responseName'")
        val experiments = arguments?.get("experiments")?.let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
        val delta = arguments?.get("delta")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val level = arguments?.get("level")?.jsonPrimitive?.doubleOrNull ?: 0.95
        val formats = (arguments?.get("formats") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.mapNotNull { s -> ksl.app.config.ReportFormat.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
            ?.toSet()?.ifEmpty { null } ?: setOf(ksl.app.config.ReportFormat.HTML)
        val outcome = resultDb.renderComparisonReport(
            dbDirFor(resultId), artifactStore.dirFor(resultId),
            response, experiments, delta, level, formats,
        )
        return dbReportResult(outcome, resultId)
    }

    /** `db_export` — export the result's database tables (CSV per table, or one Excel workbook). */
    fun dbExport(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val formatName = arguments.string("format") ?: "CSV"
        val format = ksl.service.capability.dbanalysis.DbExportFormat.entries
            .firstOrNull { it.name.equals(formatName, ignoreCase = true) }
            ?: return error("format must be CSV or EXCEL")
        val outcome = resultDb.exportDatabase(dbDirFor(resultId), artifactStore.dirFor(resultId), format)
        return dbReportResult(outcome, resultId)
    }

    /** `db_summary_report` — render a single-experiment summary report (stats + histograms/frequencies) as an artifact. */
    fun dbSummaryReport(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val experiment = arguments.string("experimentName") ?: return error("missing required argument 'experimentName'")
        val level = arguments?.get("level")?.jsonPrimitive?.doubleOrNull ?: 0.95
        val showPlots = arguments.string("showPlots")?.toBooleanStrictOrNull() ?: true
        val formats = (arguments?.get("formats") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.mapNotNull { s -> ksl.app.config.ReportFormat.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
            ?.toSet()?.ifEmpty { null } ?: setOf(ksl.app.config.ReportFormat.HTML)
        val outcome = resultDb.renderExperimentSummaryReport(
            dbDirFor(resultId), artifactStore.dirFor(resultId),
            experiment, level, showPlots, formats,
        )
        return dbReportResult(outcome, resultId)
    }

    /** `experiment_regression` — fit a factor-effects regression to a designed experiment
     *  that was run with the database enabled, and render it as an HTML artifact. */
    fun experimentRegression(arguments: JsonObject?): CallToolResult {
        val resultId = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val responseName = arguments.string("responseName") ?: return error("missing required argument 'responseName'")
        val effects = (arguments?.get("effects") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return error("missing required argument 'effects' (an array of control-key names to regress on)")
        if (effects.isEmpty()) return error("'effects' must list at least one control key to regress on")
        // Interactions are '*'-joined products of effect names, e.g. "A*B"; split into term lists.
        val interactions = (arguments?.get("interactions") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.map { it.split("*").map(String::trim).filter(String::isNotEmpty) }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val coded = arguments.string("coded")?.toBooleanStrictOrNull() ?: true
        val level = arguments?.get("level")?.jsonPrimitive?.doubleOrNull ?: 0.95
        val outcome = resultDb.renderExperimentRegressionReport(
            dbDirFor(resultId), artifactStore.dirFor(resultId),
            responseName, effects, interactions, coded, intercept = true, level,
        )
        return dbReportResult(outcome, resultId)
    }

    /** Maps a file-producing [ksl.service.capability.dbanalysis.DbReportResult] to a
     *  tool result: on success the result's artifact list, else a guidance result. */
    private fun dbReportResult(outcome: ksl.service.capability.dbanalysis.DbReportResult, resultId: String): CallToolResult =
        when (outcome) {
            ksl.service.capability.dbanalysis.DbReportResult.NoDatabase ->
                result(ksl.service.capability.dbanalysis.NO_DATABASE_MESSAGE, buildJsonObject { put("present", false) })
            is ksl.service.capability.dbanalysis.DbReportResult.Invalid ->
                result(outcome.reason, buildJsonObject { put("analyzable", false); put("reason", outcome.reason) })
            is ksl.service.capability.dbanalysis.DbReportResult.Ok -> {
                val refs = artifactStore.list(resultId)
                val structured = buildJsonObject {
                    putJsonArray("artifacts") {
                        refs.forEach { add(buildJsonObject { put("name", it.name); put("mediaType", it.mediaType); put("path", it.path) }) }
                    }
                }
                result(
                    "Wrote ${outcome.files.size} file(s): ${outcome.files.joinToString(", ")}. " +
                        "${refs.size} artifact(s) now downloadable via get_artifact.",
                    structured,
                )
            }
        }

    /** Maps a [ksl.service.capability.dbanalysis.DbQueryResult] to a tool result:
     *  JSON in structuredContent on success, or a non-error guidance result when
     *  there is no database / the request is not analyzable. */
    private fun dbJsonResult(outcome: ksl.service.capability.dbanalysis.DbQueryResult, key: String): CallToolResult =
        when (outcome) {
            ksl.service.capability.dbanalysis.DbQueryResult.NoDatabase ->
                result(ksl.service.capability.dbanalysis.NO_DATABASE_MESSAGE, buildJsonObject { put("present", false) })
            is ksl.service.capability.dbanalysis.DbQueryResult.Invalid ->
                result(outcome.reason, buildJsonObject { put("analyzable", false); put("reason", outcome.reason) })
            is ksl.service.capability.dbanalysis.DbQueryResult.Json -> {
                val payload = json.parseToJsonElement(outcome.payload)
                result("Returned $key as structuredContent.$key.", buildJsonObject { put(key, payload) })
            }
        }

    /** `get_response` — one response's statistics from a retained result. */
    fun getResponse(arguments: JsonObject?): CallToolResult {
        val id = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val name = arguments.string("name") ?: return error("missing required argument 'name'")
        val stored = resultStore.get(id) ?: return error("no retained result '$id'")
        val match = responsesFrom(stored.payload.jsonObject)
            .firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == name }
            ?: return error("no response '$name' in result '$id'")
        val o = match.jsonObject
        val summary = "$name — average ${o.str("average")}, std err ${o.str("stdErr")}, " +
            "95% CI half-width ${o.str("halfWidth")} (n=${o.str("count")})"
        return result(summary, o)
    }

    /** `get_design_point` — one scenario/design-point result from a batch. */
    fun getDesignPoint(arguments: JsonObject?): CallToolResult {
        val id = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val index = arguments.int("index") ?: return error("missing required argument 'index'")
        val stored = resultStore.get(id) ?: return error("no retained result '$id'")
        val items = stored.payload.jsonObject["items"]?.jsonArray
            ?: return error("result '$id' is not a batch")
        if (index !in items.indices) return error("design-point index $index out of range (0..${items.size - 1})")
        val o = items[index].jsonObject
        val responses = o["responses"]?.jsonArray
        val summary = buildString {
            append("Design point $index of result '$id'")
            responses?.let { append(" — ${it.size} response(s)") }
            append(".")
        }
        return result(summary, o)
    }

    /** The response array of a completed run, or the first item of a batch. */
    private fun responsesFrom(payload: JsonObject): List<JsonElement> =
        when (payload["type"]?.jsonPrimitive?.contentOrNull) {
            "completed" -> payload["responses"]?.jsonArray?.toList() ?: emptyList()
            "batch" -> payload["items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("responses")?.jsonArray?.toList() ?: emptyList()
            else -> emptyList()
        }

    private fun validationError(validation: ksl.app.validation.ValidationResult): CallToolResult =
        error("invalid configuration: " + validation.errors.joinToString("; ") { "${it.path}: ${it.message}" })

    /**
     * `run_optimization` — runs a simulation-optimization (stochastic hill
     * climbing) over a bundled model and returns the best solution and iteration
     * history. `inputs` are decision variables `{name, lowerBound, upperBound,
     * granularity}`; discover valid control keys and response names first via
     * `describe_model`.
     */
    suspend fun runOptimization(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        if (registry.listModels(bundleId).none { it == modelId }) {
            return error("no model '$modelId' in bundle '$bundleId'")
        }
        val objective = arguments.string("objectiveResponse")
            ?: return error("missing required argument 'objectiveResponse'")
        val inputsArray = arguments?.get("inputs") as? JsonArray
            ?: return error("missing required argument 'inputs' (an array of decision variables)")
        val inputs = try {
            inputsArray.map { json.decodeFromJsonElement(OptimizationInputSpec.serializer(), it) }
        } catch (e: Exception) {
            return error("invalid 'inputs': ${e.message}")
        }
        if (inputs.isEmpty()) return error("'inputs' must list at least one decision variable")

        val config = runService.optimizationRunConfig(
            modelId = modelId,
            objectiveResponse = objective,
            inputs = inputs,
            maxIterations = arguments.int("maxIterations") ?: 20,
            replicationsPerEvaluation = arguments.int("replicationsPerEvaluation") ?: 10,
            maximize = arguments?.get("maximize")?.jsonPrimitive?.booleanOrNull ?: false,
        )
        return runCached(
            key = ResultKeys.forOptimizationConfig(config, CacheVersion.forOptimization(registry, config)),
            kind = ResultKind.OPTIMIZATION,
            request = json.parseToJsonElement(OptimizationRunConfigurationJson.encode(config)),
            useCache = useCache(arguments),
            failMessage = "optimization failed",
        ) {
            runJobs.result(runJobs.register { runService.submitOptimizationConfig(config) }.jobId)
                ?: kotlin.error("optimization vanished")
        }
    }

    /**
     * `run_experiment` — runs a two-level factorial designed experiment over a
     * bundled model and returns the per-design-point results (a batch result).
     * `factors` each bind a model control key to low/high levels; a factorial
     * needs at least two factors. Discover control keys via `describe_model`.
     */
    suspend fun runExperiment(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        if (registry.listModels(bundleId).none { it == modelId }) {
            return error("no model '$modelId' in bundle '$bundleId'")
        }
        val factorsArray = arguments?.get("factors") as? JsonArray
            ?: return error("missing required argument 'factors' (an array of factor bindings)")
        val factors = try {
            factorsArray.map { json.decodeFromJsonElement(ExperimentFactorSpec.serializer(), it) }
        } catch (e: Exception) {
            return error("invalid 'factors': ${e.message}")
        }
        if (factors.size < 2) return error("a factorial experiment needs at least two factors")
        val numReps = arguments.int("numRepsPerDesignPoint")
        // Opt-in: retain each design point's results in a KSL database so the
        // experiment_regression and db_* tools can analyze them afterward. Folded
        // into the cache key, so enabling it forces a fresh run that writes the DB
        // rather than returning a prior no-database result.
        val enableKSLDatabase = arguments?.get("enableKSLDatabase")?.jsonPrimitive?.booleanOrNull ?: false

        // No serializable document backs a designed experiment, so key on a
        // canonical request: the model, the rep count, and the factors in a
        // stable (name-sorted) order — everything that determines the result.
        val request = buildJsonObject {
            put("modelId", modelId)
            numReps?.let { put("numRepsPerDesignPoint", it) }
            if (enableKSLDatabase) put("enableKSLDatabase", true)
            putJsonArray("factors") {
                factors.sortedBy { it.name }.forEach { add(json.encodeToJsonElement(ExperimentFactorSpec.serializer(), it)) }
            }
        }
        val key = ResultStore.sha256("${registry.versionSaltFor(listOf(modelId))}|experiment:$request")
        val captureDir = if (enableKSLDatabase) artifactStore.outputDirFor(key) else null
        return runCached(
            key = key,
            kind = ResultKind.BATCH,
            request = request,
            useCache = useCache(arguments),
            failMessage = "experiment failed",
        ) {
            runJobs.result(runJobs.register { runService.submitExperiment(modelId, factors, numReps, captureDir) }.jobId)
                ?: kotlin.error("experiment vanished")
        }
    }

    /**
     * `experiment_template` — a ready-to-edit `ExperimentConfiguration` document
     * scaffold for a model: a two-level factorial over its first two numeric
     * controls. Edit the factors/levels and submit to `experiment_config`.
     */
    fun experimentTemplate(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        val descriptor = registry.describeModel(bundleId, modelId)
            ?: return error("no model '$modelId' in bundle '$bundleId'")
        return try {
            documentResult("ExperimentConfiguration", ExperimentDocuments.encode(ExperimentDocuments.template(descriptor, modelId)))
        } catch (e: IllegalArgumentException) {
            error(e.message ?: "cannot scaffold an experiment for '$modelId'")
        }
    }

    /**
     * `optimization_template` — a ready-to-edit `OptimizationRunConfiguration` scaffold: a
     * placeholder single-decision-variable problem over the model's first numeric control,
     * minimizing its first response with stochastic hill climbing. The bounds are a **finite
     * placeholder** (`[value, value+1]`) because `OptimizationInputSpec` forbids the ±infinity
     * a control's default bounds carry — edit them to the real search range. Submit to
     * `run_optimization_config`.
     */
    fun optimizationTemplate(arguments: JsonObject?): CallToolResult {
        val bundleId = arguments.string("bundleId") ?: return error("missing required argument 'bundleId'")
        val modelId = arguments.string("modelId") ?: return error("missing required argument 'modelId'")
        val descriptor = registry.describeModel(bundleId, modelId)
            ?: return error("no model '$modelId' in bundle '$bundleId'")
        val control = descriptor.controls.numericControls.firstOrNull()
            ?: return error("model '$modelId' has no numeric controls to optimize over; optimization needs a decision variable")
        val objective = descriptor.responseNames.firstOrNull()
            ?: return error("model '$modelId' has no responses to use as the optimization objective")
        val base = control.value.takeIf { it.isFinite() } ?: 0.0
        return try {
            val input = OptimizationInputSpec(name = control.keyName, lowerBound = base, upperBound = base + 1.0, granularity = 1.0)
            val config = runService.optimizationRunConfig(
                modelId = modelId,
                objectiveResponse = objective,
                inputs = listOf(input),
                maxIterations = 50,
                replicationsPerEvaluation = 20,
            )
            documentResult("OptimizationRunConfiguration", OptimizationRunConfigurationJson.encode(config))
        } catch (e: Exception) {
            error(e.message ?: "cannot scaffold an optimization for '$modelId'")
        }
    }

    /**
     * `validate_experiment_config` — validates an ExperimentConfiguration document
     * (structure + that each factor binds to a real model input) without running.
     */
    fun validateExperiment(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "ExperimentConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeExperiment(text!!)
        } catch (e: Exception) {
            return error("invalid ExperimentConfiguration document: ${e.message}")
        }
        val descriptor = experimentDescriptor(config) ?: return error("the document references an unknown model")
        return validationResult(ExperimentDocuments.validate(config, descriptor))
    }

    /**
     * `experiment_config` — the document-centric experiment path: runs a complete
     * `ExperimentConfiguration` document (factors + design) as authored, validated
     * first. Returns a compact batch card; per-design-point results via
     * `get_design_point` over the retained result.
     */
    suspend fun experimentConfig(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "ExperimentConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeExperiment(text!!)
        } catch (e: Exception) {
            return error("invalid ExperimentConfiguration document: ${e.message}")
        }
        val descriptor = experimentDescriptor(config) ?: return error("the document references an unknown model")
        val validation = ExperimentDocuments.validate(config, descriptor)
        if (!validation.isValid) return validationError(validation)
        val key = ExperimentDocuments.key(config, CacheVersion.forExperiment(registry, config))
        // A document that opts into a database (outputConfig.enableKSLDatabase)
        // has its per-design-point results retained under the result id so the
        // experiment_regression and db_* tools can analyze them afterward. The
        // key already reflects the flag (it hashes the whole document).
        val captureDir = if (config.outputConfig.enableKSLDatabase) artifactStore.outputDirFor(key) else null
        return runCached(
            key = key,
            kind = ResultKind.BATCH,
            request = json.parseToJsonElement(ExperimentDocuments.encode(config)),
            useCache = useCache(arguments),
            failMessage = "experiment failed",
        ) {
            runJobs.result(runJobs.register { runService.submitExperimentConfig(config, captureDir) }.jobId)
                ?: kotlin.error("experiment vanished")
        }
    }

    /** Resolves the model descriptor a document references by its provider id. */
    private fun experimentDescriptor(config: ksl.app.config.experiment.ExperimentConfiguration) =
        (config.modelReference as? ModelReference.ByProviderId)?.providerId?.let { registry.descriptorForModelId(it) }

    /**
     * `fit_dataset` — capability B's quick path: fits candidate distributions to
     * an inline numeric dataset. Builds the `FitConfiguration` from the flat
     * arguments and runs it through the *same* cached fit path as `fit_config`,
     * returning a compact card with a `resultId` (the full ranked fits are
     * retrievable via `get_result`). Estimators/scoring default to the catalog.
     */
    suspend fun fitDataset(arguments: JsonObject?): CallToolResult {
        val dataArray = arguments?.get("data") as? JsonArray
            ?: return error("missing required argument 'data' (an array of numbers)")
        val data = dataArray.map { it.jsonPrimitive.doubleOrNull ?: return error("'data' must contain only numbers") }
            .toDoubleArray()
        if (data.size < 2) return error("'data' must contain at least two values")

        val name = arguments.string("name") ?: "dataset"
        val kind = when (arguments.string("kind")?.uppercase()) {
            null, "CONTINUOUS" -> DistributionKind.CONTINUOUS
            "DISCRETE" -> DistributionKind.DISCRETE
            else -> return error("'kind' must be CONTINUOUS or DISCRETE")
        }
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf(name to data)),
            kind = kind,
            estimatorIds = FittingCatalog.defaultEstimatorIds(kind),
            scoringModelIds = if (kind == DistributionKind.CONTINUOUS) FittingCatalog.defaultScoringModelIds() else emptySet(),
        )
        return cachedFit(config, useCache(arguments), rawData = data)
    }

    /**
     * `fit_config` — the document-centric fit path: runs a complete, author-shaped
     * `FitConfiguration` document (any data source — inline, file, generated, or
     * database) as authored. Validated before running; the result is cached and
     * a compact card is returned (full result via `get_result`).
     */
    suspend fun fitConfig(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "FitConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeFit(text!!)
        } catch (e: Exception) {
            return error("invalid FitConfiguration document: ${e.message}")
        }
        val validation = FitDocuments.validate(config)
        if (!validation.isValid) return validationError(validation)
        return cachedFit(config, useCache(arguments))
    }

    /** `validate_fit_config` — validates a FitConfiguration document without running it. */
    fun validateFit(arguments: JsonObject?): CallToolResult {
        val (text, argErr) = configDocText(arguments, "FitConfiguration")
        if (argErr != null) return argErr
        val config = try {
            ConfigDocuments.decodeFit(text!!)
        } catch (e: Exception) {
            return error("invalid FitConfiguration document: ${e.message}")
        }
        return validationResult(FitDocuments.validate(config))
    }

    /**
     * `fit_template` — a ready-to-edit `FitConfiguration` scaffold for a kind
     * (CONTINUOUS or DISCRETE): an inline data source to fill with observations
     * plus the catalog-default estimators/scoring. Edit and submit to `fit_config`.
     */
    fun fitTemplate(arguments: JsonObject?): CallToolResult {
        val kind = when (arguments.string("kind")?.uppercase()) {
            null, "CONTINUOUS" -> DistributionKind.CONTINUOUS
            "DISCRETE" -> DistributionKind.DISCRETE
            else -> return error("'kind' must be CONTINUOUS or DISCRETE")
        }
        return documentResult("FitConfiguration", FitDocuments.encode(FitDocuments.template(kind)))
    }

    /** The one cached fit-and-card path `fit_dataset` and `fit_config` share. */
    private suspend fun cachedFit(config: FitConfiguration, useCache: Boolean, rawData: DoubleArray? = null): CallToolResult {
        val cached = try {
            resultStore.cachedRun(
                key = FitDocuments.key(config),
                kind = ResultKind.FIT,
                request = json.parseToJsonElement(FitDocuments.encode(config)),
                useCache = useCache,
            ) {
                val result = FitService().use { service -> service.submit(FitSpec.Single(config)).result.await() }
                when (result) {
                    is FitResult.Completed -> json.encodeToJsonElement(FitResultData.serializer(), result.report)
                    is FitResult.Failed -> kotlin.error("fit failed: ${result.error.message}")
                    is FitResult.Cancelled -> kotlin.error("fit cancelled: ${result.reason}")
                    is FitResult.BatchCompleted -> kotlin.error("unexpected batch result for a single-dataset fit")
                }
            }
        } catch (e: Exception) {
            return error("fit failed: ${e.message}")
        }
        // Retain the raw observations (session-scoped) so get_fit_report can render the
        // diagnostic plots, which need the data the fit result itself does not keep.
        if (rawData != null) recentFitData[cached.stored.resultId] = rawData
        return fitResult(cached)
    }

    private fun fitResult(cached: CachedResult): CallToolResult =
        result(fitSummary(cached), fitStructured(cached))

    /** Full fit payload + metadata as one object (conforms to the fit outputSchema). */
    private fun fitStructured(cached: CachedResult): JsonObject =
        buildJsonObject {
            put("resultId", cached.stored.resultId)
            put("cached", cached.fromCache)
            // The full fit payload (datasetName, kind, fits[...], recommendedFamilyId, …).
            cached.stored.payload.jsonObject.forEach { (k, v) -> put(k, v) }
        }

    /** A complete summary of a distribution fit: the recommendation plus the full ranked candidates. */
    /** A complete fit summary: the recommendation + the MODA-ranked candidates (scaled MODA score + GOF). */
    private fun fitSummary(cached: CachedResult): String {
        val p = cached.stored.payload.jsonObject
        return buildString {
            appendLine("Distribution fit ${cached.stored.resultId}${if (cached.fromCache) " (cached)" else ""} — dataset: ${p.str("datasetName")}")
            p["recommendedFamilyId"]?.jsonPrimitive?.contentOrNull?.let { appendLine("Recommended: $it (top MODA score)") }
            p["scoring"]?.jsonObject?.let {
                appendLine("Ranked by MODA (${it.str("rankingMethod")}) over weighted metrics — full scaled-scores matrix via get_fit_scoring.")
            }
            appendLine()
            appendLine("| Rank | Family | MODA score | Avg rank | Parameters | GOF stat | p-value |")
            appendLine("|---|---|---|---|---|---|---|")
            p["fits"]?.jsonArray?.forEach { f ->
                val o = f.jsonObject
                val gof = o["goodnessOfFit"]?.jsonObject
                val params = o["parameters"]?.jsonObject?.entries
                    ?.joinToString(", ") { "${it.key}=${it.value.jsonPrimitive.contentOrNull}" } ?: ""
                appendLine(
                    "| ${o.str("rank")} | ${o.str("familyId")} | ${o.str("weightedValue")} | ${o.str("averageRanking")} | " +
                        "$params | ${gof?.str("chiSquaredStatistic") ?: ""} | ${gof?.str("chiSquaredPValue") ?: ""} |",
                )
            }
            appendLine()
            append("Next steps: render the full report (get_fit_report) · see the MODA scoring matrix " +
                "(get_fit_scoring) · use the recommended distribution (its family + parameters) as a model input.")
        }.trimEnd()
    }

    /**
     * `get_fit_scoring` — the full MODA scoring for a retained continuous fit: the
     * metrics (with weights), each candidate's scaled score per metric, and the
     * per-metric ranks (the scaled-scores matrix behind the ranking).
     */
    fun getFitScoring(arguments: JsonObject?): CallToolResult {
        val id = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val stored = resultStore.get(id) ?: return error("no retained result '$id'")
        val scoring = stored.payload.jsonObject["scoring"]?.jsonObject
            ?: return error("result '$id' has no MODA scoring (continuous fits only)")
        return result(fitScoringSummary(scoring), scoring)
    }

    /**
     * `get_fit_report` — render the full HTML report for a retained fit (data
     * summary, shift analysis, ranked fits, MODA scoring, goodness-of-fit, and the
     * diagnostic plots **when a graphical display is available**) to
     * `~/.ksl/reports/<resultId>.html`, returning its path. Plot rendering needs a
     * display; in a headless environment it degrades to the tables-only report.
     */
    fun getFitReport(arguments: JsonObject?): CallToolResult {
        val id = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val stored = resultStore.get(id) ?: return error("no retained result '$id'")
        if (stored.kind != ResultKind.FIT) return error("result '$id' is not a distribution fit")
        val fitData = try {
            json.decodeFromJsonElement(FitResultData.serializer(), stored.payload)
        } catch (e: Exception) {
            return error("could not read the fit result '$id': ${e.message}")
        }
        val data = (arguments?.get("data") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.doubleOrNull }?.toDoubleArray()
            ?: recentFitData[id]
        // Render with plots when a display is available; fall back to the full
        // tables/stats report (no plots) on any rendering failure (e.g. headless,
        // where lets-plot throws). Catch Throwable: the failure can be an
        // ExceptionInInitializerError from the plot library's static init.
        val (html, withPlots) = try {
            fitData.toDocument(rawData = data).toHtml() to (data != null)
        } catch (t: Throwable) {
            val fallback = runCatching { fitData.toDocument(rawData = null).toHtml() }
                .getOrElse { return error("failed to render the fit report: ${it.message}") }
            fallback to false
        }
        return try {
            // Write under the shared KSL workspace (not ~/.ksl, which is settings-only),
            // beside the other apps' artifacts: <workspace>/KSL_MCP_APPS/reports/.
            val file = workspaceAppDir("reports").resolve("$id.html").toAbsolutePath()
            java.nio.file.Files.writeString(file, html)
            val note = if (withPlots) {
                "with diagnostic plots"
            } else {
                "without plots (no graphical display available — the full statistics report was written)"
            }
            result(
                "Fit report $note:\n$file\nOpen it in a web browser.",
                buildJsonObject {
                    put("resultId", id)
                    put("reportPath", file.toString())
                    put("includedPlots", withPlots)
                },
            )
        } catch (e: Exception) {
            error("failed to write the fit report: ${e.message}")
        }
    }

    /**
     * Renders the MODA **scaled-scores** matrix (alternatives × metric, value-function
     * outputs in [0,1], higher is better) plus the metric weights. The scaled values come
     * from `scoring.values` (value-function outputs); `scoring.scores` holds the raw metric
     * values (both are in structuredContent).
     */
    private fun fitScoringSummary(scoring: JsonObject): String {
        val metrics = scoring["metrics"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val values = scoring["values"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        return buildString {
            appendLine("MODA scoring (${scoring.str("rankingMethod")})")
            if (metrics.isNotEmpty()) {
                appendLine("Metrics — name (weight, direction):")
                metrics.forEach { m -> appendLine("  - ${m.str("metricName")} (${m.str("weight")}, ${m.str("direction")})") }
            }
            // Pivot the value-function outputs (scaled scores) into an alternative × metric matrix.
            val cols = LinkedHashSet<String>()
            val byAlt = LinkedHashMap<String, MutableMap<String, String>>()
            values.forEach { v ->
                val name = v.str("metricName"); cols += name
                byAlt.getOrPut(v.str("alternative")) { linkedMapOf() }[name] = v.str("metricValue")
            }
            if (byAlt.isNotEmpty()) {
                appendLine()
                appendLine("Scaled scores (value-function outputs in [0,1], higher is better):")
                appendLine("| Alternative | ${cols.joinToString(" | ")} |")
                appendLine("|---${"|---".repeat(cols.size)}|")
                byAlt.forEach { (alt, m) -> appendLine("| $alt | ${cols.joinToString(" | ") { m[it] ?: "" }} |") }
            }
        }.trimEnd()
    }

    /**
     * `list_distributions` — the scalar-parameter distribution families the server can
     * generate random variates from (via `generate_variates`). Array-parameter families
     * (empirical, piecewise, etc.) are excluded; they require a dataset rather than
     * scalar values and are deferred to a future tool.
     */
    fun listDistributions(): CallToolResult {
        val families = FittingCatalog.families
            .filter { !it.rvType.rvParameters.hasDoubleArrayParameter() }
        val structured = buildJsonObject {
            putJsonArray("distributions") {
                families.forEach { f -> add(familyDescriptorJson(f)) }
            }
        }
        val continuous = families.filter { it.kind == ksl.app.dist.config.DistributionKind.CONTINUOUS }
        val discrete = families.filter { it.kind == ksl.app.dist.config.DistributionKind.DISCRETE }
        val summary = buildString {
            appendLine("${families.size} scalar-parameter distribution families available:")
            appendLine("  Continuous (${continuous.size}): ${continuous.joinToString(", ") { it.displayName }}")
            append("  Discrete (${discrete.size}): ${discrete.joinToString(", ") { it.displayName }}")
        }
        return result(summary, structured)
    }

    private fun familyDescriptorJson(f: DistributionFamilyDescriptor): JsonObject {
        val rvParams = f.rvType.rvParameters
        return buildJsonObject {
            put("familyId", f.id)
            put("displayName", f.displayName)
            put("kind", f.kind.name)
            putJsonObject("parameters") {
                rvParams.doubleParameterNames.forEach { name ->
                    putJsonObject(name) {
                        put("type", "double")
                        put("default", rvParams.doubleParameter(name))
                    }
                }
                rvParams.integerParameterNames.forEach { name ->
                    putJsonObject(name) {
                        put("type", "integer")
                        put("default", rvParams.integerParameter(name))
                    }
                }
            }
        }
    }

    /**
     * `generate_variates` — samples `n` random values from a named scalar-parameter
     * distribution family. Parameters default to the catalog's built-in defaults when
     * not supplied; only the values you want to override need to be passed. Returns the
     * sample inline as a JSON array of numbers (`structuredContent.values`). Use
     * `list_distributions` to discover available `familyId`s and their parameter names.
     */
    fun generateVariates(arguments: JsonObject?): CallToolResult {
        val familyId = arguments.string("familyId")
            ?: return error("missing required argument 'familyId'")
        val n = arguments.int("n")
            ?: return error("missing required argument 'n'")
        if (n < 1) return error("'n' must be at least 1")
        if (n > MAX_VARIATES) return error("'n' must be <= $MAX_VARIATES; requested $n")

        val family = FittingCatalog.familyOrNull(familyId)
            ?: return error("unknown familyId '$familyId' — call list_distributions for available families")

        val rvParams = family.rvType.rvParameters
        if (rvParams.hasDoubleArrayParameter()) {
            return error("'$familyId' requires array parameters and is not supported by generate_variates")
        }

        val suppliedParams = arguments?.get("parameters") as? JsonObject
        if (suppliedParams != null) {
            for ((key, value) in suppliedParams) {
                val dbl = (value as? JsonPrimitive)?.doubleOrNull
                    ?: return error("parameter '$key' must be a number, got: $value")
                if (!rvParams.containsParameter(key)) {
                    val valid = (rvParams.doubleParameterNames + rvParams.integerParameterNames).joinToString(", ")
                    return error("unknown parameter '$key' for '$familyId' — valid parameters: $valid")
                }
                rvParams.changeParameter(key, dbl)
            }
        }

        val values = try {
            rvParams.createRVariable().sample(n)
        } catch (e: Exception) {
            return error("failed to generate variates for '$familyId': ${e.message}")
        }

        // Write the full sample to a CSV when the caller opts in (output=true) or the
        // sample is large (auto, > INLINE_THRESHOLD) — so a big request never returns a
        // giant inline array. The file holds every value; the inline `values` carry at
        // most INLINE_THRESHOLD as a leading preview.
        val name = arguments.string("name") ?: familyId
        val wantsFile = (arguments?.get("output")?.jsonPrimitive?.booleanOrNull ?: false) || n > INLINE_THRESHOLD
        val filePath: java.nio.file.Path? = if (wantsFile) {
            try {
                writeVariatesCsv(name, values)
            } catch (e: Exception) {
                return error("failed to write the variate sample file: ${e.message}")
            }
        } else {
            null
        }

        val inlineCount = minOf(n, INLINE_THRESHOLD)
        val truncated = inlineCount < n
        val structured = buildJsonObject {
            put("familyId", familyId)
            put("n", n)
            put("truncated", truncated)
            filePath?.let { put("filePath", it.toString()) }
            putJsonArray("values") { for (i in 0 until inlineCount) add(values[i]) }
        }
        val paramsUsed = rvParams.asDoubleMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
        val previewCount = minOf(8, n)
        val preview = (0 until previewCount).joinToString(", ") { values[it].toString() }
        val summary = buildString {
            appendLine("Generated $n variate(s) from ${family.displayName} ($familyId)")
            appendLine("  Parameters: $paramsUsed")
            filePath?.let { appendLine("  Full sample written to: $it") }
            append("  First $previewCount: $preview" + if (n > previewCount) " … (${n - previewCount} more)" else "")
            if (truncated) append("\n  (structuredContent.values holds the first $inlineCount; the complete sample is in the file)")
        }
        return result(summary, structured)
    }

    /**
     * Writes [values] as a single-column CSV (a `<name>` header then one value per line) to
     * `<workspace>/KSL_MCP_APPS/data/<sanitizedName>.csv`, overwriting a same-named file.
     * The name is sanitized to a filesystem-safe form; the file lands beside the other KSL
     * apps' artifacts under the shared workspace.
     */
    private fun writeVariatesCsv(name: String, values: DoubleArray): java.nio.file.Path {
        val safe = ksl.app.config.sanitizeAnalysisName(name)
        val file = workspaceAppDir("data").resolve("$safe.csv").toAbsolutePath()
        val text = buildString {
            appendLine(safe)
            values.forEach { appendLine(it.toString()) }
        }
        java.nio.file.Files.writeString(file, text)
        return file
    }

    /**
     * `summarize_data` — computes the engine's full statistical summary (count, mean,
     * std dev, min/max, variance, confidence interval, skewness, kurtosis, sign counts)
     * and an equal-bin histogram over an arbitrary numeric array. Returns the SAME
     * `DataSummaryDTO` / `HistogramDTO` shapes a distribution fit produces, so a client
     * gets the engine's exact statistics without running a fit. The histogram is included
     * by default (set `histogram=false` to skip) and omitted when it cannot be binned.
     */
    fun summarizeData(arguments: JsonObject?): CallToolResult {
        val dataArray = arguments?.get("data") as? JsonArray
            ?: return error("missing required argument 'data' (an array of numbers)")
        val data = dataArray.map { it.jsonPrimitive.doubleOrNull ?: return error("'data' must contain only numbers") }
            .toDoubleArray()
        if (data.isEmpty()) return error("'data' must contain at least one value")
        val name = arguments.string("name") ?: "data"
        val level = arguments.double("confidenceLevel") ?: 0.95
        if (level <= 0.0 || level >= 1.0) return error("'confidenceLevel' must be between 0 and 1 (exclusive)")
        val includeHistogram = arguments?.get("histogram")?.jsonPrimitive?.booleanOrNull ?: true

        val stats = ksl.utilities.statistic.Statistic(data)
        val summaryJson = json.encodeToJsonElement(
            ksl.app.dist.result.DataSummaryDTO.serializer(),
            dataSummaryDtoOf(stats, level),
        ).jsonObject

        // A histogram needs spread (at least two distinct values for break points). Build it
        // best-effort and omit it on any failure rather than failing the whole summary.
        val histogramJson = if (includeHistogram && data.size >= 2) {
            runCatching {
                json.encodeToJsonElement(
                    ksl.app.dist.result.HistogramDTO.serializer(),
                    histogramDtoOf(data),
                ).jsonObject
            }.getOrNull()
        } else {
            null
        }
        return dataSummaryResult(name, summaryJson, histogramJson)
    }

    /** Parses the required `data` argument (a non-empty array of numbers) into a DoubleArray, or null. */
    private fun dataArg(arguments: JsonObject?): DoubleArray? {
        val arr = arguments?.get("data") as? JsonArray ?: return null
        val values = arr.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
        return values.toDoubleArray().takeIf { it.isNotEmpty() }
    }

    /**
     * `acf_analysis` — the sample autocorrelation function of a data series: the correlation at
     * each lag 1..maxLag, a white-noise significance band (±1.96/√n), and a lag-1 independence
     * verdict — a quick check of whether observations are serially dependent.
     */
    fun acfAnalysis(arguments: JsonObject?): CallToolResult {
        val data = dataArg(arguments) ?: return error("missing or invalid 'data' (a non-empty array of numbers)")
        if (data.size < 3) return error("need at least 3 values to estimate autocorrelation; got ${data.size}")
        val maxLag = (arguments.int("maxLag") ?: minOf(20, data.size / 4)).coerceIn(1, data.size - 1)
        val acf = ksl.utilities.statistic.Statistic.autoCorrelations(data, maxLag)
        val band = 1.96 / kotlin.math.sqrt(data.size.toDouble())
        val lag1 = acf.firstOrNull() ?: 0.0
        val independent = kotlin.math.abs(lag1) < band
        val structured = buildJsonObject {
            put("n", data.size)
            put("maxLag", maxLag)
            put("whiteNoiseBand", band)
            put("lag1", lag1)
            put("independentAtLag1", independent)
            putJsonArray("acf") {
                acf.forEachIndexed { i, v ->
                    add(buildJsonObject { put("lag", i + 1); put("value", v); put("significant", kotlin.math.abs(v) > band) })
                }
            }
        }
        val verdict = if (independent) "no significant lag-1 dependence" else "significant lag-1 dependence"
        // Constant (zero-variance) data yields NaN autocorrelations; keep the payload wire-safe.
        return result("ACF over $maxLag lag(s); lag-1 = $lag1 (band ±$band): $verdict.", sanitizeNonFinite(structured).jsonObject)
    }

    /**
     * `shift_analysis` — the left-shift a distribution fit would apply to this data, computed
     * standalone (otherwise only visible inside a full fit). A positive shift means the data is
     * offset from a lower bound; subtract it before fitting a lower-bounded distribution.
     */
    fun shiftAnalysis(arguments: JsonObject?): CallToolResult {
        val data = dataArg(arguments) ?: return error("missing or invalid 'data' (a non-empty array of numbers)")
        val shift = ksl.utilities.distributions.fitting.PDFModeler.estimateLeftShiftParameter(data)
        val structured = buildJsonObject {
            put("n", data.size)
            put("leftShift", shift)
            put("dataMin", data.minOrNull() ?: 0.0)
            put("shiftRecommended", shift > 0.0)
        }
        val summary = if (shift > 0.0) {
            "Recommended left shift: $shift (subtract it before fitting a lower-bounded distribution)."
        } else {
            "No left shift recommended (shift is 0)."
        }
        return result(summary, sanitizeNonFinite(structured).jsonObject)
    }

    /**
     * `family_frequency_bootstrap` — resamples the data `numSamples` times, re-runs the full fit
     * on each resample, and tallies how often each distribution family is the recommended fit
     * (each cell's `cellLabel` is the family, `proportion` its frequency): a robustness check on
     * a fit recommendation. Heavier than the other analyses — it re-fits per resample.
     */
    fun familyFrequencyBootstrap(arguments: JsonObject?): CallToolResult {
        val data = dataArg(arguments) ?: return error("missing or invalid 'data' (a non-empty array of numbers)")
        val name = arguments.string("name") ?: "data"
        val numSamples = arguments.int("numSamples") ?: 100
        if (numSamples < 1) return error("'numSamples' must be >= 1")
        val streamNumber = arguments.int("streamNumber") ?: 0
        val bootstrapResult = try {
            ksl.app.dist.runner.FittingRunner.familyFrequencyBootstrap(
                dataset = ksl.app.dist.data.NamedDataset(name, data),
                config = ksl.app.dist.config.FamilyBootstrapConfig(numSamples = numSamples, streamNumber = streamNumber),
            )
        } catch (e: Exception) {
            return error("family-frequency bootstrap failed: ${e.message}")
        }
        val structured = json.encodeToJsonElement(
            ksl.app.dist.result.FamilyFrequencyResult.serializer(), bootstrapResult,
        ).jsonObject
        val top = bootstrapResult.frequency.cells.maxByOrNull { it.count }
        val summary = "Family-frequency bootstrap of '$name' ($numSamples resamples): " +
            (top?.let { "'${it.cellLabel}' recommended in ${it.count.toInt()}/$numSamples resamples." } ?: "no families tallied.")
        return result(summary, structured)
    }

    /**
     * `get_fit_data_summary` — projects the data summary (and, for a continuous fit, the
     * histogram) that a retained distribution fit already computed, without re-running
     * anything. Same `DataSummaryDTO` / `HistogramDTO` shapes as `summarize_data`; the
     * histogram is absent for discrete fits.
     */
    fun getFitDataSummary(arguments: JsonObject?): CallToolResult {
        val id = arguments.string("resultId") ?: return error("missing required argument 'resultId'")
        val stored = resultStore.get(id) ?: return error("no retained result '$id'")
        if (stored.kind != ResultKind.FIT) return error("result '$id' is not a distribution fit")
        val payload = stored.payload.jsonObject
        val dataSummary = payload["dataSummary"] as? JsonObject
            ?: return error("fit result '$id' has no data summary")
        val datasetName = payload["datasetName"]?.jsonPrimitive?.contentOrNull ?: id
        // Continuous-only; null/absent for a discrete fit.
        val histogram = payload["histogram"] as? JsonObject
        return dataSummaryResult(datasetName, dataSummary, histogram)
    }

    /** Builds a [DataSummaryDTO] from a [StatisticIfc] — the same mapping the fit extractor uses. */
    private fun dataSummaryDtoOf(
        stats: ksl.utilities.statistic.StatisticIfc,
        level: Double,
    ): ksl.app.dist.result.DataSummaryDTO {
        val sd = stats.statisticData(level)
        val statsDto = ksl.app.dist.result.StatisticDataDTO(
            sd.name, sd.count, sd.average, sd.standardDeviation, sd.standardError,
            sd.halfWidth, sd.confidenceLevel, sd.lowerLimit, sd.upperLimit, sd.min, sd.max,
            sd.sum, sd.variance, sd.deviationSumOfSquares, sd.kurtosis, sd.skewness,
            sd.lag1Covariance, sd.lag1Correlation, sd.vonNeumannLag1TestStatistic, sd.numberMissing,
        )
        return ksl.app.dist.result.DataSummaryDTO(
            statistics = statsDto,
            zeroCount = stats.zeroCount.toInt(),
            negativeCount = stats.negativeCount.toInt(),
            positiveCount = stats.positiveCount.toInt(),
        )
    }

    /** Builds a [HistogramDTO] from an array using the engine's recommended binning. */
    private fun histogramDtoOf(data: DoubleArray): ksl.app.dist.result.HistogramDTO {
        val h = ksl.utilities.statistic.Histogram.create(
            data,
            ksl.utilities.statistic.Histogram.recommendBreakPoints(data),
        )
        val bins = h.histogramData().map { b ->
            ksl.app.dist.result.HistogramBinDTO(
                binNum = b.binNum,
                binLabel = b.binLabel,
                lowerLimit = b.binLowerLimit,
                upperLimit = b.binUpperLimit,
                count = b.binCount,
                cumCount = b.cumCount,
                proportion = b.proportion,
                cumProportion = b.cumProportion,
            )
        }
        return ksl.app.dist.result.HistogramDTO(bins = bins, underFlowCount = h.underFlowCount, overFlowCount = h.overFlowCount)
    }

    /** The shared envelope for `summarize_data` and `get_fit_data_summary`: identical
     *  structuredContent shape and human summary, so the two read the same either way. */
    private fun dataSummaryResult(datasetName: String, dataSummary: JsonObject, histogram: JsonObject?): CallToolResult {
        val structured = buildJsonObject {
            put("datasetName", datasetName)
            put("dataSummary", dataSummary)
            if (histogram != null) put("histogram", histogram)
        }
        return result(dataSummaryText(datasetName, dataSummary, histogram), structured)
    }

    private fun dataSummaryText(datasetName: String, dataSummary: JsonObject, histogram: JsonObject?): String {
        val s = dataSummary["statistics"]?.jsonObject ?: JsonObject(emptyMap())
        return buildString {
            appendLine("Data summary for '$datasetName':")
            appendLine("  count=${s.str("count")}, average=${s.str("average")}, std dev=${s.str("standardDeviation")}")
            appendLine("  min=${s.str("min")}, max=${s.str("max")}, variance=${s.str("variance")}")
            appendLine("  ${s.str("confidenceLevel")} CI: [${s.str("lowerLimit")}, ${s.str("upperLimit")}] (half-width ${s.str("halfWidth")})")
            appendLine("  skewness=${s.str("skewness")}, kurtosis=${s.str("kurtosis")}")
            append(
                "  sign counts — negative=${dataSummary.str("negativeCount")}, " +
                    "zero=${dataSummary.str("zeroCount")}, positive=${dataSummary.str("positiveCount")}",
            )
            val bins = histogram?.get("bins")?.jsonArray
            if (bins != null && bins.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Histogram (${bins.size} bins):")
                appendLine("| Bin | Range | Count | Proportion |")
                appendLine("|---|---|---|---|")
                bins.forEach { b ->
                    val o = b.jsonObject
                    appendLine("| ${o.str("binNum")} | [${o.str("lowerLimit")}, ${o.str("upperLimit")}) | ${o.str("count")} | ${o.str("proportion")} |")
                }
            }
        }.trimEnd()
    }

    /**
     * `get_workspace` — reports the active KSL workspace (shared with the other KSL
     * apps via `~/.ksl/settings.toml`) and the MCP server's app subdirectory under it,
     * where reports and generated data are written.
     */
    fun getWorkspace(): CallToolResult {
        val workspace = settingsStore.activeWorkspace()
        val appDir = ksl.app.session.AppWorkspacePaths.appWorkspaceDir(workspace, ServerConfig.SERVER_APP_FOLDER)
        val settings = settingsStore.settings.value
        val isDefault = settings.workspace.currentDirectory == null
        // Shared with the Swing apps via ~/.ksl/settings.toml (most-recent first).
        val recentWorkspaces = settings.workspace.recent.directories
        val recentConfigurations = settings.configurations.files
        val structured = buildJsonObject {
            put("workspace", workspace.toString())
            put("appDir", appDir.toString())
            put("isDefault", isDefault)
            putJsonArray("recentWorkspaces") { recentWorkspaces.forEach { add(it) } }
            putJsonArray("recentConfigurations") { recentConfigurations.forEach { add(it) } }
        }
        val summary = buildString {
            append("Active KSL workspace: $workspace")
            if (isDefault) append(" (default — no override set)")
            appendLine()
            append("The MCP server writes reports and generated data under: $appDir")
            if (recentWorkspaces.isNotEmpty()) {
                appendLine()
                append("Recent workspaces: ${recentWorkspaces.joinToString(", ")}")
            }
            if (recentConfigurations.isNotEmpty()) {
                appendLine()
                append("Recent configurations: ${recentConfigurations.joinToString(", ")}")
            }
        }
        return result(summary, structured)
    }

    /**
     * `set_workspace` — points the active KSL workspace at `path` and persists it to
     * `~/.ksl/settings.toml` (the same file the Swing apps read, so the change is shared
     * across all KSL applications). The directory must already exist; this does not
     * create it (mirrors the Swing apps).
     */
    fun setWorkspace(arguments: JsonObject?): CallToolResult {
        val pathStr = arguments.string("path") ?: return error("missing required argument 'path'")
        val path = try {
            java.nio.file.Path.of(pathStr)
        } catch (e: Exception) {
            return error("invalid path '$pathStr': ${e.message}")
        }
        if (!java.nio.file.Files.exists(path)) {
            return error("path does not exist: '$pathStr' — create the directory first, then set it")
        }
        if (!java.nio.file.Files.isDirectory(path)) {
            return error("path is not a directory: '$pathStr'")
        }
        val previous = settingsStore.activeWorkspace().toString()
        settingsStore.setCurrentDirectory(path)
        val workspace = settingsStore.activeWorkspace()
        val appDir = ksl.app.session.AppWorkspacePaths.appWorkspaceDir(workspace, ServerConfig.SERVER_APP_FOLDER)
        val structured = buildJsonObject {
            put("workspace", workspace.toString())
            put("appDir", appDir.toString())
            put("previous", previous)
        }
        val summary = buildString {
            appendLine("Workspace set to: $workspace")
            appendLine("The MCP server will write reports and generated data under: $appDir")
            append("(Previous workspace: $previous)")
        }
        return result(summary, structured)
    }

    /** The structured-output envelope: complete human summary in text + full data as structuredContent. */
    private fun result(summary: String, structured: JsonObject): CallToolResult =
        CallToolResult(content = listOf(TextContent(summary)), structuredContent = structured)

    private fun text(body: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(body)))

    private fun error(message: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(message)), isError = true)

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.int(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.intOrNull

    private fun JsonObject?.double(key: String): Double? =
        this?.get(key)?.jsonPrimitive?.doubleOrNull

    override fun close() {
        runService.close()
        scope.cancel()
    }
}
