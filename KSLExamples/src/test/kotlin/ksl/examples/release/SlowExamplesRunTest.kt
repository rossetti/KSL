package ksl.examples.release

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The four examples that overran the exposed-example run's per-example limit, re-run alone and
 * without one.
 *
 * They are all cross-entropy or restarted annealing, which is a suspicious pattern rather than a
 * random one: both compare and sort whole populations every generation, and this release changed
 * how penalized solutions are compared -- each comparison now re-stamps its operands to a common
 * evaluation number, which copies a solution. A per-comparison allocation was flagged as a risk
 * when that change was proposed and was never measured.
 *
 * Measured here rather than assumed: the cross-entropy book example takes 302 s on the commit
 * this release was cut from and 307 s on the release, a 1.7% difference that is inside
 * run-to-run noise. These examples are simply long. The limit was too short, not the library too
 * slow.
 *
 * They are separated from the main list because five-minute examples do not belong in a run that
 * is supposed to give a quick answer, and because leaving them there would train the reader to
 * ignore a red result.
 */
class SlowExamplesRunTest {

    private companion object {
        val SLOW: List<String> = listOf(
            "ksl.examples.book.chapter11.CESolverExampleKt",
            "ksl.examples.book.chapter11.SARestartSolverExampleKt",
            "ksl.examples.general.simopt.CrossEntropySolverTestingKt",
            "ksl.examples.general.simopt.PilotStudyKt"
        )
    }

    /**
     *  Both example runs are opt-in: `./gradlew :KSLExamples:test -Dksl.runExamples=true`.
     *
     *  They exist as a pre-release net, not as a build tax. Running the exposed list takes about
     *  a quarter of an hour and the long list about half of one, because these are simulation
     *  studies rather than unit-sized work, and nobody should pay that on an ordinary build.
     *  Skipping is announced rather than silent, so a reader of the report can see the net was
     *  not cast.
     */
    @BeforeEach
    fun onlyWhenAsked() {
        assumeTrue(System.getProperty("ksl.runExamples") == "true") {
            "set -Dksl.runExamples=true to run the examples"
        }
    }

    @TestFactory
    @DisplayName("The long-running examples complete")
    fun slowExamplesRun(): List<DynamicTest> = SLOW.map { className ->
        DynamicTest.dynamicTest(className.substringAfterLast('.')) {
            val startedAt = System.currentTimeMillis()
            val facade = Class.forName(className)
            facade.getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>())
            System.err.println(
                "ELAPSED ${className.substringAfterLast('.')}=" +
                    "${(System.currentTimeMillis() - startedAt) / 1000}s"
            )
        }
    }
}
