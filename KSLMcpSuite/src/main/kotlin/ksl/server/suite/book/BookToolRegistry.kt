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

package ksl.server.suite.book

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import ksl.book.search.BookSearch
import ksl.book.search.BookStore
import ksl.service.usage.ToolUsageRecorder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger {}

private const val BOOK = "the textbook \"Simulation Modeling using the Kotlin Simulation Library (KSL)\" " +
    "by M. Rossetti (https://rossetti.github.io/KSLBook/)"

/**
 * Registers the six textbook tools onto a (possibly shared) MCP server, over a KSLBookSearch
 * `BookStore` + `BookSearch`. Every call is timed and recorded through [recorder] for the usage
 * study (a no-op with the default NONE recorder). Copied from the standalone KSLBookServer's tool
 * registration; the standalone `build()` server factory is intentionally omitted.
 */
object BookToolRegistry {

    fun registerBookTools(
        server: Server,
        store: BookStore = BookStore.instance,
        search: BookSearch = BookSearch(store),
        recorder: ToolUsageRecorder = ToolUsageRecorder.NONE,
        session: ksl.service.usage.ToolCallSession? = null,
    ) {
        val handlers = BookToolHandlers(store, search)

        // The recording registrar: a tool whose handler returns a ToolReply (text + optional search
        // metadata). Times the call, classifies failures, and records the usage event with the session +
        // args-derived query/target + the reply's resultCount/topScore. addBookTool wraps a plain-String
        // handler over this for the non-search tools (their definitions are unchanged).
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
                    // Throwable, not Exception: an Error (class loading, OOM) must still
                    // produce a response — an unanswered request looks like a client timeout
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
                            query = args.string("query"),
                            target = args.string("section") ?: args.string("chapter"),
                            resultCount = reply?.resultCount, topScore = reply?.topScore,
                        ),
                    )
                }
            }
        }

        fun Server.addBookTool(
            name: String,
            description: String,
            properties: JsonObject = buildJsonObject {},
            required: List<String> = emptyList(),
            handler: (JsonObject) -> String,
        ) = addRecordedTool(name, description, properties, required) { ksl.server.suite.ToolReply(handler(it)) }

        server.addRecordedTool(
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
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotBlank() }?.content

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()
}
