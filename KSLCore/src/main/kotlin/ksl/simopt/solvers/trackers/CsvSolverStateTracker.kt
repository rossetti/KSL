package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import java.io.File
import java.io.PrintWriter

/**
 * A self-terminating tracker that logs optimization progress to a CSV file.
 * Automatically handles lifecycle events to safely close the file stream.
 */
class CsvSolverStateTracker(
    solver: Solver,
    outputFile: File
) : AbstractSolverStateTracker(solver) {

    // Wrap the file output in a buffered PrintWriter for high-performance I/O
    private val writer = PrintWriter(outputFile.bufferedWriter())

    init {
        // Updated header to match the revised SolverStateSnapshot properties
        writer.println("Iteration,OracleCalls,Replications,EstimatedObjValue,PenalizedObjValue,BestPoint,CurrentPoint,Metrics")
    }

    override fun consume(snapshot: SolverStateSnapshot) {
        val iteration = snapshot.iterationNumber
        val oracleCalls = snapshot.numOracleCalls
        val replications = snapshot.numReplicationsRequested
        val estObjValue = snapshot.estimatedObjFncValue
        val penObjValue = snapshot.penalizedObjFncValue

        // Sanitize string representations so commas in the solution maps do not break CSV columns
        // (Assuming your Solution class exposes an inputMap or similar iterable property)
        val bestPoint = snapshot.bestSolutionSoFar.inputMap.toString().replace(",", ";")
        val currentPoint = snapshot.currentSolution.inputMap.toString().replace(",", ";")

        // Handle the optional map gracefully
        val metrics = snapshot.solverSpecificState?.toString()?.replace(",", ";") ?: ""

        // Write the constructed row directly to the buffer
        writer.println("$iteration,$oracleCalls,$replications,$estObjValue,$penObjValue,\"$bestPoint\",\"$currentPoint\",\"$metrics\"")
    }

    /**
     * Triggered automatically by the base class when COMPLETED or ERROR is received.
     */
    override fun onCloseResources() {
        writer.flush()
        writer.close()
    }
}