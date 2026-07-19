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

package ksl.server.suite.code

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import ksl.code.search.CodeSearch
import ksl.code.search.CodeStore
import ksl.service.usage.ToolUsageRecorder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger {}

private const val KSL = "the Kotlin Simulation Library (KSL) — the discrete-event simulation library the " +
    "student's course is built on (https://github.com/rossetti/KSL)"

/**
 * Registers the eight source-code tools onto a (possibly shared) MCP server, over a KSLCodeSearch
 * `CodeStore` + `CodeSearch`. Every call is timed and recorded through [recorder] for the usage
 * study (a no-op with the default NONE recorder). Copied from the standalone KSLCodeMCPServer's tool
 * registration; the standalone `build()` server factory is intentionally omitted.
 */
object CodeToolRegistry {

    fun registerCodeTools(
        server: Server,
        store: CodeStore = CodeStore.instance,
        search: CodeSearch = CodeSearch(store),
        recorder: ToolUsageRecorder = ToolUsageRecorder.NONE,
        session: ksl.service.usage.ToolCallSession? = null,
    ) {
        val handlers = CodeToolHandlers(store, search)

        // The recording registrar (search-aware): the handler returns a ToolReply (text + optional
        // resultCount/topScore). addCodeTool wraps a plain-String handler over this for non-search tools.
        fun Server.addRecordedTool(
            name: String,
            description: String,
            properties: JsonObject = buildJsonObject {},
            required: List<String> = emptyList(),
            handler: (JsonObject) -> ksl.server.suite.ToolReply,
        ) {
            addTool(name, description, ToolSchema(properties, required)) { request ->
                val args = request.arguments ?: buildJsonObject {}
                val start = System.currentTimeMillis()
                var ok = false
                var errorClass: String? = null
                var errorSummary: String? = null
                var reply: ksl.server.suite.ToolReply? = null
                try {
                    reply = handler(args)
                    ok = true
                    logger.info { "$name completed in ${System.currentTimeMillis() - start} ms" }
                    CallToolResult(listOf(TextContent(reply.text)))
                } catch (e: ToolInputException) {
                    errorClass = "INVALID_INPUT"
                    errorSummary = e.message?.take(200)
                    CallToolResult(listOf(TextContent(e.message ?: "Invalid input.")), true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable, not Exception: even an Error must produce a response — an
                    // unanswered request looks like a client timeout.
                    errorClass = ksl.service.usage.UsageErrors.classify(e)
                    errorSummary = e.message?.take(200)
                    logger.error(e) { "tool $name failed" }
                    CallToolResult(
                        listOf(TextContent("Internal error in $name: ${e::class.simpleName}: ${e.message}")),
                        true,
                    )
                } finally {
                    recorder.record(
                        name, System.currentTimeMillis() - start, ok,
                        ksl.service.usage.UsageDetails(
                            sessionId = session?.sessionId, client = session?.client?.invoke(),
                            errorClass = errorClass, errorSummary = errorSummary,
                            query = args.string("query") ?: args.string("topic"),
                            target = args.string("fqn"),
                            resultCount = reply?.resultCount, topScore = reply?.topScore,
                            intent = args.string("intent"),
                        ),
                    )
                }
            }
        }

        fun Server.addCodeTool(
            name: String,
            description: String,
            properties: JsonObject = buildJsonObject {},
            required: List<String> = emptyList(),
            handler: (JsonObject) -> String,
        ) = addRecordedTool(name, description, properties, required) { ksl.server.suite.ToolReply(handler(it)) }

        server.addRecordedTool(
            name = "search_code",
            description = "Full-text search over the KSL source — $KSL. Use this FIRST for any question " +
                "about a KSL class, interface, function, or API. Returns ranked declarations with kind, " +
                "fully-qualified name, signature, a KDoc summary, and the source URL. Example query: " +
                "\"seize release a resource\" or \"exponential random variate\". Follow up with get_class " +
                "for the full API or get_example for worked usage.",
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search terms, e.g. \"queue discipline priority\" or \"ProcessModel suspend\"")
                }
                putJsonObject("maxResults") {
                    put("type", "integer")
                    put("description", "Number of results (default 5, max 15)")
                }
                putJsonObject("module") {
                    put("type", "string")
                    put("description", "Optional filter: \"KSLCore\" (the API) or \"KSLExamples\" (worked programs)")
                }
                putJsonObject("intent") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional: the student's original question in their own words. Pass it (for the " +
                            "local course usage study) when the search terms are your paraphrase of what they asked.",
                    )
                }
            },
            required = listOf("query"),
        ) { args ->
            handlers.searchCode(
                query = args.string("query") ?: throw ToolInputException("query is required."),
                maxResults = args.int("maxResults") ?: 5,
                module = args.string("module"),
            )
        }

        server.addCodeTool(
            name = "get_class",
            description = "Get the full API of one KSL declaration by fully-qualified name (e.g. " +
                "\"ksl.modeling.entity.Resource\"), simple name (\"Resource\"), or an id from search_code: " +
                "its kind, signature, supertypes, complete KDoc, public member signatures, and the example " +
                "files that use it. Use after search_code, or directly when the student names a class.",
            properties = buildJsonObject {
                putJsonObject("fqn") {
                    put("type", "string")
                    put("description", "Fully-qualified name, simple name, or search_code id of the declaration")
                }
            },
            required = listOf("fqn"),
        ) { args ->
            handlers.getClass(args.string("fqn") ?: throw ToolInputException("fqn is required."))
        }

        server.addCodeTool(
            name = "get_example",
            description = "List the KSLExamples files that use a given KSL declaration, with source URLs, " +
                "so the student can read a worked program that exercises the API. Pass a fully-qualified or " +
                "simple name (e.g. \"ProcessModel\").",
            properties = buildJsonObject {
                putJsonObject("fqn") {
                    put("type", "string")
                    put("description", "Fully-qualified or simple name of the declaration to find examples for")
                }
            },
            required = listOf("fqn"),
        ) { args ->
            handlers.getExample(args.string("fqn") ?: throw ToolInputException("fqn is required."))
        }

        server.addCodeTool(
            name = "get_package_overview",
            description = "List every public declaration in a KSL package (e.g. \"ksl.modeling.entity\" or " +
                "\"ksl.modeling.queue\"), grouped by kind, each with a one-line KDoc summary. Use to orient " +
                "in an area of the library or when asked \"what's in package X\".",
            properties = buildJsonObject {
                putJsonObject("packageName") {
                    put("type", "string")
                    put("description", "Package name, e.g. \"ksl.modeling.variable\"")
                }
            },
            required = listOf("packageName"),
        ) { args ->
            handlers.getPackageOverview(args.string("packageName") ?: throw ToolInputException("packageName is required."))
        }

        server.addCodeTool(
            name = "find_subclasses",
            description = "List the declarations that extend or implement a given KSL type, by name (e.g. " +
                "\"ModelElement\" or \"ksl.simulation.ModelElement\"). Use to explore a type hierarchy, e.g. " +
                "\"what kinds of ModelElement are there?\".",
            properties = buildJsonObject {
                putJsonObject("fqn") {
                    put("type", "string")
                    put("description", "Fully-qualified or simple name of the supertype")
                }
            },
            required = listOf("fqn"),
        ) { args ->
            handlers.findSubclasses(args.string("fqn") ?: throw ToolInputException("fqn is required."))
        }

        server.addCodeTool(
            name = "get_related_examples",
            description = "Find KSLExamples programs related to a topic (e.g. \"inventory\", \"tandem queue\", " +
                "\"non-stationary arrivals\"). Returns example file paths and URLs. Use when the student " +
                "wants a worked example for a concept rather than a specific class.",
            properties = buildJsonObject {
                putJsonObject("topic") {
                    put("type", "string")
                    put("description", "A topic or concept, e.g. \"resource seize release\" or \"reliability\"")
                }
            },
            required = listOf("topic"),
        ) { args ->
            handlers.getRelatedExamples(args.string("topic") ?: throw ToolInputException("topic is required."))
        }

        server.addCodeTool(
            name = "list_modules",
            description = "List the KSL modules this server indexes with their declaration counts and the " +
                "packages each contains. Use to get the lay of the library or to validate a package/module name.",
        ) { _ -> handlers.listModules() }

        server.addCodeTool(
            name = "get_server_info",
            description = "Report what this server was built from: server version, the KSL git ref indexed, " +
                "the index build date, and the total declaration count. Use to verify the index is current " +
                "with the course's KSL version.",
        ) { _ -> handlers.getServerInfo() }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotBlank() }?.content

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()
}
