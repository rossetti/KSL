package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus
import ksl.utilities.observers.Emitter

abstract class AbstractNestedSolverStateTracker(
    protected val macroSolver: Solver,
    protected val microSolver: Solver
) {
    var experimentName: String = "DefaultExperiment"
    private var currentRunNumber: Int = 0
    protected var currentMacroIteration: Int = 0

    // The context passed down to column extractors for BOTH macro and micro rows
    protected val trackingContext: TrackingContext
        get() = TrackingContext(currentRunNumber, experimentName, currentMacroIteration)

    private var macroLifecycleConnection: Emitter.Connection? = null
    private var macroDataConnection: Emitter.Connection? = null
    private var microDataConnection: Emitter.Connection? = null

    fun startTracking() {
        if (macroLifecycleConnection != null) return

        // 1. MACRO Lifecycle: Controls runs and file locking
        macroLifecycleConnection = macroSolver.lifeCycleEmitter.attach { status ->
            if (status == SolverStatus.INITIALIZED) {
                currentRunNumber++
                currentMacroIteration = 0
            }
            onMacroLifecycleEvent(status)
        }

        // 2. MACRO Data
        macroDataConnection = macroSolver.iterationEmitter.attach { outerSnapshot ->
            currentMacroIteration = outerSnapshot.iterationNumber
            consumeMacro(outerSnapshot)
        }

        // 3. MICRO Data
        microDataConnection = microSolver.iterationEmitter.attach { innerSnapshot ->
            consumeMicro(innerSnapshot)
        }
    }

    fun stopTracking() {
        macroLifecycleConnection?.let { macroSolver.lifeCycleEmitter.detach(it) }
        macroDataConnection?.let { macroSolver.iterationEmitter.detach(it) }
        microDataConnection?.let { microSolver.iterationEmitter.detach(it) }
        macroLifecycleConnection = null
        macroDataConnection = null
        microDataConnection = null
    }

    protected abstract fun consumeMacro(outerSnapshot: SolverStateSnapshot)
    protected abstract fun consumeMicro(innerSnapshot: SolverStateSnapshot)
    protected open fun onMacroLifecycleEvent(status: SolverStatus) {}
}