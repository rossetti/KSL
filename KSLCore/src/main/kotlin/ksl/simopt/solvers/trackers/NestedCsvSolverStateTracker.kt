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
 * An autonomous, persistent tracker that logs both macro and micro iterations of a nested
 * solver architecture to a single CSV file.
 * * ### Autonomous Lifecycle Management
 * This tracker listens to the parent (macro) solver's lifecycle to automatically manage OS file locks.
 * It lazily opens the CSV file in append mode when a run starts and safely closes the file when
 * the run ends. This allows a user to execute the solver multiple times sequentially without
 * manually managing the tracker, resulting in all consecutive runs being cleanly appended to the same file.
 * * ### Data Structure
 * The output CSV inherently interleaves "MACRO" summary rows and "MICRO" detail rows.
 * It leverages the [TrackingContext] to stamp each row with a run number, an optional experiment
 * name, and to correlate micro iterations to their parent macro iteration.
 *
 * @param macroSolver The outer solver managing the high-level algorithm (e.g., RandomRestartSolver).
 * @param microSolver The inner solver executing the detailed optimization steps (e.g., RSplineSolver).
 * @param outputFile The file destination for the CSV data.
 * @param columns A list of [NestedTrackerColumn] definitions detailing the CSV structure. Defaults to [defaultColumns].
 */
@Suppress("unused")
class NestedCsvSolverStateTracker(
    macroSolver: Solver,
    microSolver: Solver,
    private val outputFile: File,
    private val columns: List<NestedTrackerColumn> = defaultColumns
) : AbstractNestedSolverStateTracker(macroSolver, microSolver) {

    /**
     * Convenience constructor that creates a CSV file in the standard KSL output directory.
     * * @param macroSolver The outer solver managing the algorithm restarts.
     * @param microSolver The inner solver executing the optimization.
     * @param fileName The base name of the file (without the .csv extension).
     */
    constructor(macroSolver: Solver, microSolver: Solver, fileName: String) : this(
        macroSolver, microSolver, KSLFileUtil.createFileWithExtension(fileName, "csv", KSL.outDir)
    )

    private var writer: PrintWriter? = null
    private var isFirstRun = true

    /**
     * Responds to the outer solver's lifecycle events to safely manage file streams.
     * * * **INITIALIZED:** Opens the file in append mode. Writes the header row if the file is completely empty.
     * * **COMPLETED / ERROR:** Flushes the buffer and releases the OS file lock.
     * * @param status The execution lifecycle status emitted by the macro solver.
     */
    override fun onMacroLifecycleEvent(status: SolverStatus) {
        when (status) {
            SolverStatus.INITIALIZED -> {
                // Open in append mode so consecutive runs don't overwrite the file
                writer = PrintWriter(FileWriter(outputFile, true).buffered())

                // Only write headers if the file was just created
                if (isFirstRun && outputFile.length() == 0L) {
                    writer?.println(columns.joinToString(",") { it.headerName })
                }
                isFirstRun = false
            }
            SolverStatus.COMPLETED, SolverStatus.ERROR -> {
                // Safely release the file lock at the end of the macro run
                writer?.flush()
                writer?.close()
                writer = null
            }
            else -> {}
        }
    }

    /**
     * Formats and writes an overarching macro iteration row to the CSV.
     * @param outerSnapshot The mathematical state snapshot of the macro solver.
     */
    override fun consumeMacro(outerSnapshot: SolverStateSnapshot) {
        val dataRow = columns.joinToString(",") { it.macroStringifier(outerSnapshot, trackingContext) }
        writer?.println(dataRow)
    }

    /**
     * Formats and writes a detailed micro iteration row to the CSV.
     * @param innerSnapshot The mathematical state snapshot of the micro solver.
     */
    override fun consumeMicro(innerSnapshot: SolverStateSnapshot) {
        val dataRow = columns.joinToString(",") { it.microStringifier(innerSnapshot, trackingContext) }
        writer?.println(dataRow)
    }

    companion object {
        /** Automatically identifies which sequential run the data belongs to (1, 2, 3...). */
        val RUN_NUMBER = NestedTrackerColumn("RunNumber",
            macroStringifier = { _, ctx -> ctx.runNumber.toString() },
            microStringifier = { _, ctx -> ctx.runNumber.toString() }
        )

        /** A user-defined semantic label for the experiment (e.g., "High_Temp_Seed_1"). */
        val EXPERIMENT = NestedTrackerColumn("ExperimentName",
            macroStringifier = { _, ctx -> ctx.experimentName },
            microStringifier = { _, ctx -> ctx.experimentName }
        )

        /** Indicates whether the row contains macro-level summary data or micro-level detail data. */
        val LEVEL = NestedTrackerColumn("Level",
            macroStringifier = { _, _ -> "MACRO" },
            microStringifier = { _, _ -> "MICRO" }
        )

        /** * The iteration number of the outer solver.
         * On micro rows, this bridges the hierarchical gap by pulling the parent iteration from the [TrackingContext].
         */
        val MACRO_ITER = NestedTrackerColumn("MacroIter",
            macroStringifier = { snap, _ -> snap.iterationNumber.toString() },
            microStringifier = { _, ctx -> ctx.macroIteration.toString() }
        )

        /** * The iteration number of the inner solver.
         * Leaves a blank cell on macro rows to cleanly separate the hierarchy.
         */
        val MICRO_ITER = NestedTrackerColumn("MicroIter",
            macroStringifier = { _, _ -> "" },
            microStringifier = { snap, _ -> snap.iterationNumber.toString() }
        )

        /** The current estimated objective function value. */
        val EST_OBJ_VALUE = NestedTrackerColumn("EstimatedObjValue",
            macroStringifier = { snap, _ -> snap.estimatedObjFncValue.toString() },
            microStringifier = { snap, _ -> snap.estimatedObjFncValue.toString() }
        )

        /** The current penalized objective function value. */
        val PEN_OBJ_VALUE = NestedTrackerColumn("PenalizedObjValue",
            macroStringifier = { snap, _ -> snap.penalizedObjFncValue.toString() },
            microStringifier = { snap, _ -> snap.penalizedObjFncValue.toString() }
        )

        /** * The best point found so far.
         * Formatted safely for CSV injection by encapsulating the map string in double quotes
         * and converting internal map commas to semicolons (e.g., `"{x=1.0; y=2.0}"`).
         */
        val BEST_POINT = NestedTrackerColumn("BestPoint",
            macroStringifier = { snap, _ -> "\"${snap.bestSolutionSoFar.inputMap.toString().replace(",", ";")}\"" },
            microStringifier = { snap, _ -> "\"${snap.bestSolutionSoFar.inputMap.toString().replace(",", ";")}\"" }
        )

        /** * The current point being evaluated.
         * Formatted safely for CSV injection by encapsulating the map string in double quotes
         * and converting internal map commas to semicolons (e.g., `"{x=1.0; y=2.0}"`).
         */
        val CURRENT_POINT = NestedTrackerColumn("CurrentPoint",
            macroStringifier = { snap, _ -> "\"${snap.currentSolution.inputMap.toString().replace(",", ";")}\"" },
            microStringifier = { snap, _ -> "\"${snap.currentSolution.inputMap.toString().replace(",", ";")}\"" }
        )

        /** The default list of columns covering standard nested optimization metrics and points. */
        val defaultColumns: List<NestedTrackerColumn> = listOf(
            RUN_NUMBER, EXPERIMENT, LEVEL, MACRO_ITER, MICRO_ITER,
            EST_OBJ_VALUE, PEN_OBJ_VALUE, BEST_POINT, CURRENT_POINT
        )
    }
}