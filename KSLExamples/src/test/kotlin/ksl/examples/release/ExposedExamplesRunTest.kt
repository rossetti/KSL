package ksl.examples.release

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.time.Duration

/**
 * Runs the examples that the R1.6.1 changes could actually reach, and asserts only that they
 * complete without throwing.
 *
 * KSLExamples holds hundreds of runnable demos and **none of them is executed by the build**, so
 * a change elsewhere in KSL can leave an example compiling and broken. A general runner for all
 * of them is designed but not built (see the example-runner plan); it needs discovery, a tier
 * registry, and a change to KSLCore's browser chokepoint, none of which belongs in a patch
 * release. This is the same idea applied to a curated list: the demos that touch the parts of
 * the library this release altered.
 *
 * The list was derived rather than chosen -- every example that mentions a solver, a benchmark
 * experiment, `ksl.simopt`, or a capacity schedule, and declares a top-level `main`.
 *
 * What this does NOT establish: it is a "did it throw" check, not a numerical one. Several of
 * these examples will legitimately print different numbers than before, because the release
 * corrects how penalized solutions are compared and how a benchmark addresses its starting
 * points. It also runs every example in one JVM, so they share KSL's default stream provider;
 * that can change an example's numbers but not, in any case seen here, whether it completes.
 */
class ExposedExamplesRunTest {

    private companion object {

        /** Generous: several of these are full simulation studies, not unit-sized work. */
        val PER_EXAMPLE_TIMEOUT: Duration = Duration.ofSeconds(180)

        /**
         * Examples deliberately left out, each because better evidence for it already exists or
         * because it cannot run unattended. Never silently dropped.
         */
        val EXCLUDED: Map<String, String> = mapOf(
            "ksl.examples.general.misc.CheckStuffKt" to
                "opens a browser (showInBrowser); the headless guard for that is Phase 0 of the " +
                "example-runner plan and is not part of this release",
            "ksl.examples.general.simopt.study1.Study1MainKt" to
                "the full Study-1 grid; the same run was executed for this release (5,700 cells, " +
                "0 failures) and is stronger evidence than a smoke of it",
            "ksl.examples.general.simopt.study1.Study1SmokeKt" to
                "superseded by the Study-1 main run above",
            "ksl.examples.general.simopt.ISCSolverTestingKt" to
                "ISC's correct-selection guarantee samples on the order of (noise/IZ)^2 " +
                "replications; measured separately for this release at 0.3x to 13x the budget",
            "ksl.examples.general.simopt.BayesianOptimizationSolverTestingKt" to
                "BO's surrogate fit is cubic in evaluated points and can run for many minutes; " +
                "BO ran in the release's own eight-solver smoke instead"
            ,
            "ksl.examples.book.chapter11.CESolverExampleKt" to
                "owned by SlowExamplesRunTest; measured at 292 s, far past this gate's 180 s budget",
            "ksl.examples.book.chapter11.SARestartSolverExampleKt" to
                "owned by SlowExamplesRunTest; measured at 347 s, far past this gate's 180 s budget",
            "ksl.examples.general.simopt.CrossEntropySolverTestingKt" to
                "owned by SlowExamplesRunTest; measured at 294 s, far past this gate's 180 s budget",
            "ksl.examples.general.simopt.PilotStudyKt" to
                "owned by SlowExamplesRunTest; measured at 734 s, four times this gate's 180 s budget"
        )

        /**
         * Every example that mentions a solver, a benchmark experiment, `ksl.simopt` or a
         * capacity schedule and has a `main`, minus the exclusions above.
         */
        val EXPOSED: List<String> = listOf(
            "ksl.examples.general.misc.TestResourceScheduleKt",
            "ksl.examples.general.models.ProposedModel2Kt",
            "ksl.examples.general.models.inventory.TwoEchelonOptProblemKt",
            "ksl.examples.general.models.station.StationNetworkWithShiftKt",
            "ksl.examples.general.models.station.StemFairEnhancedStationKt",
            "ksl.examples.general.simopt.BenchmarkDemoKt",
            "ksl.examples.general.simopt.GeneticAlgorithmSolverTestingKt",
            "ksl.examples.general.simopt.MakeProblemDefinitionsKt",
            "ksl.examples.general.simopt.ParticleSwarmSolverTestingKt",
            "ksl.examples.general.simopt.RSPLINESolverTestingKt",
            "ksl.examples.general.simopt.RSplineSolverExampleKt",
            "ksl.examples.general.simopt.SimulatedAnnealingSolverTestingKt",
            "ksl.examples.general.simopt.StochasticHillClimberExampleKt",
            "ksl.examples.general.simopt.StochasticHillClimbingSolverTestingKt",
            "ksl.examples.general.simopt.tutorial.CombinedBenchmarkExampleKt",
            "ksl.examples.general.simopt.tutorial.NewsvendorBenchmarkExampleKt",
            "ksl.examples.general.simopt.tutorial.NewsvendorOptimizationExampleKt",
            "ksl.examples.general.simopt.tutorial.RQInventoryBenchmarkExampleKt",
            "ksl.examples.general.simopt.tutorial.RQInventoryOptimizationExampleKt",
            "ksl.examples.general.simopt.tutorial.RosenbrockBenchmarkExampleKt",
            "ksl.examples.general.simopt.tutorial.RosenbrockOptimizationExampleKt",
            "ksl.examples.general.supplychain.MultiEchelonNetworkOptProblemKt",
            "ksl.examples.general.utilities.TestStatisticsKt"
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

    @TestFactory
    @DisplayName("Examples reachable by the release's changes complete without throwing")
    fun exposedExamplesRun(): List<DynamicTest> {
        println("Running ${EXPOSED.size} exposed examples; ${EXCLUDED.size} excluded:")
        for ((name, reason) in EXCLUDED) {
            println("   EXCLUDED ${name.substringAfterLast('.')}: $reason")
        }
        return EXPOSED.map { className ->
            DynamicTest.dynamicTest(className.substringAfterLast('.')) {
                val startedAt = System.currentTimeMillis()
                assertTimeoutPreemptively(PER_EXAMPLE_TIMEOUT) { runExample(className) }
                println("   ok ${className.substringAfterLast('.')} " +
                    "(${(System.currentTimeMillis() - startedAt) / 1000}s)")
            }
        }
    }
}
