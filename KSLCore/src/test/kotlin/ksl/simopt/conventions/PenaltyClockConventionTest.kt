package ksl.simopt.conventions

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Keeps the penalty clock explicit.
 *
 * The penalized objective is a property of a solution *and a clock*: with a dynamic penalty
 * `f + M0 * k * v`, a value carried over from iteration `j` belongs to a different objective
 * function than the one in force at iteration `k`. Reading it without naming a clock is how twelve
 * separate call sites came to compare solutions across subproblems — a defect found three times, in
 * three different releases, each time by accident rather than by looking.
 *
 * Diligence has now been tried three times. This is the part that does not depend on it: a new
 * decision site cannot read a clock-free penalized value without either using a
 * `SolutionScorer` / `penalizedObjFncValueAt`, or editing an allowlist — and editing an allowlist
 * is a reviewable act, which is the whole point.
 */
class PenaltyClockConventionTest {

    private companion object {

        /**
         * Files permitted to read `recordedPenalizedObjFncValue` — the value at a solution's own
         * clock. Every entry is reporting, or a documented exception, and none decides between
         * solutions found at different iterations:
         *
         *  - `Solution.kt` declares it, and carries the own-clock comparators whose KDoc says so
         *  - `BenchmarkExperiment`, `BenchmarkResultsDb` record it to results and to the database
         *  - `MemorySolutionCache` picks an eviction victim (a heuristic, claiming no correctness)
         *  - `Solver` initialises the tracker snapshot, logs, and — in `penalizedDifference` —
         *    reads it only after restamping both operands to a common clock
         *  - `RSplineSolver` differences gradients within ONE evaluation batch, so the clock is
         *    already common (verified, not assumed)
         *  - the remaining solvers expose it in `extractSolverSpecificState` tracker maps
         */
        val ALLOWED_RECORDED_READS: Set<String> = setOf(
            "evaluator/Solution.kt",
            "benchmark/BenchmarkExperiment.kt",
            "benchmark/io/BenchmarkResultsDb.kt",
            "cache/MemorySolutionCache.kt",
            "solvers/Solver.kt",
            "solvers/algorithms/RSplineSolver.kt",
            "solvers/algorithms/genetic/GeneticAlgorithmSolver.kt",
            "solvers/algorithms/isc/CompassSolver.kt",
            "solvers/algorithms/isc/NichingGeneticAlgorithmSolver.kt",
            "solvers/algorithms/pso/ParticleSwarmSolver.kt",
            "solvers/concurrent/SolverPortfolio.kt"
        )

        /**
         * Files permitted to write `.penalizedObjFncValue`. The bare property on `Solution` is
         * deprecated and has no remaining users; these matches are a different member that happens
         * to share the name — `ProblemDefinition`'s function, and `SolverState`'s own field, which
         * the trackers read from a snapshot rather than from a solution.
         */
        val ALLOWED_BARE_READS: Set<String> = setOf(
            "evaluator/Solution.kt",
            "benchmark/BenchmarkExperiment.kt",
            "problem/ProblemDefinition.kt",
            "solvers/trackers/ConsoleSolverStateTracker.kt",
            "solvers/trackers/NestedConsoleSolverStateTracker.kt",
            "solvers/trackers/CsvSolverStateTracker.kt",
            "solvers/trackers/NestedCsvSolverStateTracker.kt",
            "solvers/trackers/DataFrameSolverStateTracker.kt"
        )
    }

    private fun simoptSources(): List<File> {
        val root = File("src/main/kotlin/ksl/simopt")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun filesContaining(pattern: Regex): Set<String> {
        val root = File("src/main/kotlin/ksl/simopt")
        return simoptSources()
            .filter { file -> file.readLines().any { pattern.containsMatchIn(it) } }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toSet()
    }

    /** The scan must actually see the sources; a vacuous pass would be worse than no test. */
    @Test
    @DisplayName("The convention scan reaches the simopt sources")
    fun scanIsNotVacuous() {
        assertTrue(simoptSources().size > 50) {
            "expected to scan the simopt sources but found ${simoptSources().size} files; the " +
                "test's working directory is not the KSLCore module root"
        }
    }

    @Test
    @DisplayName("Only reporting code reads the penalized value at a solution's own clock")
    fun recordedPenalizedValueIsReadOnlyWhereAllowed() {
        val offenders = filesContaining(Regex("recordedPenalizedObjFncValue")) - ALLOWED_RECORDED_READS
        assertTrue(offenders.isEmpty()) {
            "these files read the penalized value at each solution's own clock:\n" +
                offenders.sorted().joinToString("\n") { "    $it" } +
                "\n\nIf this is a DECISION over more than one solution, take it through " +
                "Solver.scorerNow or Solution.penalizedObjFncValueAt(k) so every operand is " +
                "judged in the same subproblem of the penalty sequence. If it is genuinely " +
                "reporting, add the file to ALLOWED_RECORDED_READS with a note saying why."
        }
    }

    @Test
    @DisplayName("The deprecated clock-free property has no remaining readers")
    fun deprecatedPenalizedValueIsUnused() {
        val offenders = filesContaining(Regex("\\.penalizedObjFncValue\\b")) - ALLOWED_BARE_READS
        assertTrue(offenders.isEmpty()) {
            "these files read the deprecated Solution.penalizedObjFncValue:\n" +
                offenders.sorted().joinToString("\n") { "    $it" } +
                "\n\nUse recordedPenalizedObjFncValue for reporting, or a scorer for decisions."
        }
    }
}
