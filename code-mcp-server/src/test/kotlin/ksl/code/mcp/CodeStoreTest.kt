package ksl.code.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Integration tests against the real index generated from ../KSLCore + ../KSLExamples. */
class CodeStoreTest {

    private val store = CodeStore.instance

    @Test
    fun `index loads with the expected shape`() {
        assertEquals(store.meta.declarationCount, store.decls.size, "meta count must match loaded decls")
        assertTrue(store.decls.size > 1000, "suspiciously few declarations: ${store.decls.size}")
        assertEquals(listOf("KSLCore", "KSLExamples"), store.modules.map { it.name })
        assertTrue(store.byId.size == store.decls.size, "ids must be unique")
    }

    @Test
    fun `resolve finds core declarations by fqn, simple name, and id`() {
        val byFqn = store.resolve("ksl.modeling.entity.Resource")
        assertEquals(1, byFqn.size)
        assertEquals("class", byFqn.first().kind)

        assertTrue(store.resolve("Resource").isNotEmpty(), "simple-name lookup should work")
        val id = byFqn.first().id
        assertEquals("ksl.modeling.entity.Resource", store.resolve(id).first().fqn)
    }

    @Test
    fun `subtypes are found by supertype simple name`() {
        val subs = store.subtypesOf("ModelElement")
        assertTrue(subs.size > 50, "expected many ModelElement subtypes, got ${subs.size}")
        // ProcessModel extends ModelElement (transitively via Resource etc.); direct match here
        assertTrue(subs.any { it.fqn == "ksl.modeling.entity.ProcessModel" } ||
            subs.any { it.name == "SchedulingElement" }, "a known ModelElement subtype should appear")
    }

    @Test
    fun `package listing returns a populated area of the library`() {
        val pkg = store.declsInPackage("ksl.modeling.entity")
        assertTrue(pkg.size > 20, "expected many decls in ksl.modeling.entity, got ${pkg.size}")
        assertTrue(pkg.any { it.name == "Resource" })
        assertTrue(store.packages.contains("ksl.simulation"))
    }

    @Test
    fun `core declarations carry signature and source url`() {
        val me = store.resolve("ksl.simulation.ModelElement").first()
        assertTrue(me.signature.contains("class ModelElement"), me.signature)
        assertTrue(me.sourceUrl.startsWith("https://github.com/rossetti/KSL/blob/develop/"), me.sourceUrl)
        assertTrue(me.members.isNotEmpty(), "ModelElement should expose members")
    }
}
