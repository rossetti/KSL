package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus

/**
 * A persistent nested tracker that prints both macro and micro iterations to the console.
 * Automatically displays run numbers and experiment names across multiple autonomous runs.
 * Micro iterations are indented for clear visual hierarchy.
 *
 * @param macroSolver The outer solver managing the restarts/macro-steps.
 * @param microSolver The inner solver executing the optimization.
 * @param macroFormatStrategy Formatter for macro-level snapshots. Defaults to [defaultMacroFormat].
 * @param microFormatStrategy Formatter for micro-level snapshots. Defaults to [defaultMicroFormat].
 * @param macroLifecycleFormatStrategy Formatter for macro lifecycle events. Defaults to [defaultMacroLifecycleFormat].
 */
class NestedConsoleSolverStateTracker(
    macroSolver: Solver,
    microSolver: Solver,
    private val macroFormatStrategy: (SolverStateSnapshot, TrackingContext) -> String = ::defaultMacroFormat,
    private val microFormatStrategy: (SolverStateSnapshot, TrackingContext) -> String = ::defaultMicroFormat,
    private val macroLifecycleFormatStrategy: (SolverStatus, TrackingContext) -> String? = ::defaultMacroLifecycleFormat
) : AbstractNestedSolverStateTracker(macroSolver, microSolver) {

    override fun onMacroLifecycleEvent(status: SolverStatus) {
        // Pass both the status and the autonomously managed tracking context
        macroLifecycleFormatStrategy(status, trackingContext)?.let { println(it) }
    }

    override fun consumeMacro(outerSnapshot: SolverStateSnapshot) {
        println(macroFormatStrategy(outerSnapshot, trackingContext))
    }

    override fun consumeMicro(innerSnapshot: SolverStateSnapshot) {
        println(microFormatStrategy(innerSnapshot, trackingContext))
    }

    companion object {
        /**
         * Provides the default console formatting for an outer (macro) solver's state snapshot.
         * Now includes the experiment name and run number so users can distinguish between runs in the console.
         */
        fun defaultMacroFormat(outerSnapshot: SolverStateSnapshot, context: TrackingContext): String {
            val run = context.runNumber
            val exp = context.experimentName
            val macroIter = outerSnapshot.iterationNumber
            val oracle = outerSnapshot.numOracleCalls
            val estObj = String.format("%.4G", outerSnapshot.estimatedObjFncValue)
            val bestPoint = outerSnapshot.bestSolutionSoFar.inputMap.toString()

            return "[Run $run: $exp | MACRO: $macroIter] Total Oracle Calls: $oracle | Best EstObj: $estObj | Best Point: $bestPoint"
        }

        /**
         * Provides the default console formatting for an inner (micro) solver's state snapshot.
         * Uses indentation to show hierarchy. It has access to the macro iteration via the [TrackingContext]
         * if needed, though the default format omits it for visual clarity.
         */
        fun defaultMicroFormat(innerSnapshot: SolverStateSnapshot, context: TrackingContext): String {
            val microIter = innerSnapshot.iterationNumber.toString().padStart(4)
            val estObj = String.format("%.4G", innerSnapshot.estimatedObjFncValue).padStart(10)
            val penObj = String.format("%.4G", innerSnapshot.penalizedObjFncValue).padStart(10)
            val currentPoint = innerSnapshot.currentSolution.inputMap.toString()

            return "    -> [Micro: $microIter] EstObj: $estObj | PenObj: $penObj | Cur: $currentPoint"
        }

        /**
         * Provides the default console formatting for macro solver lifecycle events.
         * Dynamically injects the experiment name and run number into the initialization banner.
         */
        fun defaultMacroLifecycleFormat(status: SolverStatus, context: TrackingContext): String? = when (status) {
            SolverStatus.INITIALIZED -> "\n======================================================\n>>> EXPERIMENT INITIALIZED: ${context.experimentName} (Run ${context.runNumber})"
            SolverStatus.STARTED     -> ">>> MACRO EXPERIMENT STARTED"
            SolverStatus.COMPLETED   -> ">>> MACRO EXPERIMENT COMPLETED\n======================================================\n"
            SolverStatus.ERROR       -> "!!! MACRO EXPERIMENT ERROR !!!\n======================================================\n"
        }
    }
}