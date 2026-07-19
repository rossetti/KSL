package ksl.book.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/**
 * Runs the MCP server over stdin/stdout and blocks until the client closes
 * the session. stdout carries only JSON-RPC; logging goes to stderr.
 */
fun runStdioServer() {
    logger.info { "ksl-book-mcp ${BuildInfo.version} starting on stdio" }
    val store = BookStore.instance
    val search = BookSearch(store)
    // index at startup, not on the first search: a slow or failing build surfaces
    // here in the log, and the first tool call stays fast
    search.warmUp()
    val server = BookMcpServer.build(store, search)
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered(),
    )
    runBlocking {
        // Complete `done` from the TRANSPORT's onClose, not the Server's: the SDK fires session
        // teardown on stdin EOF (client disconnect) but never the server-level onClose, so hooking
        // server.onClose left runBlocking parked forever — the orphaned-JVM leak. Registering on
        // the transport BEFORE createSession chains ahead of the session's own teardown, no race.
        val done = Job()
        transport.onClose { done.complete() }
        server.createSession(transport)
        done.join()
    }
    logger.info { "server closed" }
    // Force JVM exit rather than returning, so a now-clientless background process cannot linger.
    exitProcess(0)
}
