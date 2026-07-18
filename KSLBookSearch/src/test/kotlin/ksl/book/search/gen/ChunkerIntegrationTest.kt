package ksl.book.search.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs the real parsers against the rendered Quarto book in ../_book. Guards the
 * structural assumptions the chunker is built on; fails loudly if a book rebuild
 * changes the markup. Skips when _book/ has not been rendered locally.
 */
class ChunkerIntegrationTest {

    private val docsDir = File("../_book")

    private fun requireBook() =
        assumeTrue(File(docsDir, "index.html").isFile, "book not rendered into _book/")

    private fun toc() = TocParser.parse(File(docsDir, "index.html"))

    @Test
    fun tocHasExpectedShape() {
        requireBook()
        val toc = toc()
        assertEquals(20, toc.size, toc.map { it.path }.toString())
        assertTrue(toc.any { it.level == null && it.path == "index.html" })
        assertTrue(toc.any { it.level == null && it.path == "references.html" })
        assertTrue(toc.any { it.level == "4" })
        assertTrue(toc.any { it.level == "A" })
    }

    @Test
    fun chapterFourChunksMatchTheBook() {
        requireBook()
        val ch4 = toc().first { it.path == "04-Chapter4.html" }
        val chunker = PageChunker(docsDir, BASE_URL, HtmlToMarkdown(BASE_URL))
        val (chunks, exercises) = chunker.process(ch4)

        val intro = chunks.first()
        assertEquals("sec-introDEDS", intro.id)
        assertEquals("4", intro.number)
        assertEquals(1, intro.level)

        val s41 = chunks.find { it.number == "4.1" }
        assertNotNull(s41)
        assertEquals("sec-introDEDSdeds", s41.id)
        assertEquals(2, s41.level)

        assertNotNull(chunks.find { it.number == "4.4" && it.id == "sec-introDEDSdedsKSL" })
        assertTrue(chunks.any { it.hasCode && it.content.contains("```kotlin") })

        assertTrue(exercises.isNotEmpty(), "no exercises extracted from chapter 4")
        val firstEx = exercises.first()
        assertEquals("exr-ch4P1", firstEx.id)
        assertEquals("4.1", firstEx.number)
        assertEquals("4", firstEx.chapter)
        assertTrue(firstEx.content.contains("**Exercise 4.1**"), firstEx.content.take(80))
    }

    @Test
    fun appendixNumberingIsLettered() {
        requireBook()
        val appA = toc().first { it.path == "12-AppRNRV.html" }
        val chunker = PageChunker(docsDir, BASE_URL, HtmlToMarkdown(BASE_URL))
        val (chunks, _) = chunker.process(appA)
        assertEquals("sec-appRNRV", chunks.first().id)
        assertEquals("A", chunks.first().number)
        assertTrue(chunks.all { it.number == null || it.number!!.startsWith("A") },
            chunks.map { it.number }.toString())
    }
}
