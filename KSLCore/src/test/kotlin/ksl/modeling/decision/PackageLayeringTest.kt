package ksl.modeling.decision

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  Guards the one-way package layering of Appendix E.1:
 *
 *      ksl.modeling.decision.descriptor  ->  ksl.modeling.decision  ->  ksl.sdm.capture
 *
 *  Kotlin permits circular package references inside a module, so the compiler will not
 *  enforce this. A cycle here builds cleanly and is invisible in review (D.19), which is
 *  why it is asserted rather than documented.
 */
class PackageLayeringTest {

    private fun sourceRoot(): File =
        listOf("src/main/kotlin", "KSLCore/src/main/kotlin")
            .map(::File).firstOrNull { it.isDirectory }
            ?: fail("cannot locate the Kotlin source root from ${File(".").absolutePath}")

    private fun importsIn(packagePath: String): List<Pair<File, String>> {
        val dir = File(sourceRoot(), packagePath)
        assertTrue(dir.isDirectory, "expected package directory $packagePath")
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { it.startsWith("import ") }.map { f to it.trim() } }
            .toList()
    }

    @Test
    fun `the descriptor package depends on nothing in the simulation`() {
        val offenders = importsIn("ksl/modeling/decision/descriptor")
            .filter { (_, imp) -> imp.startsWith("import ksl.simulation") || imp.startsWith("import ksl.modeling.") }
            .filterNot { (_, imp) -> imp.startsWith("import ksl.modeling.decision.descriptor") }
        assertTrue(offenders.isEmpty(),
            "The descriptor package must be plain serializable data with no model references, " +
                "so that a description can be validated without building a model. Found: " +
                offenders.joinToString { "${it.first.name}: ${it.second}" })
    }

    @Test
    fun `the decision package does not depend on the capture implementations`() {
        val offenders = importsIn("ksl/modeling/decision")
            .filter { (_, imp) -> imp.startsWith("import ksl.sdm") }
        assertTrue(offenders.isEmpty(),
            "The capture CONTRACT lives in ksl.modeling.decision and only its implementations " +
                "live in ksl.sdm.capture (E.1). An import the other way makes the two packages " +
                "mutually dependent. Found: " +
                offenders.joinToString { "${it.first.name}: ${it.second}" })
    }
}
