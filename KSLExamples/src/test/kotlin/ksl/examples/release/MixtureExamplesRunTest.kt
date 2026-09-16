package ksl.examples.release

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Runs the mixture tutorial examples, and asserts only that they complete without throwing.
 *
 * A sibling of `ExposedExamplesRunTest` rather than an addition to it. That class runs the
 * examples a particular release's changes could reach, a list derived from the packages that
 * release touched; these examples are not that, and adding them would quietly turn "reachable by
 * this release" into "whatever we happened to add", which is the drift its exclusion map exists to
 * prevent. Separate lists keep each one's reason for existing legible, the way
 * `SlowExamplesRunTest` split off on cost.
 *
 * These have a second reason to be run that a demo does not. The tutorial guide quotes numbers
 * computed by them, and they read their data from files shipped in `chapterFiles`. A path that
 * stops resolving, or a dataset that goes missing, is invisible at compile time and would surface
 * as a fit of nothing.
 *
 * What this does NOT establish: it is a "did it throw" check, not a numerical one. That the
 * shipped data is the sample it claims to be is checked separately, value for value, by
 * `ReceptionDeskDataTest`.
 */
class MixtureExamplesRunTest {

    private companion object {

        /**
         * Six times the slowest measured run, which is Example 8 at 30 s; the other four are
         * about a second each. A timeout is here to catch a hang, not to police drift on a loaded
         * machine, so the headroom is deliberate.
         */
        val PER_EXAMPLE_TIMEOUT: Duration = Duration.ofSeconds(180)

        const val PACKAGE: String = "ksl.examples.general.utilities.fitting.mixture"

        /** The examples that can run unattended. */
        val RUNNABLE: List<String> = listOf(
            "$PACKAGE.Example2FittingAMixtureKt",
            "$PACKAGE.Example3ChoosingKKt",
            "$PACKAGE.Example4RefinementKt",
            "$PACKAGE.Example5AgainstTheTruthKt",
            "$PACKAGE.Example8HowStableIsItKt"
        )

        /**
         * The examples deliberately left out, each with its reason. Never silently dropped: an
         * example missing from both lists is an example nobody is watching, and the only way to
         * tell that from a deliberate omission is to write the omission down.
         *
         * Every exclusion here is categorical -- the example cannot run unattended at all. None is
         * excluded for being slow. Example 8 looked like the expensive one and was left out on that
         * assumption until it was timed at 30 s, which is not a reason to stop watching it.
         */
        val EXCLUDED: Map<String, String> = mapOf(
            "$PACKAGE.Example1TheProblemKt" to
                "opens a browser twice (showInBrowser) to show the histogram and the fit plot; " +
                "the point of the example is looking at them",
            "$PACKAGE.Example6YourOwnDataKt" to
                "blocks on KSLFileUtil.chooseFile(), so it cannot run unattended by design -- it " +
                "exists to be pointed at data of the reader's own",
            "$PACKAGE.Example7DiagnosticsKt" to
                "opens a browser (showAllResultsInBrowser) to show the full diagnostic report"
        )
    }

    /**
     * Invokes a Kotlin file-level `main`. A no-arg `fun main()` compiles to a synthetic
     * `main(String[])` bridge on the file's facade class, so the array-taking method is the one
     * to look for and an empty array is the argument.
     */
    private fun runExample(className: String) {
        val facade = Class.forName(className)
        val main = facade.getMethod("main", Array<String>::class.java)
        main.invoke(null, emptyArray<String>())
    }

    /**
     * Every example in the package is on one of the two lists.
     *
     * The "never silently dropped" rule is only worth as much as something that enforces it. An
     * example added later and put on neither list is an example nobody runs and nobody decided not
     * to run, and the difference between those two is invisible in a passing build. This is not
     * gated on the opt-in flag: it costs a directory listing and it guards the case where the flag
     * is never set.
     */
    @Test
    @DisplayName("Every mixture example is either run or excluded for a stated reason")
    fun everyExampleIsAccountedFor() {
        val dir = listOf(
            Path.of("src/main/kotlin", PACKAGE.replace('.', '/')),
            Path.of("KSLExamples/src/main/kotlin", PACKAGE.replace('.', '/'))
        ).firstOrNull { Files.isDirectory(it) }
            ?: fail("cannot locate the mixture examples from ${Path.of(".").toAbsolutePath()}")

        val onDisk = Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.startsWith("Example") && it.endsWith(".kt") }
                .map { "$PACKAGE.${it.removeSuffix(".kt")}Kt" }
                .sorted()
                .toList()
        }
        val accounted = (RUNNABLE + EXCLUDED.keys).sorted()
        val unaccounted = onDisk - accounted.toSet()
        val stale = accounted - onDisk.toSet()

        assertTrue(unaccounted.isEmpty()) {
            "these examples are neither run nor excluded, so nobody is watching them: " +
                unaccounted.joinToString { it.substringAfterLast('.') }
        }
        assertTrue(stale.isEmpty()) {
            "these are listed but no longer exist: " + stale.joinToString { it.substringAfterLast('.') }
        }
    }

    @TestFactory
    @DisplayName("The mixture tutorial examples complete without throwing")
    fun mixtureExamplesRun(): List<DynamicTest> {
        // The gate sits here rather than in a @BeforeEach so that it covers the runs and not the
        // accounting check above, which must hold whether or not anyone asked for the runs.
        assumeTrue(System.getProperty("ksl.runExamples") == "true") {
            "set -Dksl.runExamples=true to run the examples"
        }
        println("Running ${RUNNABLE.size} mixture examples; ${EXCLUDED.size} excluded:")
        for ((name, reason) in EXCLUDED) {
            println("   EXCLUDED ${name.substringAfterLast('.')}: $reason")
        }
        return RUNNABLE.map { className ->
            DynamicTest.dynamicTest(className.substringAfterLast('.')) {
                val startedAt = System.currentTimeMillis()
                assertTimeoutPreemptively(PER_EXAMPLE_TIMEOUT) { runExample(className) }
                println(
                    "   ok ${className.substringAfterLast('.')} " +
                        "(${(System.currentTimeMillis() - startedAt) / 1000}s)"
                )
            }
        }
    }
}
