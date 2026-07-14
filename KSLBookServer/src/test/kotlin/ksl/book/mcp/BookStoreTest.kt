package ksl.book.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll

class BookStoreTest {

    private val store = BookStore.instance

    companion object {
        // D3 graceful degradation: skip when the book was not rendered into _book/
        // at build time (empty bundled content); the parser port re-pins assertions.
        @JvmStatic
        @BeforeAll
        fun requireBookContent() =
            assumeTrue(BookStore.instance.chunks.isNotEmpty(), "book content not generated (_book/ absent)")
    }

    @Test
    fun `loads bundled content`() {
        assertTrue(store.chunks.size > 250, "chunks: ${store.chunks.size}")
        assertTrue(store.exercises.size > 200, "exercises: ${store.exercises.size}")
    }

    @Test
    fun `finds sections by number and by id`() {
        val byNumber = store.find("4.4.4")
        val byId = store.find("introDEDSPharmacy")
        assertNotNull(byNumber)
        assertEquals(byNumber, byId)
        assertEquals("Modeling a Simple Queueing System", byNumber.title)
        assertNull(store.find("99.99"))
    }

    @Test
    fun `chapters are in book order with letters after numbers`() {
        val numbers = store.chapters.map { it.number }
        assertEquals(listOf("1","2","3","4","5","6","7","8","9","10","A","B","C","D","E","F","G"), numbers)
        assertTrue(store.chapters.all { it.sectionCount > 0 })
    }

    @Test
    fun `children of a level2 section are its subsections`() {
        val parent = store.find("4.4")!!
        val kids = store.childrenOf(parent)
        assertTrue(kids.any { it.number == "4.4.4" }, kids.map { it.number }.toString())
        assertTrue(kids.all { it.parentId == parent.id })
    }

    @Test
    fun `exercises filter by chapter including appendices`() {
        val ch4 = store.exercisesFor("4")
        assertTrue(ch4.isNotEmpty())
        assertTrue(ch4.all { it.number.startsWith("4.") })
        val appA = store.exercisesFor("A")
        assertTrue(appA.isNotEmpty())
        assertTrue(appA.all { it.number.startsWith("A.") })
    }
}
