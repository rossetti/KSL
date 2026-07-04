package ksl.simopt.benchmark

import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.concurrent.ConfirmationOutcome
import ksl.simopt.solvers.concurrent.MemberStatus

/**
 *  The recorded outcome of one benchmark cell: one solver configuration run once
 *  (one macro-replication) on one problem under the experiment's replication budget.
 *
 *  Objective values are reported on the problem's natural (model) scale except for
 *  the penalized objective, which is the solver's internal minimization-oriented
 *  value (maximization problems are negated internally). The gap is oriented so that
 *  larger is worse and the reference (or the experiment-best for `GapType.BEST_FOUND`)
 *  gaps to zero; a negative gap means the run beat a best-known reference.
 *
 *  @param experimentName the owning experiment's name
 *  @param problemName the problem case's name
 *  @param solverLabel the solver case's label
 *  @param repNum the 1-based macro-replication number
 *  @param cellLabel the unique cell label ("problem_solver_rN"); also the solver's name
 *  @param status the cell's lifecycle outcome (completed, failed, stopped before start)
 *  @param startingPoint the common starting point the cell's solver was given
 *  @param bestInputs the best design point the solver found (the problem's bad solution
 *  when the cell failed — see the isBestValid flag)
 *  @param bestObjective the estimated objective value at the best point (model scale)
 *  @param bestPenalizedObjective the solver-internal penalized objective at the best point
 *  @param isBestValid false when the best solution is a placeholder from a failed or
 *  never-started cell
 *  @param isInputFeasible whether the best point satisfies the problem's input ranges
 *  and deterministic constraints
 *  @param responseConstraintViolation the total response-constraint violation at the
 *  best point (zero when response-feasible or the problem has no response constraints)
 *  @param numOracleCalls the solver's cumulative oracle-call count
 *  @param numReplicationsRequested the solver's cumulative requested replications — the
 *  actual budget consumption used for normalization
 *  @param totalIterations the solver's iteration count, when the solver ran
 *  @param wallClockMillis the solver's execution time in milliseconds, when tracked
 *  @param gap the optimality gap of the best objective against the problem's gap basis
 *  @param gapType the basis the gap was computed against
 *  @param errorMessage the failure cause when the cell failed; null otherwise
 */
data class BenchmarkRunResult(
    val experimentName: String,
    val problemName: String,
    val solverLabel: String,
    val repNum: Int,
    val cellLabel: String,
    val status: MemberStatus,
    val startingPoint: Map<String, Double>,
    val bestInputs: Map<String, Double>,
    val bestObjective: Double,
    val bestPenalizedObjective: Double,
    val isBestValid: Boolean,
    val isInputFeasible: Boolean,
    val responseConstraintViolation: Double,
    val numOracleCalls: Int,
    val numReplicationsRequested: Int,
    val totalIterations: Int?,
    val wallClockMillis: Long?,
    val gap: Double?,
    val gapType: GapType?,
    val errorMessage: String? = null
)

/**
 *  The recorded outcome of all cells of one problem within a benchmark experiment,
 *  plus the problem-level confirmation stage and the gap basis shared by the runs.
 *
 *  @param problemName the problem case's name
 *  @param tags the problem case's descriptive tags
 *  @param runs the cell results, in deterministic cell order (solver case major,
 *  macro-replication minor)
 *  @param confirmation the CRN confirmation outcome across the problem's finalists;
 *  null when confirmation was disabled or no cell produced a valid candidate
 *  @param winner the winning solution for the problem: the confirmation winner when
 *  confirmation ran, otherwise the best valid solution by point estimate; null when
 *  every cell failed
 *  @param gapBasisObjective the objective value runs were gapped against; null when no
 *  reference exists and no run produced a valid solution
 *  @param gapType the kind of basis the gaps were computed against
 */
data class ProblemBenchmarkResult(
    val problemName: String,
    val tags: Map<String, String>,
    val runs: List<BenchmarkRunResult>,
    val confirmation: ConfirmationOutcome?,
    val winner: Solution?,
    val gapBasisObjective: Double?,
    val gapType: GapType?
)

/**
 *  The in-memory result of a benchmark experiment: one entry per problem, in problem
 *  order, each holding its cell-level run records, confirmation outcome, and gap basis.
 *
 *  @param experimentName the experiment's name
 *  @param macroReplications the number of macro-replications per (problem, solver) pair
 *  @param replicationBudgetPerRun the per-cell replication budget
 *  @param problemResults the per-problem results, in the experiment's problem order
 */
data class BenchmarkSummary(
    val experimentName: String,
    val macroReplications: Int,
    val replicationBudgetPerRun: Int,
    val problemResults: List<ProblemBenchmarkResult>
) {
    /** All cell-level run records across all problems, in deterministic order. */
    val allRuns: List<BenchmarkRunResult>
        get() = problemResults.flatMap { it.runs }
}
