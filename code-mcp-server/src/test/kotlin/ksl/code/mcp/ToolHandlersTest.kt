package ksl.code.mcp

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolHandlersTest {

    private val store = CodeStore.instance
    private val handlers = ToolHandlers(store, CodeSearch(store))

    @Test
    fun `search_code returns ranked declarations with source urls`() {
        val out = handlers.searchCode("seize release a resource", 5, null)
        assertTrue(out.startsWith("Results for"), out.take(120))
        assertTrue(out.contains("https://github.com/rossetti/KSL/blob/develop/"), out.take(400))
        assertTrue(out.contains("get_class"), out)
    }

    @Test
    fun `search_code module filter is validated and applied`() {
        val out = handlers.searchCode("model", 5, "KSLExamples")
        assertTrue(out.contains("(module KSLExamples)"), out.take(120))
        assertFailsWith<ToolInputException> { handlers.searchCode("model", 5, "NoSuchModule") }
    }

    @Test
    fun `get_class shows full api by fqn and by simple name`() {
        val out = handlers.getClass("ksl.modeling.entity.Resource")
        assertTrue(out.contains("class ksl.modeling.entity.Resource"), out.take(200))
        assertTrue(out.contains("Signature:"))
        assertTrue(out.contains("Supertypes: ProcessModel, ResourceCIfc"), out.take(400))
        assertTrue(out.contains("API surface"))
        // simple-name resolution reaches the same class
        assertTrue(handlers.getClass("Resource").contains("Resource"))
    }

    @Test
    fun `get_class unknown declaration is a helpful error`() {
        val e = assertFailsWith<ToolInputException> { handlers.getClass("ksl.does.not.Exist") }
        assertTrue(e.message!!.contains("search_code"), e.message)
    }

    @Test
    fun `get_example lists example files for a used declaration`() {
        val out = handlers.getExample("Resource")
        assertTrue(out.contains("Examples using"), out.take(120))
        assertTrue(out.contains("KSLExamples/src/main/kotlin/"), out.take(400))
    }

    @Test
    fun `get_package_overview groups declarations and validates the package`() {
        val out = handlers.getPackageOverview("ksl.modeling.entity")
        assertTrue(out.startsWith("Package ksl.modeling.entity"), out.take(120))
        assertTrue(out.contains("Resource"))
        assertFailsWith<ToolInputException> { handlers.getPackageOverview("ksl.not.a.package") }
    }

    @Test
    fun `find_subclasses lists implementers`() {
        val out = handlers.findSubclasses("ModelElement")
        assertTrue(out.startsWith("Declarations that extend or implement ModelElement"), out.take(120))
        assertTrue(out.contains("[KSLCore]"))
    }

    @Test
    fun `get_related_examples finds example programs for a topic`() {
        val out = handlers.getRelatedExamples("resource")
        assertTrue(out.contains("related to \"resource\""), out.take(120))
    }

    @Test
    fun `list_modules reports modules and packages`() {
        val out = handlers.listModules()
        assertTrue(out.contains("KSLCore"))
        assertTrue(out.contains("KSLExamples"))
        assertTrue(out.contains("ksl.modeling.entity"))
    }

    @Test
    fun `get_server_info reports the indexed ref`() {
        val out = handlers.getServerInfo()
        assertTrue(out.contains("KSL ref:        develop"), out)
        assertTrue(out.contains("declarations:"))
    }
}
