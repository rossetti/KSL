package ksl.simopt.solvers.concurrent

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverResult

/**
 * One member of a concurrent solver run: the factory that creates its solver instance
 * plus the member-specific configuration applied at creation time.
 *
 * @param solverFactory creates the member's solver instance, bound to the member's
 * private evaluator
 * @param label a short, unique (within the run) human-readable identifier for the
 * member; flows into solver names, trace files, logs, and results
 * @param startingPoint the member's starting point; when null the created solver
 * generates its own. For reproducibility, owners that randomize starting points should
 * pre-draw them on the launching thread before members run.
 * @param innerSolverDecorator invoked with the freshly created solver and the member
 * index, before the solver runs, on the member's worker thread. This is the attachment
 * hook for per-member trackers and other instrumentation; anything it touches must be
 * safe to use from worker threads.
 */
data class SolverMemberTask(
    val solverFactory: SolverFactoryIfc,
    val label: String,
    val startingPoint: InputMap? = null,
    val innerSolverDecorator: ((solver: Solver, memberIndex: Int) -> Unit)? = null
) {
    init {
        require(label.isNotBlank()) { "The member label must not be blank" }
    }
}

/**
 * The lifecycle outcome of one member of a concurrent solver run.
 */
enum class MemberStatus {
    /** The member's solver ran to completion (including a graceful stop mid-search). */
    COMPLETED,

    /** The member failed with an exception; its best solution is the problem's bad solution. */
    FAILED,

    /** A stop request arrived before the member started; its solver never ran. */
    STOPPED_BEFORE_START
}

/**
 * The result of one member of a concurrent solver run, captured on the member's worker
 * after its solver finished (or failed). Results are safe to read once obtained from
 * the runner: the await establishes the necessary happens-before ordering.
 *
 * @param memberIndex the 0-based index of the member within the run
 * @param label the member's label
 * @param bestSolution the best solution the member found; the problem's bad solution
 * when the member failed or never ran
 * @param numOracleCalls the member solver's cumulative oracle-call count
 * @param numReplicationsRequested the member solver's cumulative requested replications
 * @param solverResult the member solver's full result record, when the solver was
 * created and ran; null when the member never ran
 * @param status the member's lifecycle outcome
 * @param error the failure cause when status is FAILED; null otherwise
 */
data class SolverMemberResult(
    val memberIndex: Int,
    val label: String,
    val bestSolution: Solution,
    val numOracleCalls: Int,
    val numReplicationsRequested: Int,
    val solverResult: SolverResult?,
    val status: MemberStatus,
    val error: Throwable? = null
) {
    val isSuccess: Boolean
        get() = status == MemberStatus.COMPLETED
}
