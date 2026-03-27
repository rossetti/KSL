package ksl.simopt.solvers

import ksl.simopt.evaluator.Solution

/**
 * Encapsulates the precise usage statistics of the evaluator during the optimization run.
 */
data class EvaluatorMetrics(
    val totalRequestsReceived: Int,
    val totalEvaluations: Int,
    val totalOracleEvaluations: Int,
    val totalCachedEvaluations: Int,
    val totalReplications: Int,
    val totalOracleReplications: Int,
    val totalCachedReplications: Int
)

/**
 * A strongly-typed, immutable snapshot of a solver's result state.
 */
sealed class SolverResult {

    /** Generates a human-readable summary of the current state or results. */
    abstract fun toReportString(): String

    /**
     * Represents a state where the solver has been configured but not yet executed.
     */
    data class NotExecuted(
        val solverName: String,
        val problemName: String
    ) : SolverResult() {
        override fun toReportString(): String {
            return """
                =================================================================
                OPTIMIZATION RESULTS PENDING
                =================================================================
                Solver  : $solverName
                Problem : $problemName
                Status  : Not Executed
                
                The solver has not yet performed any evaluations or iterations. 
                Call solve() or run() to generate results.
                =================================================================
            """.trimIndent()
        }
    }

    /**
     * Represents the completed (or partially completed/halted) trajectory of a solver run.
     */
    data class Completed(
        val solverName: String,
        val problemName: String,
        val initialSolution: Solution,
        val currentSolution: Solution,
        val bestSolution: Solution?,
        val totalIterations: Int,
        val evaluatorMetrics: EvaluatorMetrics,
        val isStoppingCriteriaMet: Boolean,
        val executionTimeMillis: Long? = null
    ) : SolverResult() {

        override fun toReportString(): String {
            val termReason = if (isStoppingCriteriaMet) "Stopping Criteria Satisfied" else "Execution Halted/Error"
            val timeString = executionTimeMillis?.let { "${it}ms" } ?: "Not Tracked"

            // Safely handle bestSolution just in case it wasn't tracked
            val bestEstObj = bestSolution?.estimatedObjFncValue?.let { String.format("%.6f", it) } ?: "N/A"
            val bestPenObj = bestSolution?.penalizedObjFncValue?.let { String.format("%.6f", it) } ?: "N/A"
            val bestCoords = bestSolution?.inputMap?.toString() ?: "N/A"

            return """
                =================================================================
                OPTIMIZATION RESULTS
                =================================================================
                Solver          : $solverName
                Problem         : $problemName
                Termination     : $termReason
                Execution Time  : $timeString
                
                --- Algorithmic Budget ---
                Total Iterations        : $totalIterations
                
                --- Evaluator Usage ---
                Requests Received       : ${evaluatorMetrics.totalRequestsReceived}
                
                Evaluations (Points)    : ${evaluatorMetrics.totalEvaluations}
                  ├─ Oracle Executed    : ${evaluatorMetrics.totalOracleEvaluations}
                  └─ Cache Hits         : ${evaluatorMetrics.totalCachedEvaluations}
                  
                Replications (Sim Runs) : ${evaluatorMetrics.totalReplications}
                  ├─ Oracle Executed    : ${evaluatorMetrics.totalOracleReplications}
                  └─ Cache Hits         : ${evaluatorMetrics.totalCachedReplications}
                
                --- Trajectory Summary ---
                [INITIAL POINT]
                Est. Objective : ${String.format("%.6f", initialSolution.estimatedObjFncValue)}
                Pen. Objective : ${String.format("%.6f", initialSolution.penalizedObjFncValue)}
                Coordinates    : ${initialSolution.inputMap}

                [FINAL/CURRENT POINT]
                Est. Objective : ${String.format("%.6f", currentSolution.estimatedObjFncValue)}
                Pen. Objective : ${String.format("%.6f", currentSolution.penalizedObjFncValue)}
                Coordinates    : ${currentSolution.inputMap}

                [BEST POINT FOUND]
                Est. Objective : $bestEstObj
                Pen. Objective : $bestPenObj
                Coordinates    : $bestCoords
                =================================================================
            """.trimIndent()
        }
    }
}