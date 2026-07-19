package ksl.code.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger {}

private const val KSL = "the Kotlin Simulation Library (KSL) — the discrete-event simulation library the " +
    "student's course is built on (https://github.com/rossetti/KSL)"

/**
 * Builds the MCP server exposing searchable access to the KSL source: the public
 * API of KSLCore and the worked programs in KSLExamples, extracted at build time
 * and bundled in the jar. Mirrors the KSL Book MCP server's shape (tools return
 * markdown; a handshake `instructions` string steers the client to consult the
 * code first for API questions).
 */
object CodeMcpServer {

    fun build(
        store: CodeStore = CodeStore.instance,
        search: CodeSearch = CodeSearch(store),
    ): Server {
        val server = Server(
            Implementation(name = "ksl-code-mcp", version = BuildInfo.version),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
            instructions = "This server provides the actual source code of the Kotlin Simulation Library " +
                "(KSL) that the student's course and homework use: the public API of KSLCore (classes, " +
                "interfaces, objects, functions, with their KDoc and signatures) and the worked programs " +
                "in KSLExamples. For ANY question about a KSL class or function, how to use a KSL API, " +
                "what a declaration does, which classes extend a type, or for a code example, consult this " +
                "server FIRST — call search_code before answering from general knowledge, then get_class " +
                "for the full API and get_example for worked usage, and cite the returned source URLs. " +
                "Do not invent KSL method names or signatures: verify them here. This is the KSL source " +
                "at ref ${store.meta.kslVersion}. (For simulation theory and textbook concepts, the " +
                "companion ksl-book server has the textbook; for running models, the ksl model server.)",
        )
        registerCodeTools(server, store, search)
        return server
    }

    /**
     * Registers the eight source-code tools onto an existing (possibly shared) MCP server, so the
     * same tools back either the standalone code server (via `build`) or the aggregated KSLMcpSuite.
     */
    fun registerCodeTools(
        server: Server,
        store: CodeStore = CodeStore.instance,
        search: CodeSearch = CodeSearch(store),
    ) {
        val handlers = ToolHandlers(store, search)

        server.addCodeTool(
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

    private fun Server.addCodeTool(
        name: String,
        description: String,
        properties: JsonObject = buildJsonObject {},
        required: List<String> = emptyList(),
        handler: (JsonObject) -> String,
    ) {
        addTool(name, description, ToolSchema(properties, required)) { request ->
            val start = System.currentTimeMillis()
            try {
                val text = handler(request.arguments ?: buildJsonObject {})
                logger.info { "$name completed in ${System.currentTimeMillis() - start} ms" }
                CallToolResult(listOf(TextContent(text)))
            } catch (e: ToolInputException) {
                CallToolResult(listOf(TextContent(e.message ?: "Invalid input.")), true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: even an Error must produce a response — an
                // unanswered request looks like a client timeout.
                logger.error(e) { "tool $name failed" }
                CallToolResult(
                    listOf(TextContent("Internal error in $name: ${e::class.simpleName}: ${e.message}")),
                    true,
                )
            }
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotBlank() }?.content

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()
}
