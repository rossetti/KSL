package ksl.code.search.gen

import org.junit.jupiter.api.DisplayName
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the Kotlin-PSI extractor on controlled snippets — the component
 * most at risk of mishandling a declaration form. Confirms kinds, reconstructed
 * signatures, KDoc, supertypes, member visibility, and nested types.
 */
class KotlinDeclarationParserTest {

    private val parser = KotlinDeclarationParser()

    @AfterTest fun tearDown() = parser.close()

    private fun parse(src: String) =
        parser.parse(src.trimIndent(), "KSLCore", "KSLCore/src/main/kotlin/pkg/Test.kt")
            .associateBy { it.name }

    @Test
    fun `class with kdoc constructor and supertypes`() {
        val d = parse(
            """
            package ksl.demo

            /**
             * A demo resource.
             * @param cap the capacity
             */
            open class Widget @JvmOverloads constructor(
                parent: ModelElement,
                cap: Int = 1,
            ) : ProcessModel(parent), WidgetIfc {
                fun spin(): Int = 1
                protected val gears: Int = 3
                private fun secret() {}
            }
            """
        )["Widget"]!!
        assertEquals("class", d.kind)
        assertEquals("ksl.demo.Widget", d.fqn)
        assertEquals("ksl.demo", d.pkg)
        assertTrue(d.signature.startsWith("open class Widget @JvmOverloads constructor("), d.signature)
        assertTrue(d.signature.contains("cap: Int = 1"), d.signature)
        assertTrue(d.signature.endsWith(": ProcessModel(parent), WidgetIfc"), d.signature)
        assertEquals(listOf("ProcessModel", "WidgetIfc"), d.supertypes)
        assertTrue(d.kdoc!!.startsWith("A demo resource."), d.kdoc!!)
        assertTrue(d.kdoc!!.contains("@param cap the capacity"))
        // public + protected members are the subclass API; private is excluded
        assertTrue(d.members.any { it.startsWith("fun spin(): Int") }, d.members.toString())
        assertTrue(d.members.any { it.contains("protected val gears") }, d.members.toString())
        assertTrue(d.members.none { it.contains("secret") }, d.members.toString())
    }

    /**
     * The source this parser is handed carries whatever line endings the checkout produced:
     * LF on macOS/Linux, CRLF on Windows, where Git defaults core.autocrlf=true. Line endings
     * must not change what is extracted. Unnormalized CRLF silently emptied the package of
     * 61% of the shipped index and truncated annotated classes at their constructor header.
     */
    @Test
    @DisplayName("CRLF source extracts identically to LF")
    fun crlfSourceExtractsIdenticallyToLf() {
        val src = """
            package ksl.demo

            /** A demo resource. */
            open class Widget @JvmOverloads constructor(
                parent: ModelElement,
                cap: Int = 1
            ) : ProcessModel(parent), WidgetIfc {
                fun spin(): Int = 1
                class Nested
            }
        """.trimIndent()
        val relPath = "KSLCore/src/main/kotlin/pkg/Test.kt"
        val lf = parser.parse(src, "KSLCore", relPath)
        val crlf = parser.parse(src.replace("\n", "\r\n"), "KSLCore", relPath)

        val widget = crlf.single { it.name == "Widget" }
        assertEquals("ksl.demo.Widget", widget.fqn)
        assertEquals("ksl.demo", widget.pkg)
        assertEquals(listOf("ProcessModel", "WidgetIfc"), widget.supertypes)
        assertTrue(widget.members.any { it.startsWith("fun spin()") }, widget.members.toString())
        assertTrue(widget.signature.endsWith(": ProcessModel(parent), WidgetIfc"), widget.signature)
        // a truncated parse stops at the constructor header, so the nested type disappears
        assertTrue(crlf.any { it.name == "Nested" }, crlf.map { it.name }.toString())
        // nothing at all may differ — line numbers and signatures included
        assertEquals(lf, crlf)
    }

    @Test
    fun `declaration kinds are distinguished`() {
        val d = parse(
            """
            package p
            interface Ifc
            data class DataThing(val a: Int)
            sealed class Shape
            enum class Color { RED, GREEN }
            annotation class Marker
            abstract class Base
            object Singleton
            typealias Alias = Map<String, Int>
            fun topLevel(x: Int): Int = x
            fun Int.doubled(): Int = this * 2
            class Outer { companion object { fun make() = Outer() } }
            """
        )
        assertEquals("interface", d["Ifc"]!!.kind)
        assertEquals("data class", d["DataThing"]!!.kind)
        assertEquals("sealed class", d["Shape"]!!.kind)
        assertEquals("enum class", d["Color"]!!.kind)
        assertEquals("annotation class", d["Marker"]!!.kind)
        assertEquals("abstract class", d["Base"]!!.kind)
        assertEquals("object", d["Singleton"]!!.kind)
        assertEquals("type alias", d["Alias"]!!.kind)
        assertEquals("fun", d["topLevel"]!!.kind)
        assertEquals("extension_fun", d["doubled"]!!.kind)
        // nested companion object is emitted as its own chunk
        assertEquals("companion object", d["Companion"]!!.kind)
    }

    @Test
    fun `private and internal top-level declarations are skipped`() {
        val d = parse(
            """
            package p
            private class Hidden
            internal class AlsoHidden
            class Visible
            """
        )
        assertTrue("Visible" in d)
        assertNull(d["Hidden"])
        assertNull(d["AlsoHidden"])
    }

    @Test
    fun `expression body signature drops the body`() {
        val d = parse(
            """
            package p
            fun area(r: Double): Double = 3.14 * r * r
            """
        )["area"]!!
        assertEquals("fun area(r: Double): Double", d.signature)
    }

    @Test
    fun `undocumented declaration has null kdoc`() {
        val d = parse(
            """
            package p
            class Plain
            """
        )["Plain"]!!
        assertNull(d.kdoc)
    }

    @Test
    fun `generic signature with bounds is preserved`() {
        val d = parse(
            """
            package p
            class Box<T : Comparable<T>>(val value: T)
            """
        )["Box"]!!
        assertTrue(d.signature.contains("Box<T : Comparable<T>>"), d.signature)
        assertTrue(d.signature.contains("value: T"), d.signature)
    }
}
