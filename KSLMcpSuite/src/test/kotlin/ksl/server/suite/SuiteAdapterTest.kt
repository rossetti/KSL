package ksl.server.suite

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import ksl.book.search.BookSearch
import ksl.book.search.BookStore
import ksl.code.search.CodeSearch
import ksl.code.search.CodeStore
import ksl.server.suite.book.BookToolHandlers
import ksl.server.suite.code.CodeToolHandlers
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Smoke test for the suite-side MCP adapters copied from the old servers and retargeted onto the
 * KSLBookSearch / KSLCodeSearch libraries, plus the capability wiring. The full tools/list union
 * (including the simulation surface) is verified live through the bridge at the Phase B gate.
 */
class SuiteAdapterTest {

    @Test
    @DisplayName("code handlers return real results over the bundled index")
    fun codeHandlersWorkOverRealIndex() {
        val store = CodeStore.instance
        val handlers = CodeToolHandlers(store, CodeSearch(store))
        assertTrue(store.meta.declarationCount > 0, "code index should be non-empty")
        assertTrue(handlers.searchCode("resource seize release", 5, null).isNotBlank())
        assertTrue("KSLCore" in handlers.listModules())
        assertTrue("declarations" in handlers.getServerInfo())
    }

    @Test
    @DisplayName("book handlers respond gracefully whether or not the book was rendered")
    fun bookHandlersTolerateEmpty() {
        val store = BookStore.instance
        val handlers = BookToolHandlers(store, BookSearch(store))
        // listChapters touches no search index, so it is safe with empty content (_book absent).
        assertTrue(handlers.listChapters().isNotBlank())
    }

    @Test
    @DisplayName("book + code capabilities register onto one server without collision")
    fun capabilitiesRegisterOnOneServer() {
        val bookStore = BookStore.instance
        val codeStore = CodeStore.instance
        val caps: List<McpToolCapability> = listOf(
            BookMcpCapability(bookStore, BookSearch(bookStore)),
            CodeMcpCapability(codeStore, CodeSearch(codeStore)),
        )
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
            ),
            instructions = "test",
        )
        caps.forEach { it.registerTools(server) } // disjoint tool names -> no collision, no throw
        assertTrue(caps.first { it.id == "code" }.readiness().ready, "code capability should be ready")
    }
}
