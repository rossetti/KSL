package ksl.code.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

private val logger = KotlinLogging.logger {}

/**
 * Runs the MCP server over stdin/stdout and blocks until the client closes the
 * session. stdout carries only JSON-RPC; logging goes to stderr.
 */
fun runStdioServer() {
    logger.info { "ksl-code-mcp ${BuildInfo.version} starting on stdio" }
    val store = CodeStore.instance
    val search = CodeSearch(store)
    // build the index at startup, not on the first search: a slow or failing build
    // surfaces here in the log, and the first tool call stays fast
    search.warmUp()
    val server = CodeMcpServer.build(store, search)
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    )
    runBlocking {
        val done = Job()
        server.onClose { done.complete() }
        server.createSession(transport)
        done.join()
    }
    logger.info { "server closed" }
}
