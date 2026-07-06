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

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `outputSchema`s for the execution-result tools (structured-output Phase 1). They
 * describe the `structuredContent` returned by [KslMcpTools.runResult] /
 * [KslMcpTools.fitResult], so an agent knows the result's exact shape and can
 * reason over its fields rather than parsing an opaque text blob. Intentionally
 * permissive (the run result is a union over `type`); the key fields are typed.
 */
internal object McpResultSchemas {

    /** Result of run_model / run_experiment / run_optimization (and their *_config twins). */
    val run = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("resultId") { put("type", "string") }
            putJsonObject("cached") { put("type", "boolean") }
            putJsonObject("reusedReplications") { put("type", "integer") }
            putJsonObject("kind") { put("type", "string") }
            putJsonObject("type") {
                put("type", "string")
                putJsonArray("enum") { add("completed"); add("batch"); add("optimization"); add("failed"); add("cancelled") }
                put("description", "Result shape discriminator.")
            }
            putJsonObject("summary") {
                put("type", "object")
                put("description", "Run identity + status (modelIdentifier, requested/completedReplications, endingStatus, times).")
            }
            putJsonObject("responses") {
                put("type", "array")
                put("description", "Across-replication statistics for every response (present when type=completed).")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") { responseStatProperties() }
                }
            }
            putJsonObject("items") {
                put("type", "array")
                put("description", "Per design-point / scenario results (present when type=batch).")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("itemName") { put("type", "string") }
                        putJsonObject("responses") {
                            put("type", "array")
                            put("description", "Across-replication statistics for the scenario / design point.")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") { responseStatProperties() }
                            }
                        }
                    }
                }
            }
            putJsonObject("best") {
                put("type", "object")
                put("description", "Best solution found (present when type=optimization).")
                putJsonObject("properties") {
                    putJsonObject("inputs") { put("type", "object"); put("description", "Decision-variable name → value.") }
                    putJsonObject("estimatedObjFncValue") { put("type", "number") }
                    putJsonObject("penalizedObjFncValue") { put("type", "number") }
                    putJsonObject("isValid") { put("type", "boolean") }
                }
            }
            putJsonObject("iterations") {
                put("type", "array")
                put("description", "Optimization iteration trace (present when type=optimization).")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("iterationNumber") { put("type", "integer") }
                        putJsonObject("numOracleCalls") { put("type", "integer") }
                        putJsonObject("estimatedObjFncValue") { put("type", "number") }
                        putJsonObject("penalizedObjFncValue") { put("type", "number") }
                    }
                }
            }
            putJsonObject("artifacts") {
                put("type", "array")
                put("description", "Server-local artifacts produced by the run (reports, bulk data, database captures): {name, mediaType, path}.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("mediaType") { put("type", "string") }
                        putJsonObject("path") { put("type", "string") }
                    }
                }
            }
        },
        required = listOf("resultId", "type"),
    )

    /** Validation verdict (validate_* tools): valid flag + field-level errors/warnings. */
    val validation = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("valid") { put("type", "boolean") }
            putJsonObject("errors") {
                put("type", "array")
                put("description", "Every validation error; empty when valid.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string"); put("description", "The offending field path.") }
                        putJsonObject("message") { put("type", "string") }
                        putJsonObject("code") { put("type", "string") }
                    }
                }
            }
            putJsonObject("warnings") {
                put("type", "array")
                put("description", "Non-fatal warnings (same shape as errors).")
            }
        },
        required = listOf("valid", "errors"),
    )

    /** Document preview (preview_* tools): canonical normalized document + its workload/cost. */
    val preview = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("documentType") { put("type", "string") }
            putJsonObject("canonical") { put("type", "object"); put("description", "The normalized document (defaults filled).") }
            putJsonObject("workload") {
                put("type", "object")
                put("description", "The work the document implies without running it (replication budget, design-point count, solver iterations, dataset/estimator counts).")
            }
        },
        required = listOf("documentType", "workload"),
    )

    /** submit_run: the accepted job's identity + cache disposition. */
    val job = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("jobId") { put("type", "string"); put("description", "Poll get_run_events / get_run_result with this.") }
            putJsonObject("status") { put("type", "string") }
            putJsonObject("resultId") { put("type", "string"); put("description", "The id the result is (or will be) retained under.") }
            putJsonObject("cached") { put("type", "boolean") }
            putJsonObject("reusedReplications") { put("type", "integer") }
        },
        required = listOf("jobId", "status", "resultId"),
    )

    /** get_run_events: a journaled-progress snapshot for polling. */
    val events = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("jobId") { put("type", "string") }
            putJsonObject("fromOffset") { put("type", "integer") }
            putJsonObject("nextOffset") { put("type", "integer"); put("description", "Pass as fromOffset on the next poll.") }
            putJsonObject("status") { put("type", "string") }
            putJsonObject("events") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type", "string") }
                        putJsonObject("detail") { put("type", "string") }
                    }
                }
            }
        },
        required = listOf("jobId", "nextOffset", "status", "events"),
    )

    /** cancel_run: whether a cancellation was issued to a running job. */
    val cancel = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("jobId") { put("type", "string") }
            putJsonObject("cancelled") {
                put("type", "boolean")
                put("description", "True when a cancel was issued to a running job; false when it was already finished, unknown, or evicted.")
            }
            putJsonObject("message") { put("type", "string") }
        },
        required = listOf("jobId", "cancelled", "message"),
    )

    /** list_responses: the response names in a retained result. */
    val responseNames = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("responses") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        },
        required = listOf("responses"),
    )

    /** get_artifacts: the rendered artifacts (reports, plot images, exports) retained for a result. */
    val artifacts = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("artifacts") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("mediaType") { put("type", "string") }
                        putJsonObject("path") { put("type", "string") }
                    }
                }
            }
        },
        required = listOf("artifacts"),
    )

    /** get_artifact: one artifact's metadata, with inline text content when the artifact is textual. */
    val artifact = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("mediaType") { put("type", "string") }
            putJsonObject("path") { put("type", "string") }
            putJsonObject("content") { put("type", "string"); put("description", "Inline text for textual artifacts (HTML/Markdown/text/CSV/JSON/SVG).") }
        },
        required = listOf("name", "mediaType", "path"),
    )

    /** db_open_external: the opened foreign database — a resultId (usable with the db_* tools) + its experiments. */
    val externalDb = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("resultId") {
                put("type", "string")
                put("description", "Pass this to db_status / db_experiments / db_summary / db_compare / db_view.")
            }
            putJsonObject("path") { put("type", "string") }
            putJsonObject("experiments") {
                put("type", "array")
                put("description", "The experiments recorded in the opened database.")
                putJsonObject("items") { put("type", "object") }
            }
        },
        required = listOf("resultId", "experiments"),
    )

    /** db_status: whether a result has an analyzable database. */
    val dbStatus = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("present") { put("type", "boolean") }
            putJsonObject("experimentCount") { put("type", "integer") }
            putJsonObject("message") { put("type", "string") }
        },
        required = listOf("present", "experimentCount", "message"),
    )

    /** db_experiments: the experiments in a result's database (or {present:false}). */
    val dbExperiments = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("experiments") {
                put("type", "array")
                putJsonObject("items") { put("type", "object") }
            }
            putJsonObject("present") { put("type", "boolean") }
        },
        required = emptyList(),
    )

    /** db_summary / db_compare: the analysis JSON (DataFrame-derived) under its key,
     *  or a guidance envelope when there is no database / the request is not analyzable. */
    val dbJson = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("summary") { put("type", "array"); put("description", "Across-replication statistics (db_summary).") }
            putJsonObject("comparison") { put("type", "object"); put("description", "MCB analysis {results, intervals, screening} (db_compare).") }
            putJsonObject("view") { put("type", "object"); put("description", "Statistical view envelope {view, total, returned, truncated, rows} (db_view).") }
            putJsonObject("present") { put("type", "boolean"); put("description", "false when the result has no database.") }
            putJsonObject("analyzable") { put("type", "boolean"); put("description", "false when preconditions are unmet.") }
            putJsonObject("reason") { put("type", "string") }
        },
        required = emptyList(),
    )

    /** db_views: the available statistical view names. */
    val dbViews = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("views") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("present") { put("type", "boolean") }
        },
        required = emptyList(),
    )

    /** get_response: one response's across-replication statistics. */
    val response = ToolSchema(
        properties = buildJsonObject { responseStatProperties() },
        required = listOf("name"),
    )

    /** get_result / get_design_point: a retained result (or one design point) projected back.
     *  Permissive — the shape is the run/fit/batch payload, discriminated by `type`/`kind`. */
    val projection = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("resultId") { put("type", "string") }
            putJsonObject("kind") { put("type", "string") }
            putJsonObject("type") { put("type", "string"); put("description", "Result shape discriminator (completed/batch/optimization).") }
        },
        required = emptyList(),
    )

    /** list_bundles: every bundle the server makes available. */
    val bundles = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("bundles") {
                put("type", "array")
                put("description", "All available bundles (bundleId + the model ids each provides).")
            }
            putJsonObject("skipped") {
                put("type", "array")
                put(
                    "description",
                    "JARs in the bundle directories that were refused — not a KSL bundle, or an " +
                        "incomplete bundle (missing embedded descriptors): {jar, reason}. Tell the user " +
                        "to (re)assemble them with 'kslpkg assemble' or the Bundle Workbench.",
                )
            }
            putJsonObject("conflicts") {
                put("type", "array")
                put(
                    "description",
                    "bundleId collisions resolved newest-wins: {bundleId, activeSource, shadowedSources}. " +
                        "The shadowed copies stay loaded but inactive; report them so the operator can prune duplicates.",
                )
            }
        },
        required = listOf("bundles"),
    )

    /** list_models: the models a bundle provides. */
    val models = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("bundleId") { put("type", "string") }
            putJsonObject("models") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        },
        required = listOf("bundleId", "models"),
    )

    /** describe_model: a model's task kinds, responses, and JSON Schemas for its I/O. */
    val modelDescriptor = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("modelIdentifier") { put("type", "string") }
            putJsonObject("modelName") { put("type", "string") }
            putJsonObject("supportedApps") {
                put("type", "array"); putJsonObject("items") { put("type", "string") }
                put("description", "The model's declared task kinds (the agent's menu of intents).")
            }
            putJsonObject("responseNames") {
                put("type", "array"); putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("hasCatalog") { put("type", "boolean") }
            putJsonObject("inputSchema") { put("type", "object"); put("description", "JSON Schema for run arguments.") }
            putJsonObject("outputSchema") { put("type", "object"); put("description", "JSON Schema for the model's outputs.") }
        },
        required = listOf("modelIdentifier", "modelName", "responseNames"),
    )

    /** *_template: a config document scaffold to edit and submit. The raw
     *  document is the text content; `document` is the same parsed for typed access. */
    val document = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("documentType") { put("type", "string") }
            putJsonObject("kind") { put("type", "string") }
            putJsonObject("document") { put("type", "object"); put("description", "The config document as a parsed object.") }
        },
        required = emptyList(),
    )

    /** validate_animation_layout: whether every checked binding matched, plus each unmatched
     *  binding (kind + name + a "did you mean" message). */
    val animationLayoutValidation = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("isValid") { put("type", "boolean") }
            putJsonObject("issues") {
                put("type", "array")
                put("description", "Unmatched bindings; empty when the layout is valid. Each is " +
                    "{kind (UNMATCHED_QUEUE / _RESOURCE / _MOVABLE_RESOURCE / _RESPONSE / _SELECTOR), " +
                    "name, message (with a nearest-name hint)}.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("kind") { put("type", "string") }
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("message") { put("type", "string") }
                    }
                }
            }
        },
        required = listOf("isValid", "issues"),
    )

    /** get_fit_scoring: the MODA scoring matrix (raw `scores` + scaled value-function `values`). */
    val fitScoring = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("modelName") { put("type", "string") }
            putJsonObject("rankingMethod") { put("type", "string") }
            putJsonObject("metrics") {
                put("type", "array"); put("description", "The MODA metrics with their weights.")
            }
            putJsonObject("scores") { put("type", "array"); put("description", "Raw metric values per alternative.") }
            putJsonObject("values") {
                put("type", "array")
                put("description", "Scaled value-function outputs in [0,1] per alternative (the recommendation basis).")
            }
            putJsonObject("rankFrequencies") { put("type", "array") }
        },
        required = listOf("metrics", "values"),
    )

    /** get_fit_report: the written HTML report's location and whether plots were embedded. */
    val fitReport = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("resultId") { put("type", "string") }
            putJsonObject("reportPath") { put("type", "string"); put("description", "Absolute path to the HTML report to open in a browser.") }
            putJsonObject("includedPlots") { put("type", "boolean"); put("description", "True when diagnostic plots were embedded (a display was available).") }
        },
        required = listOf("resultId", "reportPath", "includedPlots"),
    )

    /** list_distributions: scalar-parameter distribution families available for variate generation. */
    val distributions = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("distributions") {
                put("type", "array")
                put("description", "All scalar-parameter distribution families the server can sample from.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("familyId") { put("type", "string") }
                        putJsonObject("displayName") { put("type", "string") }
                        putJsonObject("kind") { put("type", "string"); put("description", "CONTINUOUS or DISCRETE.") }
                        putJsonObject("parameters") { put("type", "object"); put("description", "Scalar parameters: name → {type, default}.") }
                    }
                }
            }
        },
        required = listOf("distributions"),
    )

    /** generate_variates: a random sample from a named scalar-parameter distribution. */
    val variates = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("familyId") { put("type", "string") }
            putJsonObject("n") { put("type", "integer") }
            putJsonObject("truncated") {
                put("type", "boolean")
                put("description", "True when `values` is a leading preview; the complete sample is in the CSV at filePath.")
            }
            putJsonObject("filePath") {
                put("type", "string")
                put("description", "Absolute path to the CSV of the full sample. Present when the sample was written (auto for large n, or output=true).")
            }
            putJsonObject("values") {
                put("type", "array")
                put("description", "The generated random variate sample (a leading preview when truncated).")
                putJsonObject("items") { put("type", "number") }
            }
        },
        required = listOf("familyId", "n", "truncated", "values"),
    )

    /** summarize_data / get_fit_data_summary: the engine's statistical summary (+ histogram) of a data series. */
    val dataSummary = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("datasetName") { put("type", "string") }
            putJsonObject("dataSummary") {
                put("type", "object")
                put("description", "The engine's full statistical summary of the data series.")
                putJsonObject("properties") {
                    putJsonObject("statistics") {
                        put("type", "object")
                        put(
                            "description",
                            "Full StatisticIfc summary: count, average, standardDeviation, standardError, " +
                                "halfWidth, confidenceLevel, lowerLimit, upperLimit, min, max, sum, variance, " +
                                "skewness, kurtosis, and lag-1 / von Neumann statistics.",
                        )
                    }
                    putJsonObject("zeroCount") { put("type", "integer") }
                    putJsonObject("negativeCount") { put("type", "integer") }
                    putJsonObject("positiveCount") { put("type", "integer") }
                }
            }
            putJsonObject("histogram") {
                put("type", "object")
                put(
                    "description",
                    "Equal-bin histogram (continuous data): bins with limits, count, and proportion plus " +
                        "under/overflow counts. Absent for a discrete fit or when the data cannot be binned.",
                )
                putJsonObject("properties") {
                    putJsonObject("bins") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("binNum") { put("type", "integer") }
                                putJsonObject("lowerLimit") { put("type", "number") }
                                putJsonObject("upperLimit") { put("type", "number") }
                                putJsonObject("count") { put("type", "number") }
                                putJsonObject("proportion") { put("type", "number") }
                            }
                        }
                    }
                    putJsonObject("underFlowCount") { put("type", "number") }
                    putJsonObject("overFlowCount") { put("type", "number") }
                }
            }
        },
        required = listOf("datasetName", "dataSummary"),
    )

    /** acf_analysis: the sample autocorrelation function + a white-noise band and independence verdict. */
    val acf = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("n") { put("type", "integer") }
            putJsonObject("maxLag") { put("type", "integer") }
            putJsonObject("whiteNoiseBand") { put("type", "number"); put("description", "±1.96/√n; |acf| beyond it is significant at ~95%.") }
            putJsonObject("lag1") { put("type", "number") }
            putJsonObject("independentAtLag1") { put("type", "boolean") }
            putJsonObject("acf") {
                put("type", "array")
                put("description", "Autocorrelation per lag (1..maxLag).")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("lag") { put("type", "integer") }
                        putJsonObject("value") { put("type", "number") }
                        putJsonObject("significant") { put("type", "boolean") }
                    }
                }
            }
        },
        required = listOf("n", "maxLag", "acf"),
    )

    /** shift_analysis: the standalone left-shift estimate for a data series. */
    val shift = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("n") { put("type", "integer") }
            putJsonObject("leftShift") { put("type", "number"); put("description", "The left shift a fit would apply; 0 means none.") }
            putJsonObject("dataMin") { put("type", "number") }
            putJsonObject("shiftRecommended") { put("type", "boolean") }
        },
        required = listOf("n", "leftShift"),
    )

    /** family_frequency_bootstrap: how often each distribution family is the recommended fit across resamples. */
    val familyBootstrap = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("datasetName") { put("type", "string") }
            putJsonObject("numSamples") { put("type", "integer") }
            putJsonObject("frequency") {
                put("type", "object")
                put("description", "Family tally: cells[] each carry cellLabel (the family), count, and proportion across resamples.")
                putJsonObject("properties") {
                    putJsonObject("cells") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("value") { put("type", "integer") }
                                putJsonObject("count") { put("type", "number") }
                                putJsonObject("proportion") { put("type", "number") }
                                putJsonObject("cellLabel") { put("type", "string"); put("description", "The distribution-family name.") }
                            }
                        }
                    }
                }
            }
        },
        required = listOf("datasetName", "numSamples", "frequency"),
    )

    /** get_workspace / set_workspace: the active KSL workspace and the MCP server's app directory. */
    val workspace = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("workspace") {
                put("type", "string")
                put("description", "Absolute path to the active KSL workspace (shared with the other KSL apps via ~/.ksl/settings.toml).")
            }
            putJsonObject("appDir") {
                put("type", "string")
                put("description", "The MCP server's subdirectory under the workspace, where it writes reports and generated data.")
            }
            putJsonObject("isDefault") {
                put("type", "boolean")
                put("description", "True when using the out-of-the-box default workspace (no user override set). Present on get_workspace.")
            }
            putJsonObject("previous") {
                put("type", "string")
                put("description", "The prior workspace path. Present on set_workspace.")
            }
            putJsonObject("recentWorkspaces") {
                put("type", "array")
                put("description", "Recently used workspace directories (most-recent first), shared with the KSL desktop apps.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("recentConfigurations") {
                put("type", "array")
                put("description", "Recently saved/loaded TOML configuration files (most-recent first), shared with the KSL desktop apps.")
                putJsonObject("items") { put("type", "string") }
            }
        },
        required = listOf("workspace", "appDir"),
    )

    /** Result of fit_dataset / fit_config: the ranked candidate distributions. */
    val fit = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("resultId") { put("type", "string") }
            putJsonObject("cached") { put("type", "boolean") }
            putJsonObject("kind") { put("type", "string") }
            putJsonObject("datasetName") { put("type", "string") }
            putJsonObject("recommendedFamilyId") { put("type", "string"); put("description", "The recommended distribution family.") }
            putJsonObject("fits") {
                put("type", "array")
                put("description", "Ranked candidate distributions (best first).")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("rank") { put("type", "integer") }
                        putJsonObject("familyId") { put("type", "string") }
                        putJsonObject("displayName") { put("type", "string") }
                        putJsonObject("weightedValue") {
                            put("type", "number")
                            put("description", "Scaled MODA value in [0,1] — the ranking basis (continuous fits).")
                        }
                        putJsonObject("averageRanking") {
                            put("type", "number")
                            put("description", "Average rank across the MODA metrics.")
                        }
                        putJsonObject("parameters") { put("type", "object") }
                        putJsonObject("goodnessOfFit") { put("type", "object"); put("description", "Statistic(s) + p-value(s).") }
                    }
                }
            }
            putJsonObject("scoring") {
                put("type", "object")
                put(
                    "description",
                    "The MODA scoring matrix (metrics + weights, scaled value-function outputs per candidate); " +
                        "present for continuous fits. Full detail via get_fit_scoring.",
                )
            }
            putJsonObject("dataSummary") {
                put("type", "object")
                put("description", "The engine's statistical summary of the fitted data series (count, moments, min/max, …).")
            }
        },
        required = listOf("resultId", "datasetName", "fits"),
    )
}

/**
 * The full set of `ResponseStatDto` fields, shared by every schema where a response's
 * across-replication statistics appear (a run's `responses`, a batch item's `responses`,
 * and get_response), so the declared shape never drifts from the DTO. Populates the
 * enclosing `properties` object in place.
 */
private fun JsonObjectBuilder.responseStatProperties() {
    putJsonObject("name") { put("type", "string") }
    putJsonObject("count") { put("type", "number") }
    putJsonObject("average") { put("type", "number") }
    putJsonObject("stdDev") { put("type", "number") }
    putJsonObject("stdErr") { put("type", "number") }
    putJsonObject("halfWidth") { put("type", "number"); put("description", "95% CI half-width.") }
    putJsonObject("confLevel") { put("type", "number") }
    putJsonObject("min") { put("type", "number") }
    putJsonObject("max") { put("type", "number") }
    putJsonObject("sum") {
        put("type", "number")
        put("description", "Sum of the per-replication values (Sx); with count and deviationSumOfSquares, pools disjoint runs exactly.")
    }
    putJsonObject("deviationSumOfSquares") {
        put("type", "number")
        put("description", "Deviation sum of squares of the per-replication values.")
    }
}
