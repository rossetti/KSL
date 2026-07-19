package ksl.book.search

import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll

class BookSearchTest {

    private val store = BookStore.instance
    private val search = BookSearch(store)

    companion object {
        // D3 graceful degradation: skip when the book was not rendered into _book/
        // at build time (empty bundled content).
        @JvmStatic
        @BeforeAll
        fun requireBookContent() =
            assumeTrue(BookStore.instance.chunks.isNotEmpty(), "book content not generated (_book/ absent)")
    }

    @Test
    fun `index builds fast enough for a lazy first call`() {
        val start = System.currentTimeMillis()
        search.search("warm up", 1)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 3000, "index build + first query took $elapsed ms")
    }

    @Test
    fun `title match ranks the pharmacy section first`() {
        val ids = search.search("modeling a simple queueing system", 5).map { it.chunk.id }
        assertTrue(ids.first() == "sec-introDEDSPharmacy", ids.toString())
    }

    @Test
    fun `pharmacy scheduling query surfaces the event scheduling sections`() {
        val hits = search.search("pharmacy model event scheduling", 5)
        assertTrue(hits.any { it.chunk.number?.startsWith("4.4") == true },
            hits.map { "${it.chunk.number} ${it.chunk.title}" }.toString())
    }

    @Test
    fun `stemming matches queueing against queue`() {
        val hits = search.search("queueing formulas waiting time", 8)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.any { it.chunk.chapter == "C" || it.chunk.title.contains("queue", true) },
            hits.map { "${it.chunk.number} ${it.chunk.title}" }.toString())
    }

    @Test
    fun `random variate query lands in chapter 2 or appendix A`() {
        val hits = search.search("generating random variates inverse transform", 8)
        assertTrue(hits.any { it.chunk.chapter == "2" || it.chunk.chapter == "A" },
            hits.map { "${it.chunk.number} ${it.chunk.title}" }.toString())
    }

    @Test
    fun `topics sidecar is bundled and applied`() {
        val withTopics = store.chunks.count { it.topics.isNotEmpty() }
        assertTrue(withTopics * 100 / store.chunks.size >= 80,
            "topic coverage: $withTopics/${store.chunks.size}")
        val pharmacy = store.find("sec-introDEDSPharmacy")!!
        assertTrue(pharmacy.topics.any { "pharmacy" in it }, pharmacy.topics.toString())
    }

    @Test
    fun `topic keywords bridge vocabulary gaps`() {
        // "warm up period" appears in topics, not in the section titles
        val warmup = search.search("warm up period", 5)
        assertTrue(warmup.any { it.chunk.chapter == "5" },
            warmup.map { "${it.chunk.number} ${it.chunk.title}" }.toString())
        // "mm1 queue example" reaches the pharmacy model via its topic keywords
        val mm1 = search.search("mm1 queue example", 5).map { it.chunk.id }
        assertTrue("sec-introDEDSPharmacy" in mm1, mm1.toString())
    }

    @Test
    fun `question words do not dominate ranking`() {
        val ids = search.search("What is the DEGREE methodology?", 3).map { it.chunk.id }
        assertTrue(ids.first() == "sec-ch1secsimMeth", ids.toString())
    }

    @Test
    fun `special characters do not crash the parser`() {
        val hits = search.search("what is rho? (utilization) AND", 5)
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun `snippets are short windows not full content`() {
        val hits = search.search("pharmacy model", 5)
        assertTrue(hits.all { it.snippet.length < 600 })
        assertTrue(hits.all { it.snippet.isNotBlank() })
    }

    @Test
    fun `related sections exclude self and siblings`() {
        val chunk = store.find("4.4.4")!!
        val related = search.related(chunk)
        assertTrue(related.isNotEmpty())
        val ids = related.map { it.chunk.id }
        assertTrue(chunk.id !in ids)
        val siblings = store.chunks.filter { it.parentId == chunk.parentId }.map { it.id }
        assertTrue(ids.none { it in siblings }, ids.toString())
    }
}
