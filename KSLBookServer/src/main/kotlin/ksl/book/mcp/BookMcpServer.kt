package ksl.book.mcp

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

private const val BOOK = "the textbook \"Simulation Modeling using the Kotlin Simulation Library (KSL)\" " +
    "by M. Rossetti (https://rossetti.github.io/KSLBook/)"

object BookMcpServer {

    fun build(
        store: BookStore = BookStore.instance,
        search: BookSearch = BookSearch(store),
    ): Server {
        val handlers = ToolHandlers(store, search)
        val server = Server(
            Implementation(name = "ksl-book-mcp", version = BuildInfo.version),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
            // sent in the initialize handshake; Claude Desktop injects this into the
            // model's context, steering it to the book without the student asking
            instructions = "This server provides the student's course textbook: \"Simulation Modeling " +
                "using the Kotlin Simulation Library (KSL)\" by M. Rossetti. The student is taking a " +
                "simulation course based on this book. For ANY question about simulation modeling, " +
                "discrete-event simulation, random variate generation, simulation statistics, queueing, " +
                "the KSL library and its code, or course homework, consult this textbook FIRST: call " +
                "search_textbook before answering from general knowledge and before any web search, " +
                "then read the relevant section with get_section and cite its URL in the answer. " +
                "If unsure whether the book covers a topic, check list_chapters — it covers simulation " +
                "methodology, Monte Carlo, discrete-event modeling, statistical analysis of simulation " +
                "output, process modeling, comparing systems, experimental design, simulation " +
                "optimization, plus appendices on random generation, distribution fitting, and queueing " +
                "formulas.",
        )

        server.addBookTool(
            name = "search_textbook",
            description = "Full-text search over $BOOK — the student's course textbook. Use this FIRST, " +
                "before web search or answering from general knowledge, for any question about " +
                "simulation concepts, statistical methods, KSL classes and APIs, homework, or where a " +
                "topic is covered in the course. Returns ranked sections with number, title, id, URL, " +
                "and a snippet. Example query: \"drive through pharmacy event scheduling\". " +
                "Follow up with get_section to read the full text.",
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search terms, e.g. \"generating random variates inverse transform\"")
                }
                putJsonObject("maxResults") {
                    put("type", "integer")
                    put("description", "Number of results to return (default 5, max 10)")
                }
            },
            required = listOf("query"),
        ) { args ->
            handlers.searchTextbook(
                query = args.string("query") ?: throw ToolInputException("query is required."),
                maxResults = args.int("maxResults") ?: 5,
            )
        }

        server.addBookTool(
            name = "get_section",
            description = "Get the full markdown content of one section of $BOOK, identified by " +
                "section number (e.g. \"4.4.4\" or appendix \"A.2\") or section id (e.g. " +
                "\"introDEDSPharmacy\"). The response includes the citation URL, chapter, subsection " +
                "contents or listing, and previous/next navigation. Use after search_textbook, or " +
                "directly when the student names a section.",
            properties = buildJsonObject {
                putJsonObject("section") {
                    put("type", "string")
                    put("description", "Section number (\"4.4.4\", \"A.2\") or section id (\"introDEDSPharmacy\")")
                }
            },
            required = listOf("section"),
        ) { args ->
            handlers.getSection(args.string("section") ?: throw ToolInputException("section is required."))
        }

        server.addBookTool(
            name = "get_chapter_outline",
            description = "Get the outline of one chapter or appendix of $BOOK: every section and " +
                "subsection with numbers, titles, ids, and flags for code examples and exercises. " +
                "Use to orient before drilling into sections, or when asked \"what's in chapter X\".",
            properties = buildJsonObject {
                putJsonObject("chapter") {
                    put("type", "string")
                    put("description", "Chapter number (\"4\") or appendix letter (\"A\")")
                }
            },
            required = listOf("chapter"),
        ) { args ->
            handlers.getChapterOutline(args.string("chapter") ?: throw ToolInputException("chapter is required."))
        }

        server.addBookTool(
            name = "list_chapters",
            description = "List every chapter and appendix of $BOOK with number, title, section count, " +
                "and exercise count. Use to get the lay of the book or to validate a chapter reference.",
        ) { _ ->
            handlers.listChapters()
        }

        server.addBookTool(
            name = "get_exercises",
            description = "Get the homework exercises of a chapter of $BOOK as markdown with exercise " +
                "numbers and citation URLs. Pass exercise to fetch a single one (e.g. chapter \"4\", " +
                "exercise \"4.3\"). Use when the student asks about homework or practice problems.",
            properties = buildJsonObject {
                putJsonObject("chapter") {
                    put("type", "string")
                    put("description", "Chapter number (\"4\") or appendix letter (\"A\")")
                }
                putJsonObject("exercise") {
                    put("type", "string")
                    put("description", "Optional single exercise number, e.g. \"4.3\" (or just \"3\")")
                }
            },
            required = listOf("chapter"),
        ) { args ->
            handlers.getExercises(
                chapter = args.string("chapter") ?: throw ToolInputException("chapter is required."),
                exercise = args.string("exercise"),
            )
        }

        server.addBookTool(
            name = "get_related_sections",
            description = "Find up to 5 sections of $BOOK related to a given section by content " +
                "similarity, excluding its own subsections and siblings. Use to point the student at " +
                "further reading, e.g. after explaining a section.",
            properties = buildJsonObject {
                putJsonObject("section") {
                    put("type", "string")
                    put("description", "Section number (\"4.4.4\", \"A.2\") or section id (\"introDEDSPharmacy\")")
                }
            },
            required = listOf("section"),
        ) { args ->
            handlers.getRelatedSections(args.string("section") ?: throw ToolInputException("section is required."))
        }

        return server
    }

    private fun Server.addBookTool(
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
                // Throwable, not Exception: an Error (class loading, OOM) must still
                // produce a response — an unanswered request looks like a client timeout
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
