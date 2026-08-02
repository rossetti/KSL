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

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.dist.config.DistributionKind
import ksl.app.config.ReportFormat
import ksl.service.capability.dbanalysis.DbExportFormat
import ksl.service.capability.dbanalysis.DbQueryResult
import ksl.service.capability.dbanalysis.DbReportResult
import ksl.service.capability.dbanalysis.NO_DATABASE_MESSAGE
import ksl.service.capability.run.ExperimentFactorSpec
import ksl.service.config.HealthEndpoints
import ksl.service.config.ServerAuth
import ksl.service.job.JobAtCapacityException
import ksl.service.job.JobStatus

/** `POST /runs` body. */
/** Body of `POST /results/{id}/database/compare` and `.../compare/report` — the
 *  MCB analysis request. [formats] applies only to the report variant. */
@Serializable
data class CompareRequest(
    val response: String,
    val experiments: List<String>? = null,
    val delta: Double? = null,
    val level: Double? = null,
    val formats: List<String>? = null,
)

/** Body of `POST /results/{id}/database/export` — `format` is "CSV" or "EXCEL". */
@Serializable
data class ExportRequest(val format: String = "CSV")

/** Body of `POST /results/{id}/database/experiments/{exp}/report` — all optional. */
@Serializable
data class SummaryReportRequest(
    val level: Double? = null,
    val showPlots: Boolean? = null,
    val formats: List<String>? = null,
)

/** Response of `GET /results/{id}/database/views` — the available statistical view names. */
@Serializable
data class ViewsResponse(val views: List<String>)

/** Maps a [DbQueryResult] onto the HTTP response: JSON body, a 404 with guidance
 *  when there is no database, or a 422 with the precondition explanation. */
private suspend fun respondDbJson(call: ApplicationCall, result: DbQueryResult) {
    when (result) {
        DbQueryResult.NoDatabase ->
            call.respond(HttpStatusCode.NotFound, StatusResponse(NO_DATABASE_MESSAGE))
        is DbQueryResult.Invalid ->
            call.respond(HttpStatusCode.UnprocessableEntity, StatusResponse(result.reason))
        is DbQueryResult.Json ->
            call.respondText(result.payload, ContentType.Application.Json)
    }
}

/** Maps a file-producing [DbReportResult] onto the response: on success, the
 *  result's full artifact list (the new report/export is downloadable via the
 *  artifacts endpoint); otherwise guidance. */
private suspend fun respondDbReport(call: ApplicationCall, service: KslRestService, resultId: String, result: DbReportResult) {
    when (result) {
        DbReportResult.NoDatabase ->
            call.respond(HttpStatusCode.NotFound, StatusResponse(NO_DATABASE_MESSAGE))
        is DbReportResult.Invalid ->
            call.respond(HttpStatusCode.UnprocessableEntity, StatusResponse(result.reason))
        is DbReportResult.Ok ->
            call.respond(service.artifacts(resultId))
    }
}

/** Parses report-format names (default HTML) into the report-format set. */
private fun parseReportFormats(names: List<String>?): Set<ReportFormat> {
    val parsed = names.orEmpty().mapNotNull { s -> ReportFormat.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
    return parsed.toSet().ifEmpty { setOf(ReportFormat.HTML) }
}

@Serializable
data class RunRequest(
    val bundleId: String,
    val modelId: String,
    val numberOfReplications: Int? = null,
    val lengthOfReplication: Double? = null,
    /** Model inputs as `inputKey -> value` (keys from `describe_model`'s input schema). */
    val inputs: Map<String, Double> = emptyMap(),
    /**
     * Optional (default 0). Independent random-realization selector: 0 is the standard run,
     * and each different value reuses a non-overlapping block of random substreams, so distinct
     * values give independent yet reproducible results. Identical requests reproduce by design.
     */
    val replicationSet: Int? = null,
    /** Optional. Run with antithetic variates (variance reduction). */
    val antithetic: Boolean? = null,
)

/** `POST /fits` body. */
@Serializable
data class FitRequest(
    val data: List<Double>,
    val name: String = "dataset",
    val kind: String = "CONTINUOUS",
)

/** `POST /optimizations` body. */
@Serializable
data class OptimizationRequest(
    val bundleId: String,
    val modelId: String,
    val objectiveResponse: String,
    val inputs: List<OptimizationInputSpec>,
    val maxIterations: Int = 20,
    val replicationsPerEvaluation: Int = 10,
    val maximize: Boolean = false,
)

/** `POST /experiments` body. */
@Serializable
data class ExperimentRequest(
    val bundleId: String,
    val modelId: String,
    val factors: List<ExperimentFactorSpec>,
    val numRepsPerDesignPoint: Int? = null,
)

/**
 * Returned by `POST /runs`, `/optimizations`, `/experiments`, and the
 * `*-configs` endpoints. [resultId] is the content-addressed id the result is
 * (or will be) retained under — fetch projections at `/results/{resultId}`.
 * [cached] is true when an identical request was already retained (no run); the
 * [jobId] then equals [resultId] and `/runs/{jobId}/result` serves it immediately.
 */
@Serializable
data class JobAccepted(
    val jobId: String,
    val status: String,
    val resultId: String = "",
    val cached: Boolean = false,
    val reusedReplications: Int = 0,
)

private fun RunSubmission.toAccepted(): JobAccepted = JobAccepted(jobId, status.name, resultId, cached, reusedReplications)

private fun RunSubmission.httpStatus(): HttpStatusCode =
    if (cached) HttpStatusCode.OK else HttpStatusCode.Accepted

/** A bare status marker (e.g. a run still in flight). */
@Serializable
data class StatusResponse(val status: String)

/** Compact card returned by `POST /fit-configs`: the retained result id plus a headline. */
@Serializable
data class FitCard(
    val resultId: String,
    val cached: Boolean,
    val datasetName: String? = null,
    val recommended: String? = null,
    val fitCount: Int,
)

/** Result of validating a config document without running it. */
@Serializable
data class ValidationReport(
    val valid: Boolean,
    val errors: List<ValidationIssue>,
    val warnings: List<ValidationIssue>,
)

/** One validation message. */
@Serializable
data class ValidationIssue(val path: String, val message: String, val code: String)

private val restJson = Json {
    encodeDefaults = true
    allowSpecialFloatingPointValues = true // ControlData bounds can be ±∞
}

/**
 * The Ktor module exposing the service core over REST + SSE (strategic plan
 * §5.9). Run execution streams over Server-Sent Events — the place the
 * journal's flow drives true server-push, which the stdio MCP transport could
 * not. The same [KslRestService] backs every route.
 *
 * @param ready a readiness probe for `GET /ready` — true once the server has
 *        finished its initial bundle scan (Phase 9 A4). The launcher flips it
 *        after `scanOnce`; defaults to always-ready for tests.
 * @param authToken when non-blank, every request except the `/health`, `/ready`,
 *        and `/version` probes must carry `Authorization: Bearer <token>`
 *        (otherwise 401). Null/blank = no auth (the local-trust default).
 */
fun Application.kslRestModule(
    service: KslRestService,
    ready: () -> Boolean = { true },
    authToken: String? = null,
) {
    install(SSE)
    install(ContentNegotiation) { json(restJson) }

    // Bearer-token gate (only when a token is configured). Runs before routing so
    // it covers every route uniformly; the probe paths stay public.
    if (!authToken.isNullOrBlank()) {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (!ServerAuth.isPublicPath(path) &&
                !ServerAuth.isAuthorized(authToken, call.request.headers["Authorization"])
            ) {
                call.respondText(
                    ServerAuth.unauthorizedJson(),
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                finish()
            }
        }
    }

    routing {
        // ----- health / readiness (Phase 9 A4) -----
        get("/health") {
            call.respondText(HealthEndpoints.healthJson("ksl-rest"), ContentType.Application.Json)
        }
        get("/ready") {
            val isReady = ready()
            call.respondText(
                HealthEndpoints.readyJson(isReady),
                ContentType.Application.Json,
                if (isReady) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
        get("/version") {
            call.respondText(HealthEndpoints.versionJson("ksl-rest"), ContentType.Application.Json)
        }

        // ----- discovery / description -----
        get("/bundles") { call.respond(service.listBundles()) }
        get("/bundles/skipped") { call.respond(service.skippedBundles()) }

        get("/bundles/{bundleId}/models") {
            call.respond(service.listModels(call.parameters["bundleId"]!!))
        }

        get("/bundles/{bundleId}/models/{modelId}") {
            val descriptor = service.describe(call.parameters["bundleId"]!!, call.parameters["modelId"]!!)
                ?: return@get call.respondText(
                    """{"error":"unknown model"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            call.respondText(descriptor.toString(), ContentType.Application.Json)
        }

        // ----- authoring help (Phase 8.3) -----
        get("/bundles/{bundleId}/models/{modelId}/template") {
            val document = service.runTemplateDocument(call.parameters["bundleId"]!!, call.parameters["modelId"]!!)
                ?: return@get call.respondText(
                    """{"error":"unknown model"}""", ContentType.Application.Json, HttpStatusCode.NotFound,
                )
            call.respondText(document, ContentType.Application.Json)
        }

        post("/validate/run") {
            val report = try {
                service.validateRunDocument(call.receiveText())
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(report)
        }

        post("/validate/optimization") {
            val report = try {
                service.validateOptimizationDocument(call.receiveText())
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(report)
        }

        // ----- preview: canonical echo + workload/cost (Tier C) -----
        post("/preview/run") {
            val preview = try {
                service.previewRunDocument(call.receiveText())
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respondText(preview.toString(), ContentType.Application.Json)
        }

        post("/preview/optimization") {
            val preview = try {
                service.previewOptimizationDocument(call.receiveText())
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respondText(preview.toString(), ContentType.Application.Json)
        }

        post("/preview/experiment") {
            val preview = try {
                service.previewExperimentDocument(call.receiveText())
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respondText(preview.toString(), ContentType.Application.Json)
        }

        post("/preview/fit") {
            val preview = try {
                service.previewFitDocument(call.receiveText())
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respondText(preview.toString(), ContentType.Application.Json)
        }

        // ----- runs (job-shaped, SSE progress) -----
        post("/runs") {
            val request = call.receive<RunRequest>()
            if (!service.modelExists(request.bundleId, request.modelId)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    StatusResponse("no model '${request.modelId}' in bundle '${request.bundleId}'"),
                )
            }
            val submission = try {
                service.submitRun(
                    request.bundleId, request.modelId,
                    request.numberOfReplications, request.lengthOfReplication, request.inputs,
                    request.replicationSet, request.antithetic,
                )
            } catch (e: JobAtCapacityException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, StatusResponse("at capacity (${e.limit})"))
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid inputs"))
            }
            call.respond(submission.httpStatus(), submission.toAccepted())
        }

        get("/runs/{jobId}/result") {
            val jobId = call.parameters["jobId"]!!
            when (service.runStatus(jobId)) {
                null -> call.respond(HttpStatusCode.NotFound, StatusResponse("unknown jobId"))
                JobStatus.RUNNING -> call.respond(HttpStatusCode.Accepted, StatusResponse("RUNNING"))
                JobStatus.TERMINAL -> service.runResult(jobId)?.let { call.respond(it) }
                    ?: call.respond(
                        HttpStatusCode.Conflict,
                        StatusResponse("the incremental base run was evicted before completion; please re-submit"),
                    )
            }
        }

        delete("/runs/{jobId}") {
            val jobId = call.parameters["jobId"]!!
            if (service.runStatus(jobId) == null) {
                return@delete call.respond(HttpStatusCode.NotFound, StatusResponse("unknown jobId"))
            }
            service.cancelRun(jobId, "cancelled via REST")
            call.respond(HttpStatusCode.Accepted, StatusResponse("CANCELLING"))
        }

        sse("/runs/{jobId}/events") {
            val jobId = call.parameters["jobId"]!!
            val events = service.runEvents(jobId)
            if (events == null) {
                send(ServerSentEvent(data = """{"error":"unknown jobId"}""", event = "error"))
                return@sse
            }
            events.collect { event ->
                send(ServerSentEvent(data = service.runEventJson(event).toString(), event = "run-event"))
            }
            send(ServerSentEvent(data = """{"done":true}""", event = "done"))
        }

        // ----- multi-objective decision studies -----

        // What a study may name for turning scores into values, so a caller can
        // find out what is on offer rather than guess and be corrected later.
        get("/moda/value-functions") { call.respond(service.modaValueFunctions()) }

        // Checking without running. A study that is merely unwise still reports
        // as runnable, with the remarks alongside.
        post("/moda/validate") {
            val text = call.receiveText()
            val report = try {
                service.checkModaDocument(text)
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(report)
        }

        post("/moda/studies") {
            val text = call.receiveText()
            val submission = try {
                service.submitModaDocument(text)
            } catch (e: JobAtCapacityException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, StatusResponse("at capacity (${e.limit})"))
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(HttpStatusCode.Accepted, submission)
        }

        get("/moda/studies/{jobId}/result") {
            val jobId = call.parameters["jobId"]!!
            when (service.modaStatus(jobId)) {
                null -> call.respond(HttpStatusCode.NotFound, StatusResponse("unknown studyId"))
                JobStatus.RUNNING -> call.respond(HttpStatusCode.Accepted, StatusResponse("RUNNING"))
                JobStatus.TERMINAL -> service.modaResult(jobId)?.let { call.respond(it) }
                    ?: call.respond(HttpStatusCode.NotFound, StatusResponse("unknown studyId"))
            }
        }

        delete("/moda/studies/{jobId}") {
            val jobId = call.parameters["jobId"]!!
            if (service.modaStatus(jobId) == null) {
                return@delete call.respond(HttpStatusCode.NotFound, StatusResponse("unknown studyId"))
            }
            service.cancelModa(jobId, "cancelled via REST")
            call.respond(HttpStatusCode.Accepted, StatusResponse("CANCELLING"))
        }

        sse("/moda/studies/{jobId}/events") {
            val jobId = call.parameters["jobId"]!!
            val events = service.modaEvents(jobId)
            if (events == null) {
                send(ServerSentEvent(data = """{"error":"unknown studyId"}""", event = "error"))
                return@sse
            }
            events.collect { event ->
                send(ServerSentEvent(data = service.modaEventJson(event).toString(), event = "moda-event"))
            }
            send(ServerSentEvent(data = """{"done":true}""", event = "done"))
        }

        // ----- retained-result projection (Phase 8.5 over REST) -----
        get("/results/{resultId}") {
            val fields = call.request.queryParameters["fields"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            val payload = service.storedResult(call.parameters["resultId"]!!, fields)
                ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse("no retained result"))
            call.respondText(payload.toString(), ContentType.Application.Json)
        }

        get("/results/{resultId}/responses") {
            val names = service.storedResponseNames(call.parameters["resultId"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse("no retained result"))
            call.respond(names)
        }

        get("/results/{resultId}/responses/{name}") {
            val response = service.storedResponse(call.parameters["resultId"]!!, call.parameters["name"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse("no such response in result"))
            call.respondText(response.toString(), ContentType.Application.Json)
        }

        get("/results/{resultId}/design-points/{index}") {
            val index = call.parameters["index"]!!.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, StatusResponse("index must be an integer"))
            val point = service.storedDesignPoint(call.parameters["resultId"]!!, index)
                ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse("no such design point"))
            call.respondText(point.toString(), ContentType.Application.Json)
        }

        get("/results/{resultId}/artifacts") {
            call.respond(service.artifacts(call.parameters["resultId"]!!))
        }

        get("/results/{resultId}/artifacts/{name...}") {
            val name = call.parameters.getAll("name")?.joinToString("/").orEmpty()
            val file = service.artifactFile(call.parameters["resultId"]!!, name)
                ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse("no such artifact"))
            call.respondFile(file.toFile())
        }

        // ----- result database analysis (Phase C) -----
        get("/results/{resultId}/database") {
            call.respond(service.dbStatus(call.parameters["resultId"]!!))
        }

        get("/results/{resultId}/database/experiments") {
            val experiments = service.dbExperiments(call.parameters["resultId"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse(NO_DATABASE_MESSAGE))
            call.respond(experiments)
        }

        get("/results/{resultId}/database/views") {
            val names = service.dbViewNames(call.parameters["resultId"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse(NO_DATABASE_MESSAGE))
            call.respond(ViewsResponse(names))
        }

        get("/results/{resultId}/database/views/{view}") {
            val experiment = call.request.queryParameters["experiment"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            respondDbJson(call, service.dbView(call.parameters["resultId"]!!, call.parameters["view"]!!, experiment, limit))
        }

        get("/results/{resultId}/database/experiments/{exp}/summary") {
            respondDbJson(call, service.dbSummary(call.parameters["resultId"]!!, call.parameters["exp"]!!))
        }

        post("/results/{resultId}/database/compare") {
            val req = try {
                call.receive<CompareRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid compare request: ${e.message}"))
            }
            respondDbJson(
                call,
                service.dbCompare(
                    call.parameters["resultId"]!!, req.response, req.experiments,
                    req.delta ?: 0.0, req.level ?: 0.95,
                ),
            )
        }

        post("/results/{resultId}/database/compare/report") {
            val id = call.parameters["resultId"]!!
            val req = try {
                call.receive<CompareRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid compare request: ${e.message}"))
            }
            respondDbReport(
                call, service, id,
                service.dbCompareReport(
                    id, req.response, req.experiments, req.delta ?: 0.0, req.level ?: 0.95,
                    parseReportFormats(req.formats),
                ),
            )
        }

        post("/results/{resultId}/database/export") {
            val id = call.parameters["resultId"]!!
            val req = try {
                call.receive<ExportRequest>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("invalid export request: ${e.message}"))
            }
            val format = DbExportFormat.entries.firstOrNull { it.name.equals(req.format, ignoreCase = true) }
                ?: return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("format must be CSV or EXCEL"))
            respondDbReport(call, service, id, service.dbExport(id, format))
        }

        post("/results/{resultId}/database/experiments/{exp}/report") {
            val id = call.parameters["resultId"]!!
            val exp = call.parameters["exp"]!!
            val req = runCatching { call.receive<SummaryReportRequest>() }.getOrElse { SummaryReportRequest() }
            respondDbReport(
                call, service, id,
                service.dbSummaryReport(id, exp, req.level ?: 0.95, req.showPlots ?: true, parseReportFormats(req.formats)),
            )
        }

        // ----- document-centric submission (the full-fidelity path) -----
        post("/run-configs") {
            val submission = try {
                service.submitRunDocument(call.receiveText())
            } catch (e: JobAtCapacityException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, StatusResponse("at capacity (${e.limit})"))
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(submission.httpStatus(), submission.toAccepted())
        }

        post("/optimization-configs") {
            val submission = try {
                service.submitOptimizationDocument(call.receiveText())
            } catch (e: JobAtCapacityException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, StatusResponse("at capacity (${e.limit})"))
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(submission.httpStatus(), submission.toAccepted())
        }

        // ----- optimization (job-shaped; shares the /runs/{id} endpoints) -----
        post("/optimizations") {
            val request = call.receive<OptimizationRequest>()
            if (!service.modelExists(request.bundleId, request.modelId)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    StatusResponse("no model '${request.modelId}' in bundle '${request.bundleId}'"),
                )
            }
            if (request.inputs.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("'inputs' must not be empty"))
            }
            val submission = try {
                service.submitOptimization(
                    request.modelId, request.objectiveResponse, request.inputs,
                    request.maxIterations, request.replicationsPerEvaluation, request.maximize,
                )
            } catch (e: JobAtCapacityException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, StatusResponse("at capacity (${e.limit})"))
            }
            call.respond(submission.httpStatus(), submission.toAccepted())
        }

        // ----- designed experiments (job-shaped; shares the /runs/{id} endpoints) -----
        post("/experiments") {
            val request = call.receive<ExperimentRequest>()
            if (!service.modelExists(request.bundleId, request.modelId)) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    StatusResponse("no model '${request.modelId}' in bundle '${request.bundleId}'"),
                )
            }
            if (request.factors.size < 2) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    StatusResponse("a factorial experiment needs at least two factors"),
                )
            }
            val submission = try {
                service.submitExperiment(request.modelId, request.factors, request.numRepsPerDesignPoint)
            } catch (e: JobAtCapacityException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, StatusResponse("at capacity (${e.limit})"))
            }
            call.respond(submission.httpStatus(), submission.toAccepted())
        }

        // ----- experiment authoring + document submission (Tier B) -----
        get("/bundles/{bundleId}/models/{modelId}/experiment-template") {
            val document = try {
                service.experimentTemplateDocument(call.parameters["bundleId"]!!, call.parameters["modelId"]!!)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "cannot scaffold"))
            } ?: return@get call.respond(HttpStatusCode.NotFound, StatusResponse("unknown model"))
            call.respondText(document, ContentType.Application.Json)
        }

        post("/validate/experiment") {
            val report = try {
                service.validateExperimentDocument(call.receiveText())
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(report)
        }

        post("/experiment-configs") {
            val submission = try {
                service.submitExperimentDocument(call.receiveText())
            } catch (e: JobAtCapacityException) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable, StatusResponse("at capacity (${e.limit})"))
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(submission.httpStatus(), submission.toAccepted())
        }

        // ----- fit authoring + document submission (Tier B) -----
        get("/fit-template") {
            val kind = when (call.request.queryParameters["kind"]?.uppercase()) {
                null, "CONTINUOUS" -> DistributionKind.CONTINUOUS
                "DISCRETE" -> DistributionKind.DISCRETE
                else -> return@get call.respond(HttpStatusCode.BadRequest, StatusResponse("kind must be CONTINUOUS or DISCRETE"))
            }
            call.respondText(service.fitTemplateDocument(kind), ContentType.Application.Json)
        }

        post("/validate/fit") {
            val report = try {
                service.validateFitDocument(call.receiveText())
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            }
            call.respond(report)
        }

        post("/fit-configs") {
            val card = try {
                service.submitFitDocument(call.receiveText())
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse(e.message ?: "invalid document"))
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("fit failed: ${e.message}"))
            }
            call.respond(if (card.cached) HttpStatusCode.OK else HttpStatusCode.Created, card)
        }

        // ----- fits (awaited) -----
        post("/fits") {
            val request = call.receive<FitRequest>()
            if (request.data.size < 2) {
                return@post call.respond(HttpStatusCode.BadRequest, StatusResponse("'data' needs at least two values"))
            }
            val kind = when (request.kind.uppercase()) {
                "CONTINUOUS" -> DistributionKind.CONTINUOUS
                "DISCRETE" -> DistributionKind.DISCRETE
                else -> return@post call.respond(
                    HttpStatusCode.BadRequest,
                    StatusResponse("kind must be CONTINUOUS or DISCRETE"),
                )
            }
            try {
                call.respond(service.fit(request.data.toDoubleArray(), request.name, kind))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, StatusResponse("fit failed: ${e.message}"))
            }
        }
    }
}
