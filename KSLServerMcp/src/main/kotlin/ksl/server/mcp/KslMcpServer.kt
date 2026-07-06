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

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ksl.app.config.RunConfiguration
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.dist.config.FitConfiguration
import ksl.service.capability.run.schema.ConfigSchemaGenerator
import ksl.service.config.BuildInfo

/**
 * Builds an MCP [Server] that exposes KSL's bundled-model discovery and
 * description over the KSL service core. The server is transport-agnostic; the
 * entrypoint ([main]) attaches a stdio transport, and a future cut can attach
 * Streamable HTTP to the same server (strategic plan §4.2).
 *
 * The registered tools delegate to [KslMcpTools], which is independently
 * unit-tested against the real example bundles.
 */
object KslMcpServer {

    fun build(tools: KslMcpTools): Server {
        val server = Server(
            serverInfo = Implementation(name = "ksl-mcp", version = BuildInfo.version),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    // Guided "do-X" workflows (Phase 8.7); the catalog can change at
                    // runtime (8.6), but the prompt *set* is fixed, so listChanged = false.
                    prompts = ServerCapabilities.Prompts(listChanged = false),
                ),
            ),
        )

        server.addTool(
            name = "list_bundles",
            description = "List the KSL model bundles available to this server (structuredContent " +
                "{bundles:[...]} with id, display name, version, and the model ids each provides). " +
                "Present the full list; don't truncate.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
            outputSchema = McpResultSchemas.bundles,
        ) { _ -> tools.listBundles() }

        server.addTool(
            name = "list_models",
            description = "List the model ids provided by a bundle (structuredContent " +
                "{bundleId, models:[...]}). Present the full list; don't truncate.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("bundleId") {
                        put("type", "string")
                        put("description", "The bundle id, e.g. ksl.examples.mm1")
                    }
                },
                required = listOf("bundleId"),
            ),
            outputSchema = McpResultSchemas.models,
        ) { request -> tools.listModels(request.arguments) }

        server.addTool(
            name = "describe_model",
            description = "Describe a model: structuredContent with its task kinds (supportedApps), " +
                "responses, and JSON Schemas for run arguments and outputs (catalog-led when the model " +
                "nominates a curated set). When reporting, list the responses and the input keys the agent " +
                "may set; don't omit any.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("bundleId") { put("type", "string") }
                    putJsonObject("modelId") { put("type", "string") }
                },
                required = listOf("bundleId", "modelId"),
            ),
            outputSchema = McpResultSchemas.modelDescriptor,
        ) { request -> tools.describeModel(request.arguments) }

        server.addTool(
            name = "run_model",
            description = "Run a single simulation of a bundled model. Returns structuredContent with the " +
                "full result and a text summary. When reporting, present EVERY response in the `responses` " +
                "array — name, average, standard error, and 95% CI half-width — plus the replication count " +
                "and status; do not omit responses or replace the statistics with prose. Identical calls " +
                "reproduce by design; to get a different, independent random realization set replicationSet " +
                "(0 = the standard run; 1, 2, … give independent runs), or set antithetic for variance reduction. " +
                "For full control (scenario batches, output/tracing, stream policy), author a RunConfiguration for " +
                "run_config; run_template scaffolds one.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("bundleId") { put("type", "string") }
                    putJsonObject("modelId") { put("type", "string") }
                    putJsonObject("numberOfReplications") {
                        put("type", "integer")
                        put("description", "Optional; overrides the model's default replication count.")
                    }
                    putJsonObject("lengthOfReplication") {
                        put("type", "number")
                        put("description", "Optional; overrides the model's default run length.")
                    }
                    putJsonObject("inputs") {
                        put("type", "object")
                        put(
                            "description",
                            "Optional model inputs as {inputKey: value}, using keys from " +
                                "describe_model's inputSchema (numeric controls and RV parameters).",
                        )
                    }
                    putJsonObject("replicationSet") {
                        put("type", "integer")
                        put(
                            "description",
                            "Optional (default 0). Selects an independent, reproducible random realization: 0 is " +
                                "the standard run, and each different value reuses a non-overlapping block of random " +
                                "substreams. Identical calls reproduce by design, so increment this (1, 2, …) to get " +
                                "a genuinely different run.",
                        )
                    }
                    putJsonObject("antithetic") {
                        put("type", "boolean")
                        put("description", "Optional. Run with antithetic variates (a variance-reduction technique).")
                    }
                    putJsonObject("enableKSLDatabase") {
                        put("type", "boolean")
                        put(
                            "description",
                            "Optional (default false). Capture a KSL SQLite database for this run so the db_* tools " +
                                "(db_status, db_summary, db_export, …) can analyze it. Writes a database file under the " +
                                "result's output directory; leave off unless you intend to run database analysis.",
                        )
                    }
                },
                required = listOf("bundleId", "modelId"),
            ),
            outputSchema = McpResultSchemas.run,
        ) { request -> tools.runModel(request.arguments) }

        val runArgsSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("bundleId") { put("type", "string") }
                putJsonObject("modelId") { put("type", "string") }
                putJsonObject("numberOfReplications") {
                    put("type", "integer")
                    put("description", "Optional; overrides the model's default replication count.")
                }
                putJsonObject("lengthOfReplication") {
                    put("type", "number")
                    put("description", "Optional; overrides the model's default run length.")
                }
                putJsonObject("inputs") {
                    put("type", "object")
                    put(
                        "description",
                        "Optional model inputs as {inputKey: value}, using keys from " +
                            "describe_model's inputSchema (numeric controls and RV parameters).",
                    )
                }
                putJsonObject("replicationSet") {
                    put("type", "integer")
                    put(
                        "description",
                        "Optional (default 0). Selects an independent, reproducible random realization: 0 is the " +
                            "standard run, and each different value reuses a non-overlapping block of random " +
                            "substreams. Increment this (1, 2, …) to get a genuinely different run.",
                    )
                }
                putJsonObject("antithetic") {
                    put("type", "boolean")
                    put("description", "Optional. Run with antithetic variates (a variance-reduction technique).")
                }
            },
            required = listOf("bundleId", "modelId"),
        )

        server.addTool(
            name = "submit_run",
            description = "Start a run without waiting; returns structuredContent with the jobId, status, " +
                "resultId, and cache disposition. When reporting, give the user the status and jobId, then " +
                "poll get_run_events for progress and get_run_result for the final result. Supports the same " +
                "replicationSet / antithetic random-stream controls as run_model.",
            inputSchema = runArgsSchema,
            outputSchema = McpResultSchemas.job,
        ) { request -> tools.submitRun(request.arguments) }

        server.addTool(
            name = "get_run_events",
            description = "Snapshot a run's progress events from fromOffset (the journal retains " +
                "every event, so any offset replays). Returns structuredContent with the events, the next " +
                "offset, and status; report the status and use nextOffset for the following poll.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("jobId") { put("type", "string") }
                    putJsonObject("fromOffset") {
                        put("type", "integer")
                        put("description", "First event index to return (default 0).")
                    }
                },
                required = listOf("jobId"),
            ),
            outputSchema = McpResultSchemas.events,
        ) { request -> tools.getRunEvents(request.arguments) }

        server.addTool(
            name = "get_run_result",
            description = "The result of a run once it has finished (structuredContent with the full result, " +
                "reported exactly like run_model — every response with average, standard error, and 95% CI " +
                "half-width), or a RUNNING marker while still in flight.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { putJsonObject("jobId") { put("type", "string") } },
                required = listOf("jobId"),
            ),
            outputSchema = McpResultSchemas.projection,
        ) { request -> tools.getRunResult(request.arguments) }

        server.addTool(
            name = "cancel_run",
            description = "Request cancellation of a still-running job started by submit_run. Returns " +
                "structuredContent {jobId, cancelled, message}: cancelled=true when a cancel was issued to a " +
                "running job, false when it was already finished, unknown, or evicted — so it is safe to call " +
                "idempotently.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("jobId") { put("type", "string") }
                    putJsonObject("reason") { put("type", "string"); put("description", "Optional human-readable cancellation reason.") }
                },
                required = listOf("jobId"),
            ),
            outputSchema = McpResultSchemas.cancel,
        ) { request -> tools.cancelRun(request.arguments) }

        server.addTool(
            name = "run_optimization",
            description = "Run a simulation-optimization (stochastic hill climbing) over a bundled " +
                "model: minimize/maximize a response over numeric decision variables. Returns structuredContent " +
                "with the best solution and iteration trace. When reporting, give the best decision-variable " +
                "values and the objective achieved, the number of iterations evaluated, and the direction. " +
                "For full control (the solver family, cooling schedules, penalties, stopping rules), author an " +
                "OptimizationRunConfiguration for run_optimization_config; optimization_template scaffolds one.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("bundleId") { put("type", "string") }
                    putJsonObject("modelId") { put("type", "string") }
                    putJsonObject("objectiveResponse") {
                        put("type", "string")
                        put("description", "The model response to optimize.")
                    }
                    putJsonObject("inputs") {
                        put("type", "array")
                        put("description", "Decision variables: {name, lowerBound, upperBound, granularity}.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string") }
                                putJsonObject("lowerBound") { put("type", "number") }
                                putJsonObject("upperBound") { put("type", "number") }
                                putJsonObject("granularity") { put("type", "number") }
                            }
                        }
                    }
                    putJsonObject("maxIterations") { put("type", "integer") }
                    putJsonObject("replicationsPerEvaluation") { put("type", "integer") }
                    putJsonObject("maximize") { put("type", "boolean") }
                },
                required = listOf("bundleId", "modelId", "objectiveResponse", "inputs"),
            ),
            outputSchema = McpResultSchemas.run,
        ) { request -> tools.runOptimization(request.arguments) }

        server.addTool(
            name = "run_experiment",
            description = "Run a two-level factorial designed experiment over a bundled model. " +
                "Each factor binds a model control key to low/high levels; needs at least two factors. " +
                "Returns structuredContent with per-design-point results. When reporting, present EVERY " +
                "design point — its factor settings and the resulting response means — and state the design. " +
                "For full control (fractional or central-composite designs, replication policies), author an " +
                "ExperimentConfiguration for experiment_config; experiment_template scaffolds one.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("bundleId") { put("type", "string") }
                    putJsonObject("modelId") { put("type", "string") }
                    putJsonObject("factors") {
                        put("type", "array")
                        put("description", "Factors: {name, controlKey, low, high} (>= 2 required).")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "string") }
                                putJsonObject("controlKey") { put("type", "string") }
                                putJsonObject("low") { put("type", "number") }
                                putJsonObject("high") { put("type", "number") }
                            }
                        }
                    }
                    putJsonObject("numRepsPerDesignPoint") { put("type", "integer") }
                },
                required = listOf("bundleId", "modelId", "factors"),
            ),
            outputSchema = McpResultSchemas.run,
        ) { request -> tools.runExperiment(request.arguments) }

        // The real per-field schema for each document family, generated from its @Serializable
        // descriptor (B1): sealed choices — design kinds, solver families, cooling schedules —
        // surface as a `oneOf` keyed by a `type` discriminator, so an agent can discover the
        // document's shape instead of guessing against an opaque blob. Kept alongside
        // `type: [object, string]` so a JSON/TOML string is still accepted.
        val configDocSchema = { documentType: String ->
            val descriptor = when (documentType) {
                "RunConfiguration" -> RunConfiguration.serializer().descriptor
                "OptimizationRunConfiguration" -> OptimizationRunConfiguration.serializer().descriptor
                "ExperimentConfiguration" -> ExperimentConfiguration.serializer().descriptor
                "FitConfiguration" -> FitConfiguration.serializer().descriptor
                else -> null
            }
            val generated = descriptor?.let { ConfigSchemaGenerator.schemaFor(it) }
            ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("config") {
                        putJsonArray("type") { add("object"); add("string") }
                        put(
                            "description",
                            "A complete $documentType document — an object with the fields below, or the same " +
                                "document as a JSON or TOML string (e.g. paste the contents of a .toml file saved " +
                                "by a KSL desktop app; the server accepts TOML directly). Sealed choices appear as " +
                                "a oneOf keyed by a type discriminator; shape guidance only — validate_* remains the gate.",
                        )
                        generated?.get("properties")?.let { put("properties", it) }
                        generated?.get("required")?.let { put("required", it) }
                    }
                },
                required = listOf("config"),
            )
        }

        server.addTool(
            name = "run_config",
            description = "Run a complete RunConfiguration document (single run or scenario batch) as " +
                "authored. The full-fidelity path; the document is validated before running. Returns " +
                "structuredContent with the full result; report every response (or design point) with its statistics.",
            inputSchema = configDocSchema("RunConfiguration"),
            outputSchema = McpResultSchemas.run,
        ) { request -> tools.runConfig(request.arguments) }

        server.addTool(
            name = "run_optimization_config",
            description = "Run a complete OptimizationRunConfiguration document as authored, validated first. " +
                "Returns structuredContent with the best solution and iteration trace; report the best inputs, " +
                "the objective achieved, and the iteration count.",
            inputSchema = configDocSchema("OptimizationRunConfiguration"),
            outputSchema = McpResultSchemas.run,
        ) { request -> tools.runOptimizationConfig(request.arguments) }

        val modelArgsSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("bundleId") { put("type", "string") }
                putJsonObject("modelId") { put("type", "string") }
            },
            required = listOf("bundleId", "modelId"),
        )

        server.addTool(
            name = "experiment_template",
            description = "Get a ready-to-edit ExperimentConfiguration document scaffold for a model " +
                "(a two-level factorial over its first two numeric controls). The text is the document to " +
                "edit; structuredContent.document is the same parsed. Edit it and submit to experiment_config.",
            inputSchema = modelArgsSchema,
            outputSchema = McpResultSchemas.document,
        ) { request -> tools.experimentTemplate(request.arguments) }

        server.addTool(
            name = "experiment_config",
            description = "Run a complete ExperimentConfiguration document (factors + design) as authored, " +
                "validated first. Returns structuredContent with per-design-point results; when reporting, " +
                "present every design point with its response means (deeper detail via get_design_point).",
            inputSchema = configDocSchema("ExperimentConfiguration"),
            outputSchema = McpResultSchemas.run,
        ) { request -> tools.experimentConfig(request.arguments) }

        server.addTool(
            name = "validate_experiment_config",
            description = "Validate an ExperimentConfiguration document without running it. Returns " +
                "structuredContent {valid, errors[], warnings[]}. Report the verdict: state VALID, or list " +
                "EVERY error with its field path; never silently pass.",
            inputSchema = configDocSchema("ExperimentConfiguration"),
            outputSchema = McpResultSchemas.validation,
        ) { request -> tools.validateExperiment(request.arguments) }

        server.addTool(
            name = "run_template",
            description = "Get a ready-to-edit RunConfiguration document scaffold for a model " +
                "(controls and run parameters pre-filled at defaults). The text is the document to edit; " +
                "structuredContent.document is the same parsed. Edit it and submit to run_config.",
            inputSchema = modelArgsSchema,
            outputSchema = McpResultSchemas.document,
        ) { request -> tools.runTemplate(request.arguments) }

        server.addTool(
            name = "optimization_template",
            description = "Get a ready-to-edit OptimizationRunConfiguration document scaffold for a model " +
                "(a placeholder single decision variable over its first numeric control, minimizing its " +
                "first response with stochastic hill climbing). The bounds are a finite placeholder — edit " +
                "them, the objective, the solver, and the budget, then submit to run_optimization_config. " +
                "The text is the document; structuredContent.document is the same parsed.",
            inputSchema = modelArgsSchema,
            outputSchema = McpResultSchemas.document,
        ) { request -> tools.optimizationTemplate(request.arguments) }

        server.addTool(
            name = "validate_run_config",
            description = "Validate a RunConfiguration document without running it. Returns structuredContent " +
                "{valid, errors[], warnings[]}. Report the verdict: state VALID, or list EVERY error with its " +
                "field path; never silently pass.",
            inputSchema = configDocSchema("RunConfiguration"),
            outputSchema = McpResultSchemas.validation,
        ) { request -> tools.validateRun(request.arguments) }

        server.addTool(
            name = "validate_optimization_config",
            description = "Validate an OptimizationRunConfiguration document without running it. Returns " +
                "structuredContent {valid, errors[], warnings[]}. Report the verdict: state VALID, or list " +
                "EVERY error with its field path; never silently pass.",
            inputSchema = configDocSchema("OptimizationRunConfiguration"),
            outputSchema = McpResultSchemas.validation,
        ) { request -> tools.validateOptimization(request.arguments) }

        server.addTool(
            name = "preview_run_config",
            description = "Preview a RunConfiguration without running it: structuredContent with the canonical " +
                "(normalized) document and its workload — scenario count and replication budget. Report the " +
                "cost before running.",
            inputSchema = configDocSchema("RunConfiguration"),
            outputSchema = McpResultSchemas.preview,
        ) { request -> tools.previewRun(request.arguments) }

        server.addTool(
            name = "preview_optimization_config",
            description = "Preview an OptimizationRunConfiguration: structuredContent with the canonical " +
                "document and its cost — solver, max iterations, replications per evaluation, decision " +
                "variables, and a lower-bound replication estimate. Report the cost before running.",
            inputSchema = configDocSchema("OptimizationRunConfiguration"),
            outputSchema = McpResultSchemas.preview,
        ) { request -> tools.previewOptimization(request.arguments) }

        server.addTool(
            name = "preview_experiment_config",
            description = "Preview an ExperimentConfiguration: structuredContent with the canonical document " +
                "and its cost — factor count, design type, design-point count (the 2^k blow-up), and total " +
                "replications. Report the cost before running.",
            inputSchema = configDocSchema("ExperimentConfiguration"),
            outputSchema = McpResultSchemas.preview,
        ) { request -> tools.previewExperiment(request.arguments) }

        server.addTool(
            name = "preview_fit_config",
            description = "Preview a FitConfiguration: structuredContent with the canonical document and its " +
                "cost — distribution kind, data source, dataset and estimator counts, and whether bootstrap " +
                "is enabled. Report the cost before running.",
            inputSchema = configDocSchema("FitConfiguration"),
            outputSchema = McpResultSchemas.preview,
        ) { request -> tools.previewFit(request.arguments) }

        val resultIdOnly = ToolSchema(
            properties = buildJsonObject { putJsonObject("resultId") { put("type", "string") } },
            required = listOf("resultId"),
        )

        server.addTool(
            name = "get_result",
            description = "Fetch a retained result by resultId (no re-run), projected back through the SAME " +
                "envelope its run/fit tool used: structuredContent with the full result plus a complete " +
                "summary. Report it exactly as you would the original run or fit.",
            inputSchema = resultIdOnly,
            outputSchema = McpResultSchemas.projection,
        ) { request -> tools.getResult(request.arguments) }

        server.addTool(
            name = "list_responses",
            description = "List the response names available in a retained result (structuredContent " +
                "{responses:[...]}); present the full list, don't truncate.",
            inputSchema = resultIdOnly,
            outputSchema = McpResultSchemas.responseNames,
        ) { request -> tools.listResponses(request.arguments) }

        server.addTool(
            name = "get_response",
            description = "Get one response's statistics from a retained result, by name. Returns " +
                "structuredContent with the response object; report its average, standard error, and 95% CI " +
                "half-width.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                },
                required = listOf("resultId", "name"),
            ),
            outputSchema = McpResultSchemas.response,
        ) { request -> tools.getResponse(request.arguments) }

        server.addTool(
            name = "get_artifacts",
            description = "List the rendered artifacts (reports, plot images, exports) retained for a result " +
                "(structuredContent {artifacts:[{name, mediaType, path}]}). Present the full list; fetch one " +
                "with get_artifact.",
            inputSchema = resultIdOnly,
            outputSchema = McpResultSchemas.artifacts,
        ) { request -> tools.getArtifacts(request.arguments) }

        server.addTool(
            name = "get_artifact",
            description = "Fetch one artifact by name. Text artifacts (HTML/Markdown/text/CSV/JSON/SVG) come " +
                "back inline as the text content; structuredContent carries {name, mediaType, path, content?} " +
                "and the on-disk path for any type.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                },
                required = listOf("resultId", "name"),
            ),
            outputSchema = McpResultSchemas.artifact,
        ) { request -> tools.getArtifact(request.arguments) }

        server.addTool(
            name = "db_open_external",
            description = "Open a pre-existing KSL database the server did not produce — a SQLite .db file or an " +
                "embedded Derby directory — so the db_* tools can analyze it. Opening validates the KSL schema (a " +
                "non-KSL file is refused). Returns structuredContent {resultId, path, experiments}; pass the " +
                "resultId to db_status / db_experiments / db_summary / db_compare / db_view like any other result.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Filesystem path to the KSL database (a .db file, or an embedded Derby directory).")
                    }
                },
                required = listOf("path"),
            ),
            outputSchema = McpResultSchemas.externalDb,
        ) { request -> tools.dbOpenExternal(request.arguments) }

        server.addTool(
            name = "db_status",
            description = "Report whether a result has an analyzable KSL database (structuredContent " +
                "{present, experimentCount, message}). A run produces one only when it enabled the database " +
                "option; when absent, the message tells the user how to re-run. Always succeeds.",
            inputSchema = resultIdOnly,
            outputSchema = McpResultSchemas.dbStatus,
        ) { request -> tools.dbStatus(request.arguments) }

        server.addTool(
            name = "db_experiments",
            description = "List the experiments recorded in a result's database (structuredContent " +
                "{experiments:[{name, modelIdentifier, numReplications, responses}]}). Returns guidance when " +
                "the result has no database.",
            inputSchema = resultIdOnly,
            outputSchema = McpResultSchemas.dbExperiments,
        ) { request -> tools.dbExperiments(request.arguments) }

        server.addTool(
            name = "db_summary",
            description = "Across-replication summary statistics for one experiment in a result's database, " +
                "as JSON in structuredContent.summary (average, std error, CI half-width, count, min/max per " +
                "response). Guidance when there is no database.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("experimentName") { put("type", "string") }
                },
                required = listOf("resultId", "experimentName"),
            ),
            outputSchema = McpResultSchemas.dbJson,
        ) { request -> tools.dbSummary(request.arguments) }

        server.addTool(
            name = "db_compare",
            description = "Multiple-comparison (MCB) analysis of a response across the database's experiments, " +
                "as JSON in structuredContent.comparison {response, delta, level, results, intervals, screening}. " +
                "Needs >=2 experiments recording the response with equal replication counts; otherwise returns a " +
                "clear precondition message. Guidance when there is no database.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("responseName") { put("type", "string") }
                    putJsonObject("experiments") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                    putJsonObject("delta") { put("type", "number") }
                    putJsonObject("level") { put("type", "number") }
                },
                required = listOf("resultId", "responseName"),
            ),
            outputSchema = McpResultSchemas.dbJson,
        ) { request -> tools.dbCompare(request.arguments) }

        server.addTool(
            name = "db_views",
            description = "List the statistical DataFrame views available for a result's database " +
                "(structuredContent {views:[...]}): across-replication, within-replication, time-series " +
                "(across-rep per-period summary), histograms, frequencies, batch-statistics, and more. " +
                "Fetch one with db_view.",
            inputSchema = resultIdOnly,
            outputSchema = McpResultSchemas.dbViews,
        ) { request -> tools.dbViews(request.arguments) }

        server.addTool(
            name = "db_view",
            description = "Fetch one named statistical view as JSON in structuredContent.view, a row-capped " +
                "envelope {view, total, returned, truncated, rows}. Optional 'experiment' filter and 'limit'. " +
                "For whole-table bulk use db_export instead.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("view") { put("type", "string") }
                    putJsonObject("experiment") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                },
                required = listOf("resultId", "view"),
            ),
            outputSchema = McpResultSchemas.dbJson,
        ) { request -> tools.dbView(request.arguments) }

        server.addTool(
            name = "db_compare_report",
            description = "Render a multiple-comparison (MCB) report — intervals plus confidence-interval and " +
                "box plots — as a downloadable artifact (structuredContent {artifacts:[...]}; fetch with " +
                "get_artifact). Same preconditions as db_compare. Optional 'formats' (HTML default).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("responseName") { put("type", "string") }
                    putJsonObject("experiments") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                    putJsonObject("delta") { put("type", "number") }
                    putJsonObject("level") { put("type", "number") }
                    putJsonObject("formats") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                },
                required = listOf("resultId", "responseName"),
            ),
            outputSchema = McpResultSchemas.artifacts,
        ) { request -> tools.dbCompareReport(request.arguments) }

        server.addTool(
            name = "db_export",
            description = "Export the result's database tables as downloadable artifacts: 'format' CSV (one file " +
                "per table) or EXCEL (a single workbook). Returns structuredContent {artifacts:[...]}; fetch with " +
                "get_artifact.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("format") { put("type", "string"); put("description", "CSV or EXCEL (default CSV).") }
                },
                required = listOf("resultId"),
            ),
            outputSchema = McpResultSchemas.artifacts,
        ) { request -> tools.dbExport(request.arguments) }

        server.addTool(
            name = "db_summary_report",
            description = "Render a single-experiment summary report — across-replication statistics plus " +
                "embedded histograms and frequency distributions — as a downloadable artifact " +
                "(structuredContent {artifacts:[...]}; fetch with get_artifact). Optional 'level', 'showPlots', " +
                "'formats' (HTML default).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("experimentName") { put("type", "string") }
                    putJsonObject("level") { put("type", "number") }
                    putJsonObject("showPlots") { put("type", "boolean") }
                    putJsonObject("formats") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                },
                required = listOf("resultId", "experimentName"),
            ),
            outputSchema = McpResultSchemas.artifacts,
        ) { request -> tools.dbSummaryReport(request.arguments) }

        server.addTool(
            name = "get_design_point",
            description = "Get one scenario/design-point result from a retained batch result, by index. " +
                "Returns structuredContent with the design point (its factor settings and per-response " +
                "statistics); report every response, not just the objective.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string") }
                    putJsonObject("index") { put("type", "integer") }
                },
                required = listOf("resultId", "index"),
            ),
            outputSchema = McpResultSchemas.projection,
        ) { request -> tools.getDesignPoint(request.arguments) }

        server.addTool(
            name = "fit_dataset",
            description = "Fit candidate probability distributions to a numeric dataset. Candidates are ranked by " +
                "MODA (multi-objective) over weighted metrics. Returns structuredContent with the recommended " +
                "family and the full ranked candidates. When reporting, lead with the recommended fit, then " +
                "present the FULL ranking including each candidate's scaled MODA score (weightedValue) and " +
                "average ranking — that is the recommendation basis — plus parameters and goodness-of-fit; " +
                "preserve the numeric values, do not show only the top fit. For the full scaled per-metric MODA " +
                "matrix, call get_fit_scoring; for the full HTML report (with diagnostic plots when a display is " +
                "available), call get_fit_report — both with the resultId.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("data") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "number") }
                        put("description", "The observations to fit.")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Optional dataset name (default 'dataset').")
                    }
                    putJsonObject("kind") {
                        put("type", "string")
                        put("description", "CONTINUOUS (default) or DISCRETE.")
                    }
                },
                required = listOf("data"),
            ),
            outputSchema = McpResultSchemas.fit,
        ) { request -> tools.fitDataset(request.arguments) }

        val fitKindSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("kind") {
                    put("type", "string")
                    put("description", "CONTINUOUS (default) or DISCRETE.")
                }
            },
            required = emptyList(),
        )

        server.addTool(
            name = "fit_template",
            description = "Get a ready-to-edit FitConfiguration document scaffold for a kind " +
                "(CONTINUOUS/DISCRETE): an inline data source to fill plus catalog-default estimators. " +
                "The text is the document to edit; structuredContent.document is the same parsed. " +
                "Edit it and submit to fit_config.",
            inputSchema = fitKindSchema,
            outputSchema = McpResultSchemas.document,
        ) { request -> tools.fitTemplate(request.arguments) }

        server.addTool(
            name = "fit_config",
            description = "Fit candidate distributions from a complete FitConfiguration document " +
                "(any data source: inline, delimited file, generated RV, or database), validated first. " +
                "Candidates are ranked by MODA over weighted metrics. Returns structuredContent with the " +
                "recommended family and the full ranked candidates; report the recommendation and the complete " +
                "ranking including each candidate's scaled MODA score (weightedValue) and average ranking, plus " +
                "parameters and goodness-of-fit. Full scaled per-metric MODA matrix via get_fit_scoring.",
            inputSchema = configDocSchema("FitConfiguration"),
            outputSchema = McpResultSchemas.fit,
        ) { request -> tools.fitConfig(request.arguments) }

        server.addTool(
            name = "get_fit_scoring",
            description = "Get the full MODA scoring for a retained continuous fit result by resultId: the " +
                "metrics (with weights and direction), each candidate distribution's scaled score per metric, " +
                "and the per-metric ranks — the scaled-scores matrix behind the fit ranking. Continuous fits only.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string"); put("description", "A retained fit result id.") }
                },
                required = listOf("resultId"),
            ),
            outputSchema = McpResultSchemas.fitScoring,
        ) { request -> tools.getFitScoring(request.arguments) }

        server.addTool(
            name = "get_fit_report",
            description = "Render the full HTML report for a retained distribution fit — data summary, shift " +
                "analysis, ranked fits, MODA scoring, goodness-of-fit, and the diagnostic plots (density, ECDF, " +
                "Q-Q, P-P) WHEN a graphical display is available — to a file, returning its path to open in a " +
                "browser. In a headless environment it degrades to the full tables/statistics report without " +
                "plots. Pass the original `data` if the server was restarted since the fit.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string"); put("description", "A retained fit result id.") }
                    putJsonObject("data") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "number") }
                        put("description", "Optional: the original observations, needed for plots if not still cached.")
                    }
                },
                required = listOf("resultId"),
            ),
            outputSchema = McpResultSchemas.fitReport,
        ) { request -> tools.getFitReport(request.arguments) }

        server.addTool(
            name = "validate_fit_config",
            description = "Validate a FitConfiguration document without running it. Returns structuredContent " +
                "{valid, errors[], warnings[]}. Report the verdict: state VALID, or list EVERY error with its " +
                "field path; never silently pass.",
            inputSchema = configDocSchema("FitConfiguration"),
            outputSchema = McpResultSchemas.validation,
        ) { request -> tools.validateFit(request.arguments) }

        server.addTool(
            name = "summarize_data",
            description = "Compute the engine's full statistical summary and an equal-bin histogram over a " +
                "numeric array — without running a distribution fit. Returns structuredContent {datasetName, " +
                "dataSummary:{statistics{count, average, standardDeviation, min, max, variance, confidence " +
                "interval, skewness, kurtosis, …}, zeroCount, negativeCount, positiveCount}, histogram?}. The " +
                "histogram (bins with ranges, counts, and proportions) is included by default — pass " +
                "histogram=false to skip it, or confidenceLevel to change the CI level (default 0.95). When " +
                "reporting, present the key statistics (count, mean, std dev, min/max, the CI) and, when a " +
                "histogram is returned, its bins; preserve the numeric values rather than paraphrasing them.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("data") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "number") }
                        put("description", "The numeric observations to summarize.")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Optional name for the data series (default 'data').")
                    }
                    putJsonObject("confidenceLevel") {
                        put("type", "number")
                        put("description", "Optional CI level in (0,1); default 0.95.")
                    }
                    putJsonObject("histogram") {
                        put("type", "boolean")
                        put("description", "Include the histogram (default true); set false for statistics only.")
                    }
                },
                required = listOf("data"),
            ),
            outputSchema = McpResultSchemas.dataSummary,
        ) { request -> tools.summarizeData(request.arguments) }

        server.addTool(
            name = "acf_analysis",
            description = "Sample autocorrelation function of a numeric series: the correlation at each lag " +
                "1..maxLag, a white-noise significance band (±1.96/√n), and a lag-1 independence verdict — a check " +
                "of whether observations are serially dependent. Returns structuredContent {n, maxLag, " +
                "whiteNoiseBand, lag1, independentAtLag1, acf[{lag, value, significant}]}.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("data") {
                        put("type", "array"); putJsonObject("items") { put("type", "number") }
                        put("description", "The numeric observations, in order.")
                    }
                    putJsonObject("maxLag") { put("type", "integer"); put("description", "Optional highest lag; default min(20, n/4).") }
                },
                required = listOf("data"),
            ),
            outputSchema = McpResultSchemas.acf,
        ) { request -> tools.acfAnalysis(request.arguments) }

        server.addTool(
            name = "shift_analysis",
            description = "The left-shift a distribution fit would apply to a numeric series, computed standalone " +
                "(otherwise only visible inside a full fit). A positive shift means the data is offset from a lower " +
                "bound; subtract it before fitting a lower-bounded distribution. Returns structuredContent {n, " +
                "leftShift, dataMin, shiftRecommended}.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("data") {
                        put("type", "array"); putJsonObject("items") { put("type", "number") }
                        put("description", "The numeric observations.")
                    }
                },
                required = listOf("data"),
            ),
            outputSchema = McpResultSchemas.shift,
        ) { request -> tools.shiftAnalysis(request.arguments) }

        server.addTool(
            name = "family_frequency_bootstrap",
            description = "Resample a numeric series numSamples times, re-run the full distribution fit on each " +
                "resample, and tally how often each family is the recommended fit — a robustness check on a fit " +
                "recommendation. Heavier than the other analyses (it re-fits per resample); keep numSamples modest. " +
                "Returns structuredContent {datasetName, numSamples, frequency:{cells[{cellLabel, count, proportion}]}}.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("data") {
                        put("type", "array"); putJsonObject("items") { put("type", "number") }
                        put("description", "The numeric observations to fit and resample.")
                    }
                    putJsonObject("name") { put("type", "string"); put("description", "Optional data-series name (default 'data').") }
                    putJsonObject("numSamples") { put("type", "integer"); put("description", "Bootstrap resamples (default 100); higher = slower.") }
                    putJsonObject("streamNumber") { put("type", "integer"); put("description", "Optional RNG stream for reproducibility (default 0).") }
                },
                required = listOf("data"),
            ),
            outputSchema = McpResultSchemas.familyBootstrap,
        ) { request -> tools.familyFrequencyBootstrap(request.arguments) }

        server.addTool(
            name = "get_fit_data_summary",
            description = "Project the data summary (and, for a continuous fit, the histogram) that a retained " +
                "distribution fit already computed — no re-run. Returns the same structuredContent {datasetName, " +
                "dataSummary, histogram?} shape as summarize_data; the histogram is absent for a discrete fit. " +
                "When reporting, present the key statistics and, when present, the histogram bins; preserve the " +
                "numeric values.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("resultId") { put("type", "string"); put("description", "A retained fit result id.") }
                },
                required = listOf("resultId"),
            ),
            outputSchema = McpResultSchemas.dataSummary,
        ) { request -> tools.getFitDataSummary(request.arguments) }

        server.addTool(
            name = "list_distributions",
            description = "List every scalar-parameter probability distribution family the server can generate " +
                "random variates from (structuredContent {distributions:[{familyId, displayName, kind, " +
                "parameters}]}). This is the discovery step for generate_variates: each entry gives the stable " +
                "familyId to pass to it, the human-readable displayName, the kind (CONTINUOUS or DISCRETE), and " +
                "every scalar parameter with its type and catalog default value. Array-parameter families " +
                "(empirical, piecewise-constant, etc.) are excluded — they need a dataset rather than scalar " +
                "values. When reporting, present the full list grouped by kind with each family's parameters; " +
                "don't truncate.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
            outputSchema = McpResultSchemas.distributions,
        ) { _ -> tools.listDistributions() }

        server.addTool(
            name = "generate_variates",
            description = "Generate a random sample of n values from a named scalar-parameter distribution " +
                "family (discover the families and their parameters with list_distributions first). Returns " +
                "structuredContent {familyId, n, truncated, values:[...], filePath?}. Parameters default to the " +
                "catalog defaults when not supplied; pass only the {paramName: value} overrides you want to " +
                "change. n must be between 1 and 10000. The full sample is written to a CSV under the workspace " +
                "data directory whenever you pass output=true, or automatically when n exceeds 1000; in that " +
                "case `values` carries a leading preview (truncated=true) and `filePath` gives the CSV of the " +
                "complete sample. When reporting, state the family and the exact parameter values used, then " +
                "characterise the sample — its size and a few representative values or its range/mean — and give " +
                "the user the filePath when one was written. Do NOT paste the entire array into prose; the data " +
                "is in structuredContent.values / the CSV for any downstream use (e.g. fit_dataset).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("familyId") {
                        put("type", "string")
                        put("description", "The distribution family id from list_distributions (e.g. 'exponential', 'normal').")
                    }
                    putJsonObject("n") {
                        put("type", "integer")
                        put("description", "Number of variates to generate (1–10000).")
                    }
                    putJsonObject("parameters") {
                        put("type", "object")
                        put("description", "Optional scalar parameter overrides as {paramName: value}; defaults are used for any parameter not supplied.")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Optional name for the sample; used (sanitized) as the CSV filename when one is written. Defaults to the familyId.")
                    }
                    putJsonObject("output") {
                        put("type", "boolean")
                        put("description", "Opt in to always write the full sample to a CSV under <workspace>/KSL_MCP_APPS/data/. Samples with n > 1000 are written automatically regardless.")
                    }
                },
                required = listOf("familyId", "n"),
            ),
            outputSchema = McpResultSchemas.variates,
        ) { request -> tools.generateVariates(request.arguments) }

        server.addTool(
            name = "get_workspace",
            description = "Report the active KSL workspace and the directory where this server writes its " +
                "reports and generated data. The workspace is shared with the other KSL apps (via " +
                "~/.ksl/settings.toml); the server's artifacts go under <workspace>/KSL_MCP_APPS/. Returns " +
                "structuredContent {workspace, appDir, isDefault}.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
            outputSchema = McpResultSchemas.workspace,
        ) { _ -> tools.getWorkspace() }

        server.addTool(
            name = "set_workspace",
            description = "Set the active KSL working directory. Persists to ~/.ksl/settings.toml, the same " +
                "setting the other KSL apps use, so the change applies everywhere. The directory must already " +
                "exist (this does not create it). Returns structuredContent {workspace, appDir, previous}. " +
                "Tell the user the new location and that subsequent reports/data will be written there.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("path") {
                        put("type", "string")
                        put("description", "Absolute path to an existing directory to use as the workspace.")
                    }
                },
                required = listOf("path"),
            ),
            outputSchema = McpResultSchemas.workspace,
        ) { request -> tools.setWorkspace(request.arguments) }

        // Guided-workflow prompts (Phase 8.7) over the same live catalog.
        KslMcpPrompts.register(server, tools)

        return server
    }
}
