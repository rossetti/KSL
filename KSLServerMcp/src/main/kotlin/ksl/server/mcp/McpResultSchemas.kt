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
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("average") { put("type", "number") }
                        putJsonObject("stdDev") { put("type", "number") }
                        putJsonObject("stdErr") { put("type", "number") }
                        putJsonObject("halfWidth") { put("type", "number"); put("description", "95% CI half-width.") }
                        putJsonObject("confLevel") { put("type", "number") }
                        putJsonObject("count") { put("type", "number") }
                        putJsonObject("min") { put("type", "number") }
                        putJsonObject("max") { put("type", "number") }
                    }
                }
            }
            putJsonObject("items") {
                put("type", "array")
                put("description", "Per design-point / scenario results (present when type=batch).")
            }
            putJsonObject("best") {
                put("type", "object")
                put("description", "Best solution found (present when type=optimization).")
            }
            putJsonObject("iterations") {
                put("type", "array")
                put("description", "Optimization iteration trace (present when type=optimization).")
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

    /** get_response: one response's across-replication statistics. */
    val response = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("average") { put("type", "number") }
            putJsonObject("stdDev") { put("type", "number") }
            putJsonObject("stdErr") { put("type", "number") }
            putJsonObject("halfWidth") { put("type", "number"); put("description", "95% CI half-width.") }
            putJsonObject("count") { put("type", "number") }
            putJsonObject("min") { put("type", "number") }
            putJsonObject("max") { put("type", "number") }
        },
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

    /** list_recipes: the author-curated config recipes for a model. */
    val recipes = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("recipes") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("kind") { put("type", "string"); put("description", "RUN, SCENARIO_BATCH, EXPERIMENT, or OPTIMIZATION.") }
                    }
                }
            }
        },
        required = listOf("recipes"),
    )

    /** get_recipe / *_template: a config document scaffold to edit and submit. The raw
     *  document is the text content; `document` is the same parsed for typed access. */
    val document = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("documentType") { put("type", "string") }
            putJsonObject("name") { put("type", "string"); put("description", "Present for a named recipe.") }
            putJsonObject("kind") { put("type", "string") }
            putJsonObject("document") { put("type", "object"); put("description", "The config document as a parsed object.") }
        },
        required = emptyList(),
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
                        putJsonObject("parameters") { put("type", "object") }
                        putJsonObject("goodnessOfFit") { put("type", "object"); put("description", "Statistic(s) + p-value(s).") }
                    }
                }
            }
        },
        required = listOf("resultId", "datasetName", "fits"),
    )
}
