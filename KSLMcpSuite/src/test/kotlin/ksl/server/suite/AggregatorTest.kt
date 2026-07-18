package ksl.server.suite

import ksl.book.mcp.BookSearch
import ksl.book.mcp.BookStore
import ksl.code.mcp.CodeSearch
import ksl.code.mcp.CodeStore
import ksl.server.mcp.KslMcpTools
import ksl.service.capability.run.BundleRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class AggregatorTest {

    /**
     * The core Phase-3 guarantee: all three tool surfaces register onto ONE MCP server. This
     * succeeds only if (a) there are no tool-name collisions across the simulation, code, and
     * textbook surfaces, and (b) the aggregated capabilities enable prompts — the simulation
     * surface registers guided prompts, and the SDK's addPrompt asserts the capability is present,
     * so a missing prompts capability would throw here.
     */
    @Test
    @DisplayName("aggregates the simulation, code, and textbook surfaces on one MCP server")
    fun buildsOneServerWithAllThreeSurfaces() {
        val registry = BundleRegistry.empty()
        val kslTools = KslMcpTools(registry)
        try {
            val server = KslSuiteMcpServer.buildAggregatedServer(
                kslTools = kslTools,
                bookStore = BookStore.instance,
                bookSearch = BookSearch(BookStore.instance),
                codeStore = CodeStore.instance,
                codeSearch = CodeSearch(CodeStore.instance),
            )
            assertNotNull(server)
        } finally {
            kslTools.close()
        }
    }
}
