package ksl.book.mcp

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll

class ToolHandlersTest {

    private val store = BookStore.instance
    private val handlers = ToolHandlers(store, BookSearch(store))

    companion object {
        // D3 graceful degradation: skip when the book was not rendered into _book/
        // at build time (empty bundled content); the parser port re-pins assertions.
        @JvmStatic
        @BeforeAll
        fun requireBookContent() =
            assumeTrue(BookStore.instance.chunks.isNotEmpty(), "book content not generated (_book/ absent)")
    }

    @Test
    fun `search returns ranked list with urls and follow-up hint`() {
        val out = handlers.searchTextbook("modeling a simple queueing system", 5)
        assertTrue(out.contains("1. 4.4.4 Modeling a Simple Queueing System"), out.take(300))
        assertTrue(out.contains("https://rossetti.github.io/KSLBook/introDEDSdedsKSL.html#introDEDSPharmacy"))
        assertTrue(out.contains("Use get_section"))
    }

    @Test
    fun `get_section by number returns content and navigation`() {
        val out = handlers.getSection("4.4.4")
        assertTrue(out.startsWith("Section: 4.4.4 Modeling a Simple Queueing System"))
        assertTrue(out.contains("Chapter: 4 — Introduction to Discrete Event Modeling"))
        assertTrue(out.contains("```kotlin"))
        assertTrue(out.contains("Previous: 4.4.3"))
        assertTrue(out.contains("Next: 4.4.5"))
    }

    @Test
    fun `get_section by id matches by-number result`() {
        assertTrue(handlers.getSection("introDEDSPharmacy").startsWith("Section: 4.4.4"))
    }

    @Test
    fun `get_section of a parent lists or inlines subsections`() {
        val out = handlers.getSection("4.4")
        assertTrue(out.contains("4.4.4") && out.contains("Modeling a Simple Queueing System"), out.take(2000))
    }

    @Test
    fun `get_section unknown input is a helpful error`() {
        val e = assertFailsWith<ToolInputException> { handlers.getSection("99.7") }
        assertTrue(e.message!!.contains("list_chapters"))
    }

    @Test
    fun `chapter outline shows nested sections with flags`() {
        val out = handlers.getChapterOutline("4")
        assertTrue(out.startsWith("Chapter 4 —"))
        assertTrue(out.contains("- 4.4.4 Modeling a Simple Queueing System (id: introDEDSPharmacy) [code]"), out)
    }

    @Test
    fun `chapter outline validates chapter`() {
        val e = assertFailsWith<ToolInputException> { handlers.getChapterOutline("42") }
        assertTrue(e.message!!.contains("A"), e.message)
    }

    @Test
    fun `list_chapters covers chapters and appendices`() {
        val out = handlers.listChapters()
        assertTrue(out.contains("- Chapter 4:"))
        assertTrue(out.contains("- Appendix A:"))
        assertTrue(out.contains("exercises)"))
    }

    @Test
    fun `get_exercises returns whole chapter or single exercise`() {
        val all = handlers.getExercises("4", null)
        assertTrue(all.contains("**Exercise 4.1**"))
        val one = handlers.getExercises("4", "4.1")
        assertTrue(one.contains("**Exercise 4.1**") && !one.contains("**Exercise 4.2**"))
        val short = handlers.getExercises("4", "1")
        assertTrue(short.contains("**Exercise 4.1**"))
        assertTrue(one.contains("https://rossetti.github.io/KSLBook/exercises-3.html#exr:ch4P1"))
    }

    @Test
    fun `get_exercises validates chapter and exercise`() {
        assertFailsWith<ToolInputException> { handlers.getExercises("Z", null) }
        val e = assertFailsWith<ToolInputException> { handlers.getExercises("4", "4.999") }
        assertTrue(e.message!!.contains("4.1"), e.message)
    }

    @Test
    fun `related sections lists other parts of the book`() {
        val out = handlers.getRelatedSections("4.4.4")
        assertTrue(out.startsWith("Sections related to 4.4.4"))
        assertTrue(out.contains("https://rossetti.github.io/KSLBook/"))
    }
}
