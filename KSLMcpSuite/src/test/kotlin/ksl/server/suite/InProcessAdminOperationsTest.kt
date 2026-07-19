package ksl.server.suite

import ksl.book.search.BookSearch
import ksl.book.search.BookStore
import ksl.code.search.CodeSearch
import ksl.code.search.CodeStore
import ksl.service.usage.UsageStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InProcessAdminOperationsTest {

    @Test
    @DisplayName("status reports enabled/disabled capabilities and recorded usage")
    fun statusReflectsEnablementAndUsage(@TempDir tmp: Path) {
        val usage = UsageStore(tmp)
        val bookStore = BookStore.instance
        val codeStore = CodeStore.instance
        // book + code enabled; sim disabled (absent from the list).
        val caps = listOf(
            BookMcpCapability(bookStore, BookSearch(bookStore)),
            CodeMcpCapability(codeStore, CodeSearch(codeStore)),
        )
        usage.recorderFor("code").record("search_code", 12, true)
        usage.recorderFor("code").record("get_class", 8, false)

        val status = InProcessAdminOperations("test-1.0", caps, usage).status()

        assertEquals("test-1.0", status.version)
        assertEquals(2, status.served)
        val byId = status.capabilities.associateBy { it.id }
        assertFalse(byId.getValue("sim").enabled, "sim disabled")
        assertTrue(byId.getValue("book").enabled, "book enabled")
        assertTrue(byId.getValue("code").enabled, "code enabled")
        assertEquals(2, byId.getValue("code").callCount)
        assertEquals("disabled", byId.getValue("sim").detail)
        assertEquals(codeStore.meta.declarationCount > 0, byId.getValue("code").ready)
    }

    @Test
    @DisplayName("usageSummary and recentActivity surface recorded calls")
    fun usageAndActivity(@TempDir tmp: Path) {
        val usage = UsageStore(tmp)
        usage.recorderFor("book").record("search_textbook", 30, true)
        val ops = InProcessAdminOperations("v", emptyList(), usage)
        assertEquals(1, ops.usageSummary().total)
        assertEquals("search_textbook", ops.recentActivity(5).first().tool)
    }
}
