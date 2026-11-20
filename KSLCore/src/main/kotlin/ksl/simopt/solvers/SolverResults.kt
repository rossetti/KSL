package ksl.simopt.solvers

import ksl.simopt.evaluator.Solution

/**
 * Encapsulates the precise usage statistics of the evaluator during the optimization run.
 */
data class EvaluatorMetrics(
    val totalEvaluatorCalls: Int,
    val totalDesignPointsEvaluated: Int,
    val totalReplicationsRequested: Int,
    val totalOracleReplications: Int,
    val totalCachedReplications: Int
) {
    override fun toString(): String {
        // Calculate the percentage of computational effort saved by the cache
        val savedPct = if (totalReplicationsRequested > 0) {
            (totalCachedReplications.toDouble() / totalReplicationsRequested) * 100
        } else 0.0

        return """
            Evaluator Invocations   : $totalEvaluatorCalls
            Design Points Evaluated : $totalDesignPointsEvaluated
              
            Total Replications Req. : $totalReplicationsRequested
              ├─ Executed by Oracle : $totalOracleReplications
              └─ Satisfied by Cache : $totalCachedReplications
              
            Cache Savings           : ${String.format("%.1f", savedPct)}% of simulation budget
        """.trimIndent()
    }
}

/**
 * A strongly-typed, immutable snapshot of a solver's result state.
 */
sealed class SolverResult {

    companion object {
        internal const val STOPPING_CRITERIA_SATISFIED = "Stopping Criteria Satisfied"
        internal const val MAXIMUM_ITERATIONS_REACHED = "Maximum Iterations Reached"
        internal const val MAXIMUM_EXECUTION_TIME_REACHED = "Maximum Execution Time Reached"
        internal const val HALTED_BEFORE_COMPLETION = "Halted Before Completion"
        internal const val NOT_FINISHED = "Not Finished"
    }


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
                Call runAllIterations() to generate results.
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
        val initialSolution: Solution?,
        val currentSolution: Solution,
        val bestSolution: Solution?,
        val totalIterations: Int,
        val evaluatorMetrics: EvaluatorMetrics,
        val isStoppingCriteriaMet: Boolean,
        val executionTimeMillis: Long? = null
    ) : SolverResult() {

        /**
         * Why the run ended: "Stopping Criteria Satisfied", "Maximum Iterations Reached", "Maximum Execution Time
         * Reached", "Halted Before Completion", or "Not Finished" for a run read before it ended. The solver records
         * it when the run ends, so a later change to the solver's settings does not alter it. A property of the body
         * rather than the constructor, so the constructor and `copy` keep their signatures; `copy` does not carry it,
         * and a result made directly reports the criteria or a halt.
         */
        var terminationReason: String = if (isStoppingCriteriaMet) STOPPING_CRITERIA_SATISFIED else HALTED_BEFORE_COMPLETION
            internal set

        override fun toString(): String {
            return toReportString()
        }

        override fun toReportString(): String {
            val termReason = terminationReason
            val timeString = executionTimeMillis?.let { "${it}ms" } ?: "Not Tracked"

            // Format the best solution safely in case it is null
            val initSolStr = initialSolution?.toString() ?: "Not Applicable (Population-based or not provided)"
            val bestSolStr = bestSolution?.toString() ?: "No valid best solution found."

            /* * We use `prependIndent("        ").trimStart()` to inject the multiline strings
             * perfectly aligned under their respective headers without breaking the trimIndent() margin.
             */
            return """
                =================================================================
                SOLVER RESULTS
                =================================================================
                Solver          : $solverName
                Problem         : $problemName
                Termination     : $termReason
                Execution Time  : $timeString
                
                --- Algorithmic Budget ---
                Total Iterations        : $totalIterations
                
                --- Evaluator Usage ---
                ${evaluatorMetrics.toString().prependIndent("                ").trimStart()}
                
                --- Trajectory Summary ---
                [INITIAL POINT]
                ${initSolStr.prependIndent("                ").trimStart()}

                [FINAL/CURRENT POINT]
                ${currentSolution.toString().prependIndent("                ").trimStart()}

                [BEST POINT FOUND]
                ${bestSolStr.prependIndent("                ").trimStart()}
                =================================================================
            """.trimIndent()
        }
    }
}