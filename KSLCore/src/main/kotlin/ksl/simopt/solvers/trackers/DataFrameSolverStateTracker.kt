package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

/**
 * An autonomous tracker that collects iterations in memory and compiles them into a strongly-typed DataFrame.
 * If the solver is executed multiple times, this tracker accumulates the data and rebuilds
 * the DataFrame to include all consecutive runs.
 */
class DataFrameSolverStateTracker(
    solver: Solver,
    private val columns: List<DataFrameColumn> = defaultColumns
) : AbstractSolverStateTracker(solver) {

    // Accumulates rows across all runs
    private val rows = mutableListOf<Map<String, Any?>>()

    /** * The compiled DataFrame. This is updated automatically every time the
     * solver emits a COMPLETED or ERROR lifecycle event.
     */
    var dataFrame: AnyFrame? = null
        private set

    override fun onLifecycleEvent(status: SolverStatus) {
        if (status == SolverStatus.COMPLETED || status == SolverStatus.ERROR) {
            // Compile the accumulated rows into a DataFrame at the end of the run
            dataFrame = rows.toDataFrame()
        }
    }

    override fun consume(snapshot: SolverStateSnapshot) {
        val rowMap = columns.associate { column ->
            // Extract using BOTH the snapshot and the current context
            column.columnName to column.extractor(snapshot, trackingContext)
        }
        rows.add(rowMap)
    }

    /** Clears all accumulated data in memory if needed. */
    fun clearData() {
        rows.clear()
        dataFrame = null
    }

    companion object {
        // --- Context Columns ---
        val RUN_NUMBER = DataFrameColumn("RunNumber") { _, ctx -> ctx.runNumber }
        val EXPERIMENT = DataFrameColumn("ExperimentName") { _, ctx -> ctx.experimentName }

        // --- Standard Data Columns ---
        val ITERATION = DataFrameColumn("Iteration") { snap, _ -> snap.iterationNumber }
        val ORACLE_CALLS = DataFrameColumn("OracleCalls") { snap, _ -> snap.numOracleCalls }
        val EST_OBJ_VALUE = DataFrameColumn("EstimatedObjValue") { snap, _ -> snap.estimatedObjFncValue }
        val PEN_OBJ_VALUE = DataFrameColumn("PenalizedObjValue") { snap, _ -> snap.penalizedObjFncValue }
        val BEST_POINT_MAP = DataFrameColumn("BestPoint") { snap, _ -> snap.bestSolutionSoFar.inputMap }

        val defaultColumns: List<DataFrameColumn> = listOf(
            RUN_NUMBER, EXPERIMENT, ITERATION, ORACLE_CALLS, EST_OBJ_VALUE, PEN_OBJ_VALUE, BEST_POINT_MAP
        )
    }
}