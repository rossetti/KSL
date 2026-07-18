package ksl.server.suite

import ksl.book.search.BookSearch
import ksl.book.search.BookStore
import ksl.code.search.CodeSearch
import ksl.code.search.CodeStore
import ksl.server.mcp.KslMcpTools
import ksl.service.capability.run.BundleRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class AggregatorTest {

    /**
     * The core aggregation guarantee: all three tool surfaces register onto ONE MCP server via the
     * capability contract. This succeeds only if (a) there are no tool-name collisions across the
     * simulation, code, and textbook surfaces, and (b) the aggregated capabilities enable prompts —
     * the simulation surface registers guided prompts, and the SDK's addPrompt asserts the
     * capability is present, so a missing prompts capability would throw here.
     */
    @Test
    @DisplayName("aggregates the simulation, code, and textbook surfaces on one MCP server")
    fun buildsOneServerWithAllThreeSurfaces() {
        val registry = BundleRegistry.empty()
        val kslTools = KslMcpTools(registry)
        try {
            val bookStore = BookStore.instance
            val codeStore = CodeStore.instance
            val capabilities: List<McpToolCapability> = listOf(
                SimMcpCapability(kslTools, registry),
                BookMcpCapability(bookStore, BookSearch(bookStore)),
                CodeMcpCapability(codeStore, CodeSearch(codeStore)),
            )
            val server = KslSuiteMcpServer.buildAggregatedServer(capabilities)
            assertNotNull(server)
        } finally {
            kslTools.close()
        }
    }
}
