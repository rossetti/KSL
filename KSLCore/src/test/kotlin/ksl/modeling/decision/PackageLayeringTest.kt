package ksl.modeling.decision

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  Guards the one-way package layering of Appendix E.1:
 *
 *      ksl.modeling.decision.descriptor  ->  ksl.modeling.decision  ->  ksl.modeling.decision.capture
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

    /**
     *  Every reference in a package's own source, not merely its imports.
     *
     *  The import-only version of this check could not see the violation that actually happened.
     *  `DecisionElement.captureTo` constructed its sink by fully-qualified name, inline — back when the
     *  sinks were `ksl.sdm.capture` and `RollingSink` was among them. That is a real compile-time
     *  dependency in the forbidden direction and it appears in no `import` line, so it passed a guard
     *  whose own KDoc said it existed because "a cycle here builds cleanly and is invisible in
     *  review". It was invisible to the guard too, for a year, which is the lesson: a rule about
     *  *dependencies* must be checked against *references*. (The class named there has since moved to
     *  sit with the contract, so that exact line could not be written today — but any inline
     *  `ksl.modeling.decision.capture.MemorySink()` would be the same fault.)
     */
    private fun referencesIn(packagePath: String, prefix: String): List<String> {
        val dir = File(sourceRoot(), packagePath)
        assertTrue(dir.isDirectory, "expected package directory $packagePath")
        val hits = mutableListOf<String>()
        // The capture package is a subdirectory of the one being policed, so the walk would
        // otherwise flag its own `package` line. It is the thing on the far side of the rule.
        for (f in dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "capture" }) {
            for ((n, line) in f.readLines().withIndex()) {
                val code = line.substringBefore("//")
                val t = code.trim()
                // KDoc and block comments name other packages constantly and legitimately.
                if (t.startsWith("*") || t.startsWith("/*")) continue
                if (code.contains(prefix)) hits += "${f.name}:${n + 1}  ${t.take(90)}"
            }
        }
        return hits
    }

    @Test
    fun `the decision package does not depend on the capture implementations`() {
        val offenders = referencesIn("ksl/modeling/decision", "ksl.modeling.decision.capture")
        assertTrue(offenders.isEmpty(),
            "The capture CONTRACT lives in ksl.modeling.decision and only its implementations live " +
                "in ksl.modeling.decision.capture (E.1). A reference the other way -- imported OR " +
                "fully qualified -- makes the two packages mutually dependent, which is the cycle " +
                "this rule exists to prevent. Found: " + offenders.joinToString("; "))
    }
}
