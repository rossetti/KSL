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

package ksl.server.rest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.result.FitResultData
import ksl.app.config.RunConfigurationJson
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationRunConfigurationJson
import ksl.app.dist.session.FitResult
import ksl.app.validation.ValidationResult
import ksl.service.capability.run.RunTemplates
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.service.capability.fit.FitDocuments
import ksl.service.capability.fit.FitService
import ksl.service.capability.run.BundleRegistry
import ksl.service.capability.run.ExperimentFactorSpec
import ksl.service.capability.run.RunInputs
import ksl.app.config.ModelReference
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.service.capability.run.ExperimentDocuments
import ksl.service.capability.run.CacheVersion
import ksl.service.capability.run.IncrementalCombine
import ksl.service.capability.run.IncrementalRunCache
import ksl.service.capability.run.ResultKeys
import ksl.service.capability.run.RunService
import ksl.app.config.OutputConfig
import ksl.app.config.ReportFormat
import ksl.service.capability.dbanalysis.DbExportFormat
import ksl.service.capability.dbanalysis.DbQueryResult
import ksl.service.capability.dbanalysis.DbReportResult
import ksl.service.capability.dbanalysis.DbStatusDto
import ksl.service.capability.dbanalysis.ExperimentInfoDto
import ksl.service.capability.dbanalysis.ResultDatabaseService
import ksl.service.capability.report.ReportArtifactService
import ksl.service.capability.report.ReportRequest
import ksl.service.capability.report.TraceReport
import ksl.service.capability.report.WelchReport
import ksl.service.capability.run.dto.ArtifactRef
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.config.ConfigDocuments
import ksl.service.preview.DocumentPreview
import ksl.service.capability.run.dto.mapping.toDto
import ksl.service.capability.run.dto.mapping.withArtifacts
import ksl.service.capability.run.schema.SchemaTranslator
import ksl.service.job.JobHandleView
import ksl.service.job.JobManager
import ksl.service.job.JobStatus
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultKind
import ksl.service.store.ResultStore
import ksl.service.store.StoredResult
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The transport-agnostic application service behind the REST routes: it holds
 * the run capability's [RunService] + [JobManager] (so runs stream through the
 * replayable journal) and the [FitService], over a shared [BundleRegistry]. The
 * Ktor module ([kslRestModule]) is a thin serialization layer over this.
 */
class KslRestService(
    private val registry: BundleRegistry,
    maxConcurrentJobs: Int = Runtime.getRuntime().availableProcessors(),
    private val resultStore: ResultStore = ResultStore(),
    private val artifactStore: ArtifactStore = ArtifactStore(),
    runDeadline: kotlin.time.Duration? = null,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runService = RunService.fromRegistry(registry, runDeadline = runDeadline)
    private val runJobs = JobManager<RunEvent, RunResult>(scope, maxConcurrentJobs)
    private val fitService = FitService()
    private val reportArtifacts = ReportArtifactService()
    private val json = Json {
        encodeDefaults = true
        allowSpecialFloatingPointValues = true // ControlData bounds can be ±∞
    }

    // jobId -> the content key + kind + request a live run will be stored under
    // when it terminates (store-on-completion). Bounded by the JobManager's own
    // retention; cleared lazily when a result is stored.
    private val pending = ConcurrentHashMap<String, ResultMeta>()

    private data class ResultMeta(
        val resultId: String,
        val kind: ResultKind,
        val request: JsonElement,
        val identity: String? = null,
        val replications: Int? = null,
        val topUp: TopUp? = null,
        // Post-run reporting: the server-owned capture dir and the reports to
        // render from it once the run completes (null when nothing was captured).
        val outputDir: Path? = null,
        val reportRequest: ReportRequest? = null,
    )

    private data class TopUp(val cachedResultId: String, val reuseN: Int)

    fun listBundles() = registry.listBundles()

    fun listModels(bundleId: String): List<String> = registry.listModels(bundleId)

    fun modelExists(bundleId: String, modelId: String): Boolean =
        registry.listModels(bundleId).any { it == modelId }

    /** A model's identity plus catalog-led input/output JSON Schemas, or null. */
    fun describe(bundleId: String, modelId: String): JsonObject? {
        val descriptor = try {
            registry.describeModel(bundleId, modelId)
        } catch (e: Exception) {
            null
        } ?: return null
        return buildJsonObject {
            put("modelIdentifier", descriptor.modelIdentifier)
            put("modelName", descriptor.modelName)
            putJsonArray("supportedApps") { registry.modelKinds(bundleId, modelId).forEach { add(it.name) } }
            putJsonArray("responseNames") { descriptor.responseNames.forEach { add(it) } }
            put("hasCatalog", descriptor.catalog != null)
            put("inputSchema", SchemaTranslator.inputSchema(descriptor))
            put("outputSchema", SchemaTranslator.outputSchema(descriptor))
        }
    }

    // ----- authoring help (Phase 8.3) -----

    /** A ready-to-edit RunConfiguration scaffold document (JSON), or null if unknown. */
    fun runTemplateDocument(bundleId: String, modelId: String): String? {
        val descriptor = registry.describeModel(bundleId, modelId) ?: return null
        return RunConfigurationJson.encode(RunTemplates.runDocument(descriptor, modelId))
    }

    /** Validates a RunConfiguration document; throws [IllegalArgumentException] if malformed. */
    fun validateRunDocument(documentText: String): ValidationReport {
        val config = try {
            ConfigDocuments.decodeRun(documentText)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid RunConfiguration document: ${e.message}")
        }
        return runService.validateRunConfig(config).toReport()
    }

    /** Validates an OptimizationRunConfiguration document. */
    fun validateOptimizationDocument(documentText: String): ValidationReport {
        val config = try {
            ConfigDocuments.decodeOptimization(documentText)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid OptimizationRunConfiguration document: ${e.message}")
        }
        return runService.validateOptimizationConfig(config).toReport()
    }

    private fun ValidationResult.toReport(): ValidationReport = ValidationReport(
        valid = isValid,
        errors = errors.map { ValidationIssue(it.path, it.message, it.code) },
        warnings = warnings.map { ValidationIssue(it.path, it.message, it.code) },
    )

    // ----- runs (job-shaped) -----

    /**
     * Registers a single run; returns its job id. [inputs] (the keys
     * `describe_model` advertises) are routed to control / RV overrides via
     * [RunInputs]. Throws [IllegalArgumentException] on an unknown input key and
     * [ksl.service.job.JobAtCapacityException] at capacity.
     */
    fun submitRun(
        bundleId: String,
        modelId: String,
        replications: Int?,
        length: Double?,
        inputs: Map<String, Double>,
        replicationSet: Int? = null,
        antithetic: Boolean? = null,
    ): RunSubmission {
        val descriptor = registry.describeModel(bundleId, modelId)
            ?: throw IllegalArgumentException("unknown model '$modelId' in bundle '$bundleId'")
        if (replicationSet != null) {
            require(replicationSet >= 0) { "replicationSet must be >= 0; got $replicationSet" }
        }
        val bound = RunInputs.bind(descriptor, inputs)
        // replicationSet selects an independent, reproducible realization; the stride is
        // k × the effective rep count so the substream blocks don't overlap.
        val effectiveReps = replications ?: descriptor.experimentRunDefaults.numberOfReplications
        val streamAdvances = replicationSet?.let { RunService.streamAdvancesFor(it, effectiveReps) }
        // Build the document so a flattened run shares the cache with the
        // equivalent document run (same key) — identical to the MCP path.
        val config = runService.singleRunConfig(
            modelId, replications, length, bound.controlOverrides, bound.rvOverrides,
            streamAdvances = streamAdvances, antithetic = antithetic,
        )
        return acceptRunIncremental(config)
    }

    /**
     * The cache-aware run-submit shared by the flattened and document run paths.
     * Exact hit → a terminal cached id; otherwise, when the replication count grew
     * over a cached shorter run of the same identity, register a job for only the
     * missing replications (the combine happens on result fetch) and report
     * `reusedReplications`; else register the full run. Eligible runs record their
     * identity so completion feeds the run-identity family index.
     */
    private fun acceptRunIncremental(config: ksl.app.config.RunConfiguration): RunSubmission {
        val salt = CacheVersion.forRun(registry, config)
        val resultId = ResultKeys.forRunConfig(config, salt)
        if (resultStore.get(resultId) != null) {
            return RunSubmission(resultId = resultId, cached = true, jobId = resultId, status = JobStatus.TERMINAL)
        }
        val m = IncrementalRunCache.replications(config)
        val identity = if (m != null && IncrementalRunCache.eligible(config)) IncrementalRunCache.runIdentity(config, salt) else null
        val topUp = identity?.let { planTopUp(it, m!!) }
        val baseConfig = if (topUp != null) IncrementalRunCache.topUpConfig(config, topUp.reuseN) else config
        // When the run captures Welch/trace data, redirect its output into a
        // server-owned per-result dir so reporting can find it, and remember the
        // reports to render once the run completes.  The output directory is an
        // execution detail, not part of the logical request — resultId was keyed
        // off the original config above, so caching is unaffected.
        val reportRequest = reportRequestFor(config.outputConfig)
        // Redirect output into the server-owned per-result dir when the run produces
        // any managed output: Welch/trace reports OR a KSL database (for analysis).
        val capturesOutput = reportRequest != null || config.outputConfig.enableKSLDatabase
        val captureDir = if (capturesOutput) artifactStore.outputDirFor(resultId) else null
        val runConfig = if (captureDir != null) {
            baseConfig.copy(outputConfig = baseConfig.outputConfig.copy(outputDirectory = captureDir.toString()))
        } else baseConfig
        val jobId = runJobs.register { runService.submitRunConfig(runConfig) }.jobId
        pending[jobId] = ResultMeta(
            resultId, ResultKind.RUN, json.parseToJsonElement(RunConfigurationJson.encode(config)),
            identity, m, topUp, outputDir = captureDir, reportRequest = reportRequest,
        )
        return RunSubmission(resultId, cached = false, jobId = jobId, status = JobStatus.RUNNING, reusedReplications = topUp?.reuseN ?: 0)
    }

    /**
     * The default reports to render for a run, derived from its capture toggles:
     * a Welch report when Welch analysis was captured, a trace report when
     * response tracing was captured. Null when the run captured neither (no
     * post-run reporting — the common case). Report formatting uses sensible
     * defaults (HTML); finer control can ride the request envelope later.
     */
    private fun reportRequestFor(outputConfig: OutputConfig): ReportRequest? {
        val request = ReportRequest(
            welch = if (outputConfig.enableWelchAnalysis) WelchReport() else null,
            trace = if (outputConfig.enableResponseTrace) TraceReport() else null,
        )
        return if (request.isEmpty) null else request
    }

    /** Renders any requested reports from the run's capture output into the result's
     *  artifact dir, before persistence so they are listed with the result. Best-effort. */
    private fun materializeReports(meta: ResultMeta) {
        val request = meta.reportRequest ?: return
        val outputDir = meta.outputDir ?: return
        runCatching { reportArtifacts.materialize(artifactStore.dirFor(meta.resultId), outputDir, request) }
    }

    private fun planTopUp(identity: String, target: Int): TopUp? {
        val best = resultStore.familyMembers(identity).filterKeys { it < target }.maxByOrNull { it.key } ?: return null
        val dto = resultStore.get(best.value)?.payload
            ?.let { runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), it) }.getOrNull() }
        val usable = dto is RunResultDto.Completed && dto.responses.all { it.sum != null && it.deviationSumOfSquares != null }
        return if (usable) TopUp(best.value, best.key) else null
    }

    /**
     * Either returns a cached result id (no run) when an identical request is
     * already retained, or registers a live job and records what content key it
     * will be stored under when it terminates ([runResult] performs the
     * store-on-completion). On a cache hit the returned `jobId` *is* the result
     * id, which resolves through the store fallback in [runStatus]/[runResult] —
     * so the existing `/runs/{id}/result` flow serves cached results transparently.
     */
    private fun acceptOrCached(
        resultId: String,
        kind: ResultKind,
        request: JsonElement,
        submit: () -> JobHandleView<RunEvent, RunResult>,
    ): RunSubmission {
        if (resultStore.get(resultId) != null) {
            return RunSubmission(resultId = resultId, cached = true, jobId = resultId, status = JobStatus.TERMINAL)
        }
        val jobId = runJobs.register { submit() }.jobId
        pending[jobId] = ResultMeta(resultId, kind, request)
        return RunSubmission(resultId = resultId, cached = false, jobId = jobId, status = JobStatus.RUNNING)
    }

    /**
     * Document-centric submit: decodes, validates, and runs a complete
     * [ksl.app.config.RunConfiguration] document. Throws [IllegalArgumentException]
     * on a malformed or invalid document (→ 400).
     */
    fun submitRunDocument(documentText: String): RunSubmission {
        val config = try {
            ConfigDocuments.decodeRun(documentText)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid RunConfiguration document: ${e.message}")
        }
        val validation = runService.validateRunConfig(config)
        require(validation.isValid) {
            "invalid configuration: " + validation.errors.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        return acceptRunIncremental(config)
    }

    /** Document-centric submit for a complete optimization document. */
    fun submitOptimizationDocument(documentText: String): RunSubmission {
        val config = try {
            ConfigDocuments.decodeOptimization(documentText)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid OptimizationRunConfiguration document: ${e.message}")
        }
        val validation = runService.validateOptimizationConfig(config)
        require(validation.isValid) {
            "invalid configuration: " + validation.errors.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        return acceptOrCached(
            ResultKeys.forOptimizationConfig(config, CacheVersion.forOptimization(registry, config)),
            ResultKind.OPTIMIZATION,
            json.parseToJsonElement(OptimizationRunConfigurationJson.encode(config)),
        ) { runService.submitOptimizationConfig(config) }
    }

    /**
     * Registers an optimization run in the same run JobManager; returns its job
     * id. Progress and result are then available via the `/runs/{id}/...`
     * endpoints (an optimization is just another job on the run spine).
     */
    fun submitOptimization(
        modelId: String,
        objectiveResponse: String,
        inputs: List<OptimizationInputSpec>,
        maxIterations: Int,
        replicationsPerEvaluation: Int,
        maximize: Boolean,
    ): RunSubmission {
        val config = runService.optimizationRunConfig(
            modelId, objectiveResponse, inputs, maxIterations, replicationsPerEvaluation, maximize,
        )
        return acceptOrCached(
            ResultKeys.forOptimizationConfig(config, CacheVersion.forOptimization(registry, config)),
            ResultKind.OPTIMIZATION,
            json.parseToJsonElement(OptimizationRunConfigurationJson.encode(config)),
        ) { runService.submitOptimizationConfig(config) }
    }

    /**
     * Registers a designed-experiment run in the same run JobManager. No
     * serializable document backs an experiment, so it keys on a canonical
     * request (model + rep count + name-sorted factors). Result/progress flow
     * through the `/runs/{id}/...` endpoints.
     */
    fun submitExperiment(
        modelId: String,
        factors: List<ExperimentFactorSpec>,
        numRepsPerDesignPoint: Int?,
    ): RunSubmission {
        val request = buildJsonObject {
            put("modelId", modelId)
            numRepsPerDesignPoint?.let { put("numRepsPerDesignPoint", it) }
            putJsonArray("factors") {
                factors.sortedBy { it.name }.forEach { add(json.encodeToJsonElement(ExperimentFactorSpec.serializer(), it)) }
            }
        }
        return acceptOrCached(ResultStore.sha256("${registry.versionSaltFor(listOf(modelId))}|experiment:$request"), ResultKind.BATCH, request) {
            runService.submitExperiment(modelId, factors, numRepsPerDesignPoint)
        }
    }

    // ----- experiment documents (Tier B) -----

    /** A ready-to-edit ExperimentConfiguration scaffold (JSON), or null if the model is unknown. */
    fun experimentTemplateDocument(bundleId: String, modelId: String): String? {
        val descriptor = registry.describeModel(bundleId, modelId) ?: return null
        // template() throws IllegalArgumentException for < 2 numeric controls (→ 400).
        return ExperimentDocuments.encode(ExperimentDocuments.template(descriptor, modelId))
    }

    /** Validates an ExperimentConfiguration document; throws [IllegalArgumentException] if malformed/unknown model. */
    fun validateExperimentDocument(documentText: String): ValidationReport {
        val config = decodeExperiment(documentText)
        val descriptor = experimentDescriptor(config)
            ?: throw IllegalArgumentException("the document references an unknown model")
        return ExperimentDocuments.validate(config, descriptor).toReport()
    }

    /** Document-centric experiment submit (job-shaped; result/progress via `/runs/{id}/...`). */
    fun submitExperimentDocument(documentText: String): RunSubmission {
        val config = decodeExperiment(documentText)
        val descriptor = experimentDescriptor(config)
            ?: throw IllegalArgumentException("the document references an unknown model")
        val validation = ExperimentDocuments.validate(config, descriptor)
        require(validation.isValid) {
            "invalid configuration: " + validation.errors.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        return acceptOrCached(
            ExperimentDocuments.key(config, CacheVersion.forExperiment(registry, config)), ResultKind.BATCH, json.parseToJsonElement(ExperimentDocuments.encode(config)),
        ) { runService.submitExperimentConfig(config) }
    }

    private fun decodeExperiment(documentText: String): ExperimentConfiguration =
        try {
            ConfigDocuments.decodeExperiment(documentText)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid ExperimentConfiguration document: ${e.message}")
        }

    private fun experimentDescriptor(config: ExperimentConfiguration) =
        (config.modelReference as? ModelReference.ByProviderId)?.providerId?.let { registry.descriptorForModelId(it) }

    // ----- preview: canonical echo + workload/cost (Tier C) -----

    /** Canonical RunConfiguration + workload; throws [IllegalArgumentException] if malformed. */
    fun previewRunDocument(documentText: String): JsonObject =
        DocumentPreview.forRun(decodeRun(documentText))

    /** Canonical OptimizationRunConfiguration + its iteration/replication budget. */
    fun previewOptimizationDocument(documentText: String): JsonObject =
        DocumentPreview.forOptimization(decodeOptimization(documentText))

    /** Canonical ExperimentConfiguration + its design-point/replication count. */
    fun previewExperimentDocument(documentText: String): JsonObject =
        DocumentPreview.forExperiment(decodeExperiment(documentText))

    /** Canonical FitConfiguration + its dataset/estimator counts. */
    fun previewFitDocument(documentText: String): JsonObject =
        DocumentPreview.forFit(decodeFit(documentText))

    private fun decodeRun(text: String) = try {
        ConfigDocuments.decodeRun(text)
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid RunConfiguration document: ${e.message}")
    }

    private fun decodeOptimization(text: String) = try {
        ConfigDocuments.decodeOptimization(text)
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid OptimizationRunConfiguration document: ${e.message}")
    }

    private fun decodeFit(text: String) = try {
        ConfigDocuments.decodeFit(text)
    } catch (e: Exception) {
        throw IllegalArgumentException("invalid FitConfiguration document: ${e.message}")
    }

    /** Replayable event flow for SSE (from offset 0; completes when the run ends). */
    fun runEvents(jobId: String): Flow<RunEvent>? = runJobs.events(jobId)

    /** Live job status, or TERMINAL for a content-key id already retained in the store. */
    fun runStatus(jobId: String): JobStatus? =
        runJobs.status(jobId) ?: if (resultStore.get(jobId) != null) JobStatus.TERMINAL else null

    /**
     * The terminal result DTO; only call once [runStatus] is TERMINAL. For a live
     * job this also performs the store-on-completion (retaining the result under
     * its content key for caching + projection); for a content-key id it reads
     * the retained result back from the store.
     */
    suspend fun runResult(jobId: String): RunResultDto? {
        runJobs.result(jobId)?.let { result ->
            val dto = result.toDto()
            val meta = pending.remove(jobId)
            if (meta != null && resultStore.get(meta.resultId) == null) {
                // Render reports from the capture output BEFORE persistRun, so the
                // freshly-written artifacts are listed onto the stored result.
                materializeReports(meta)
                val topUp = meta.topUp
                if (topUp != null) {
                    // Incremental: combine the (M−N)-rep top-up with the cached N-rep run.
                    val cachedDto = resultStore.get(topUp.cachedResultId)?.payload
                        ?.let { runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), it) }.getOrNull() }
                    if (cachedDto !is RunResultDto.Completed || dto !is RunResultDto.Completed) {
                        // Base evicted before completion — cannot assemble the full result.
                        return null
                    }
                    val combined = IncrementalCombine.completed(cachedDto, dto)
                    val storedCombined = persistRun(meta.resultId, meta.request, combined)
                    indexFamily(meta)
                    return storedCombined
                }
                val stored = persistRun(meta.resultId, meta.request, dto)
                if (dto is RunResultDto.Completed) indexFamily(meta)
                return stored
            }
            return dto
        }
        // A cached / content-key id: serve the retained result.
        return resultStore.get(jobId)?.let { json.decodeFromJsonElement(RunResultDto.serializer(), it.payload) }
    }

    /**
     * Stores [dto] under [resultId], first attaching any artifacts a capability
     * has materialized for the result (empty until the reporting phases). Returns
     * the artifact-enriched DTO so callers hand the client the same payload that
     * was retained.
     */
    private fun persistRun(resultId: String, request: JsonElement, dto: RunResultDto): RunResultDto {
        val enriched = dto.withArtifacts(artifactStore.list(resultId))
        resultStore.put(
            StoredResult(
                resultId = resultId,
                kind = ResultKind.RUN,
                createdAt = Clock.System.now(),
                request = request,
                payload = json.encodeToJsonElement(RunResultDto.serializer(), enriched),
            ),
        )
        return enriched
    }

    /** The artifacts (rendered reports, plot images, exports) retained for a result. */
    fun artifacts(resultId: String): List<ArtifactRef> = artifactStore.list(resultId)

    /** Resolves one artifact file by name within a result, or null if absent / escaping the dir. */
    fun artifactFile(resultId: String, name: String): Path? = artifactStore.resolve(resultId, name)

    // ----- result database analysis (Phase C; by resultId, stateless) -----

    private val resultDb = ResultDatabaseService()

    /** Whether the result has an analyzable database (always succeeds). */
    fun dbStatus(resultId: String): DbStatusDto = resultDb.status(artifactStore.outputDirFor(resultId))

    /** The experiments in the result's database, or null when there is none. */
    fun dbExperiments(resultId: String): List<ExperimentInfoDto>? =
        resultDb.experiments(artifactStore.outputDirFor(resultId))

    /** Across-replication summary statistics for one experiment as JSON. */
    fun dbSummary(resultId: String, experimentName: String): DbQueryResult =
        resultDb.summary(artifactStore.outputDirFor(resultId), experimentName)

    /** Multiple-comparison analysis of a response as JSON. */
    fun dbCompare(
        resultId: String,
        responseName: String,
        experimentNames: List<String>?,
        delta: Double,
        level: Double,
    ): DbQueryResult =
        resultDb.compare(artifactStore.outputDirFor(resultId), responseName, experimentNames, delta, level)

    /** Renders a comparison (MCB) report into the result's artifact dir (Phase C+). */
    fun dbCompareReport(
        resultId: String,
        responseName: String,
        experimentNames: List<String>?,
        delta: Double,
        level: Double,
        formats: Set<ReportFormat>,
    ): DbReportResult =
        resultDb.renderComparisonReport(
            artifactStore.outputDirFor(resultId), artifactStore.dirFor(resultId),
            responseName, experimentNames, delta, level, formats,
        )

    /** Exports the result's database into its artifact dir (Phase C+). */
    fun dbExport(resultId: String, format: DbExportFormat): DbReportResult =
        resultDb.exportDatabase(artifactStore.outputDirFor(resultId), artifactStore.dirFor(resultId), format)

    /** Renders a single-experiment summary report into the result's artifact dir (Phase C+). */
    fun dbSummaryReport(
        resultId: String,
        experimentName: String,
        level: Double,
        showPlots: Boolean,
        formats: Set<ReportFormat>,
    ): DbReportResult =
        resultDb.renderExperimentSummaryReport(
            artifactStore.outputDirFor(resultId), artifactStore.dirFor(resultId),
            experimentName, level, showPlots, formats,
        )

    private fun indexFamily(meta: ResultMeta) {
        val identity = meta.identity ?: return
        val replications = meta.replications ?: return
        resultStore.indexFamily(identity, replications, meta.resultId)
    }

    // ----- retained-result projection (Phase 8.5 over REST) -----

    /**
     * A retained result's payload, optionally narrowed to [fields] (a set of
     * top-level keys — the REST `?fields=` projection), or null if unknown.
     */
    fun storedResult(resultId: String, fields: Set<String> = emptySet()): JsonObject? {
        val payload = resultStore.get(resultId)?.payload?.jsonObject ?: return null
        if (fields.isEmpty()) return payload
        return buildJsonObject { payload.forEach { (k, v) -> if (k in fields) put(k, v) } }
    }

    /** The response names in a retained result, or null if unknown. */
    fun storedResponseNames(resultId: String): List<String>? {
        val payload = resultStore.get(resultId)?.payload?.jsonObject ?: return null
        return responsesOf(payload).map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    /** One response's statistics from a retained result, or null if the id/name is unknown. */
    fun storedResponse(resultId: String, name: String): JsonObject? {
        val payload = resultStore.get(resultId)?.payload?.jsonObject ?: return null
        return responsesOf(payload).firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == name }?.jsonObject
    }

    /** One design-point/scenario result from a retained batch, or null if out of range. */
    fun storedDesignPoint(resultId: String, index: Int): JsonObject? {
        val items = resultStore.get(resultId)?.payload?.jsonObject?.get("items")?.jsonArray ?: return null
        return items.getOrNull(index)?.jsonObject
    }

    private fun responsesOf(payload: JsonObject): List<JsonElement> =
        when (payload["type"]?.jsonPrimitive?.contentOrNull) {
            "completed" -> payload["responses"]?.jsonArray?.toList() ?: emptyList()
            "batch" -> payload["items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("responses")?.jsonArray?.toList() ?: emptyList()
            else -> emptyList()
        }

    fun cancelRun(jobId: String, reason: String) = runJobs.cancel(jobId, reason)

    /** One run event as a small JSON object for an SSE `data:` payload. */
    fun runEventJson(event: RunEvent): JsonObject = buildJsonObject {
        put("type", event::class.simpleName ?: "RunEvent")
        put("detail", event.toString())
    }

    // ----- fits (awaited; ResultStore-backed for caching + projection) -----

    /** The flat fit path: builds a FitConfiguration from data, runs it cached, returns the full result. */
    suspend fun fit(data: DoubleArray, name: String, kind: DistributionKind): FitResultData {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf(name to data)),
            kind = kind,
            estimatorIds = FittingCatalog.defaultEstimatorIds(kind),
            scoringModelIds = if (kind == DistributionKind.CONTINUOUS) FittingCatalog.defaultScoringModelIds() else emptySet(),
        )
        return json.decodeFromJsonElement(FitResultData.serializer(), runFit(config).stored.payload)
    }

    /** A ready-to-edit FitConfiguration scaffold (JSON) for a kind. */
    fun fitTemplateDocument(kind: DistributionKind): String =
        FitDocuments.encode(FitDocuments.template(kind))

    /** Validates a FitConfiguration document; throws [IllegalArgumentException] if malformed. */
    fun validateFitDocument(documentText: String): ValidationReport {
        val config = try {
            ConfigDocuments.decodeFit(documentText)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid FitConfiguration document: ${e.message}")
        }
        return FitDocuments.validate(config).toReport()
    }

    /**
     * Document-centric fit: decodes, validates, and runs a complete
     * [FitConfiguration] document (any data source). The result is cached and
     * retained for projection; a compact [FitCard] is returned. Throws
     * [IllegalArgumentException] on a malformed or invalid document (→ 400).
     */
    suspend fun submitFitDocument(documentText: String): FitCard {
        val config = try {
            ConfigDocuments.decodeFit(documentText)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid FitConfiguration document: ${e.message}")
        }
        val validation = FitDocuments.validate(config)
        require(validation.isValid) {
            "invalid configuration: " + validation.errors.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        val cached = runFit(config)
        val payload = cached.stored.payload.jsonObject
        return FitCard(
            resultId = cached.stored.resultId,
            cached = cached.fromCache,
            datasetName = payload["datasetName"]?.jsonPrimitive?.contentOrNull,
            recommended = payload["recommendedFamilyId"]?.jsonPrimitive?.contentOrNull,
            fitCount = payload["fits"]?.jsonArray?.size ?: 0,
        )
    }

    /** Runs a single fit through the ResultStore (cache-on-miss), keyed by the document. */
    private suspend fun runFit(config: FitConfiguration) =
        resultStore.cachedRun(
            key = FitDocuments.key(config),
            kind = ResultKind.FIT,
            request = json.parseToJsonElement(FitDocuments.encode(config)),
            useCache = true,
        ) {
            when (val result = fitService.submit(FitSpec.Single(config)).result.await()) {
                is FitResult.Completed -> json.encodeToJsonElement(FitResultData.serializer(), result.report)
                is FitResult.Failed -> throw IllegalStateException(result.error.message)
                is FitResult.Cancelled -> throw IllegalStateException("fit cancelled: ${result.reason}")
                is FitResult.BatchCompleted -> throw IllegalStateException("unexpected batch result")
            }
        }

    override fun close() {
        fitService.close()
        runService.close()
        scope.cancel()
    }
}

/**
 * The outcome of a run/optimization/experiment submission: the content-addressed
 * [resultId] the result is (or will be) retained under, whether it was already
 * [cached], the [jobId] to poll/stream (equal to [resultId] on a cache hit), and
 * the initial [status].
 */
data class RunSubmission(
    val resultId: String,
    val cached: Boolean,
    val jobId: String,
    val status: JobStatus,
    /** > 0 when this is an incremental top-up: replications reused from a cached shorter run. */
    val reusedReplications: Int = 0,
)
