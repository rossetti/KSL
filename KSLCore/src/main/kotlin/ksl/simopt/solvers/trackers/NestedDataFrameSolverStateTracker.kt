package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

class NestedDataFrameSolverStateTracker(
    macroSolver: Solver,
    microSolver: Solver,
    private val columns: List<NestedDataFrameColumn> = defaultColumns
) : AbstractNestedSolverStateTracker(macroSolver, microSolver) {

    private val rows = mutableListOf<Map<String, Any?>>()
    var dataFrame: AnyFrame? = null
        private set

    override fun onMacroLifecycleEvent(status: SolverStatus) {
        if (status == SolverStatus.COMPLETED || status == SolverStatus.ERROR) {
            dataFrame = rows.toDataFrame()
        }
    }

    override fun consumeMacro(outerSnapshot: SolverStateSnapshot) {
        val rowMap = columns.associate { it.columnName to it.macroExtractor(outerSnapshot, trackingContext) }
        rows.add(rowMap)
    }

    override fun consumeMicro(innerSnapshot: SolverStateSnapshot) {
        val rowMap = columns.associate { it.columnName to it.microExtractor(innerSnapshot, trackingContext) }
        rows.add(rowMap)
    }

    fun clearData() {
        rows.clear()
        dataFrame = null
    }

    companion object {
        val RUN_NUMBER = NestedDataFrameColumn("RunNumber",
            macroExtractor = { _, ctx -> ctx.runNumber },
            microExtractor = { _, ctx -> ctx.runNumber }
        )
        val LEVEL = NestedDataFrameColumn("Level",
            macroExtractor = { _, _ -> "MACRO" },
            microExtractor = { _, _ -> "MICRO" }
        )
        val MACRO_ITER = NestedDataFrameColumn("MacroIter",
            macroExtractor = { snap, _ -> snap.iterationNumber },
            microExtractor = { _, ctx -> ctx.macroIteration }
        )
        val MICRO_ITER = NestedDataFrameColumn("MicroIter",
            macroExtractor = { _, _ -> null },
            microExtractor = { snap, _ -> snap.iterationNumber }
        )
        val EST_OBJ_VALUE = NestedDataFrameColumn("EstimatedObjValue",
            macroExtractor = { snap, _ -> snap.estimatedObjFncValue },
            microExtractor = { snap, _ -> snap.estimatedObjFncValue }
        )

        val defaultColumns = listOf(RUN_NUMBER, LEVEL, MACRO_ITER, MICRO_ITER, EST_OBJ_VALUE)
    }
}