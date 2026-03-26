package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

/**
 * An autonomous tracker that logs continuous optimization progress to a CSV file.
 * Automatically manages OS file locks to support multiple sequential solver runs
 * appending cleanly to the same file.
 *
 * @param solver The solver to track.
 * @param outputFile The file to write the CSV data to.
 * @param columns A list of [TrackerColumn]s defining the CSV structure. Defaults to [defaultColumns].
 */
@Suppress("unused")
class CsvSolverStateTracker(
    solver: Solver,
    private val outputFile: File,
    private val columns: List<TrackerColumn> = defaultColumns
) : AbstractSolverStateTracker(solver) {

    /**
     * Convenience constructor that creates a CSV file in the KSL output directory.
     */
    constructor(solver: Solver, fileName: String) : this(
        solver,
        KSLFileUtil.createFileWithExtension(fileName, "csv", KSL.outDir)
    )

    private var writer: PrintWriter? = null
    private var isFirstRun = true

    override fun onLifecycleEvent(status: SolverStatus) {
        when (status) {
            SolverStatus.INITIALIZED -> {
                // Open the file in APPEND mode (true) so we don't overwrite previous runs
                writer = PrintWriter(FileWriter(outputFile, true).buffered())

                // Only print the header row if the file is completely empty
                if (isFirstRun && outputFile.length() == 0L) {
                    val headerRow = columns.joinToString(",") { it.headerName }
                    writer?.println(headerRow)
                }
                isFirstRun = false
            }
            SolverStatus.COMPLETED, SolverStatus.ERROR -> {
                // Safely flush and release the OS file lock
                writer?.flush()
                writer?.close()
                writer = null
            }
            else -> {}
        }
    }

    override fun consume(snapshot: SolverStateSnapshot) {
        // Build the data row dynamically by passing BOTH the snapshot and the context
        val dataRow = columns.joinToString(",") { column ->
            column.stringifier(snapshot, trackingContext)
        }
        writer?.println(dataRow)
    }

    companion object {
        // --- Context Metadata Columns ---
        val RUN_NUMBER = TrackerColumn("RunNumber") { _, ctx -> ctx.runNumber.toString() }
        val EXPERIMENT = TrackerColumn("ExperimentName") { _, ctx -> ctx.experimentName }

        // --- Standard Metrics Columns ---
        val ITERATION = TrackerColumn("Iteration") { snap, _ -> snap.iterationNumber.toString() }
        val ORACLE_CALLS = TrackerColumn("OracleCalls") { snap, _ -> snap.numOracleCalls.toString() }
        val REPLICATIONS = TrackerColumn("Replications") { snap, _ -> snap.numReplicationsRequested.toString() }
        val EST_OBJ_VALUE = TrackerColumn("EstimatedObjValue") { snap, _ -> snap.estimatedObjFncValue.toString() }
        val PEN_OBJ_VALUE = TrackerColumn("PenalizedObjValue") { snap, _ -> snap.penalizedObjFncValue.toString() }

        // Maps and complex strings are safely wrapped in quotes with internal commas replaced
        val BEST_POINT = TrackerColumn("BestPoint") { snap, _ ->
            "\"${snap.bestSolutionSoFar.inputMap.toString().replace(",", ";")}\""
        }
        val CURRENT_POINT = TrackerColumn("CurrentPoint") { snap, _ ->
            "\"${snap.currentSolution.inputMap.toString().replace(",", ";")}\""
        }
        val METRICS = TrackerColumn("Metrics") { snap, _ ->
            "\"${snap.solverSpecificState?.toString()?.replace(",", ";") ?: ""}\""
        }

        /** The default list of columns covering standard optimization metrics. */
        val defaultColumns: List<TrackerColumn> = listOf(
            RUN_NUMBER, EXPERIMENT, ITERATION, ORACLE_CALLS, REPLICATIONS, EST_OBJ_VALUE,
            PEN_OBJ_VALUE, BEST_POINT, CURRENT_POINT, METRICS
        )
    }
}