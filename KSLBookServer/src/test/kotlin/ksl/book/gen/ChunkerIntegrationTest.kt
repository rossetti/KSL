package ksl.book.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Disabled

/**
 * Runs the real parsers against the rendered book in ../docs. Guards the
 * structural assumptions the chunker is built on; fails loudly if a book
 * rebuild changes the markup.
 */
@Disabled("bookdown-era fixtures/assertions; rewritten for Quarto (_book/) in the parser-port change set")
class ChunkerIntegrationTest {

    private val docsDir = File("../docs")

    @Test
    fun `toc has the expected shape`() {
        val toc = TocParser.parse(File(docsDir, "index.html"))
        assertTrue(toc.size > 200, "TOC entries: ${toc.size}")
        assertTrue(toc.map { it.path }.distinct().size > 100)
        // front matter present, unnumbered
        assertTrue(toc.any { it.level == null && it.path == "index.html" })
        // numbered chapters and lettered appendices
        assertTrue(toc.any { it.level == "4" })
        assertTrue(toc.any { it.level == "A" })
    }

    @Test
    fun `pharmacy page chunks match the book`() {
        val chunker = PageChunker(docsDir, BASE_URL, HtmlToMarkdown(BASE_URL))
        val (chunks, _) = chunker.process("introDEDSdedsKSL.html")
        val own = chunks.first()
        assertEquals("introDEDSdedsKSL", own.id)
        assertEquals("4.4", own.number)
        assertEquals(2, own.level)

        val pharmacy = chunks.find { it.id == "introDEDSPharmacy" }
        assertNotNull(pharmacy)
        assertEquals("4.4.4", pharmacy.number)
        assertEquals("Modeling a Simple Queueing System", pharmacy.title)
        assertTrue(pharmacy.hasCode)
        assertTrue(pharmacy.content.contains("```kotlin"))
    }

    @Test
    fun `appendix numbering is lettered`() {
        val chunker = PageChunker(docsDir, BASE_URL, HtmlToMarkdown(BASE_URL))
        val (chunks, _) = chunker.process("appRNRVs.html")
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.number == null || it.number!!.startsWith("A") },
            chunks.map { it.number }.toString())
    }

    @Test
    fun `chapter 4 exercises are extracted with numbers`() {
        val chunker = PageChunker(docsDir, BASE_URL, HtmlToMarkdown(BASE_URL))
        val (_, exercises) = chunker.process("exercises-3.html")
        assertTrue(exercises.size > 5, "exercises: ${exercises.size}")
        val first = exercises.first()
        assertEquals("exr:ch4P1", first.id)
        assertEquals("4.1", first.number)
        assertEquals("4", first.chapter)
        assertTrue(first.content.startsWith("**Exercise 4.1**"))
    }
}
