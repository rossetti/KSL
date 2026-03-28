package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus

/**
 * A self-terminating tracker that prints optimization progress directly to the console.
 * Useful for real-time monitoring and debugging.
 * Automatically handles lifecycle events to print clear visual boundaries between runs and errors.
 * * @param solver The solver to track.
 * @param formatStrategy A function that formats a [SolverStateSnapshot] into a String.
 * Defaults to [defaultFormat].
 * @param lifecycleFormatStrategy A function that formats a [SolverStatus] into a String.
 * If it returns null, nothing is printed for that status. Defaults to [defaultLifecycleFormat].
 */
@Suppress("unused")
class ConsoleSolverStateTracker(
    solver: Solver,
    private val formatStrategy: (SolverStateSnapshot) -> String = ::defaultFormat,
    private val lifecycleFormatStrategy: (SolverStatus) -> String? = ::defaultLifecycleFormat
) : AbstractSolverStateTracker(solver) {

    override fun onLifecycleEvent(status: SolverStatus) {
        // Only print if the strategy returns a non-null string
        lifecycleFormatStrategy(status)?.let { println(it) }
    }

    override fun consume(snapshot: SolverStateSnapshot) {
        println(formatStrategy(snapshot))
    }

    companion object {
        /**
         * Provides the default tabular formatting for a single [SolverStateSnapshot].
         * * The output includes the iteration number, oracle calls, objective function values,
         * and the string representations of the current and best solutions. Numerical values
         * are padded to align neatly in a fixed-width console environment.
         *
         * @param snapshot The mathematical state of the solver at a specific iteration.
         * @return A formatted, tabular string representing the snapshot.
         */
        fun defaultFormat(snapshot: SolverStateSnapshot): String {
            val iter = snapshot.iterationNumber.toString().padStart(4)
            val oracle = snapshot.numOracleCalls.toString().padStart(6)

            val estObj = String.format("%.6G", snapshot.estimatedObjFncValue).padStart(12)
            val penObj = String.format("%.6G", snapshot.penalizedObjFncValue).padStart(12)

            val bestPoint = snapshot.bestSolutionSoFar.inputMap.toString()
            val currentPoint = snapshot.currentSolution.inputMap.toString()

            return "[Iter: $iter | Oracle: $oracle] EstObj: $estObj | PenObj: $penObj | Cur: $currentPoint | Best: $bestPoint"
        }

        /**
         * Provides the default console formatting for solver lifecycle events.
         * * Maps each [SolverStatus] to a distinct, human-readable console message
         * with visual markers (e.g., `>>>` for flow and `!!!` for errors).
         *
         * @param status The execution lifecycle status emitted by the solver.
         * @return A formatted string for the console, or `null` if the event should be ignored.
         */
        fun defaultLifecycleFormat(status: SolverStatus): String? = when (status) {
            SolverStatus.INITIALIZED -> "\n>>> SOLVER INITIALIZED: Baseline captured."
            SolverStatus.STARTED     -> ">>> SOLVER STARTED: Beginning iterations..."
            SolverStatus.COMPLETED   -> ">>> SOLVER COMPLETED: Run finished successfully.\n"
            SolverStatus.ERROR       -> "!!! SOLVER ERROR: An exception occurred during execution !!!\n"
        }
    }
}