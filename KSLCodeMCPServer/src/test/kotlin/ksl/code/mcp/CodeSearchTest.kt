package ksl.code.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeSearchTest {

    private val store = CodeStore.instance
    private val search = CodeSearch(store)

    @Test
    fun `core classes are findable by name`() {
        listOf("ProcessModel", "ModelElement", "KSLEvent", "Resource").forEach { name ->
            val hits = search.search(name, 5)
            assertTrue(hits.isNotEmpty(), "no results for core class: $name")
            assertTrue(hits.any { it.decl.name == name }, "expected $name among hits")
        }
    }

    @Test
    fun `concept queries return results`() {
        listOf(
            "seize release a resource",
            "waiting line queue discipline",
            "schedule an event",
            "generate exponential random variate",
        ).forEach { q ->
            assertTrue(search.search(q, 5).isNotEmpty(), "no results for concept query: '$q'")
        }
    }

    @Test
    fun `module filter restricts results`() {
        val hits = search.search("model", 10, module = "KSLExamples")
        assertTrue(hits.isNotEmpty(), "no KSLExamples results for 'model'")
        assertTrue(hits.all { it.decl.module == "KSLExamples" }, "module filter leaked non-KSLExamples hits")
    }

    @Test
    fun `total documents matches the store`() {
        assertEquals(store.decls.size, search.totalDocuments())
    }

    @Test
    fun `identifier splitting bridges prose and camel case`() {
        val split = splitIdentifier("ProcessModel")
        assertTrue(split.contains("Process") && split.contains("Model"), split)
        assertTrue(split.contains("ProcessModel"), "original token retained: $split")
    }
}
