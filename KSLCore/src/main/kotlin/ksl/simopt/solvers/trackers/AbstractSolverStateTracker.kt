package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus
import ksl.utilities.observers.Emitter

/**
 * An abstract base class that safely tracks a [Solver]'s state and lifecycle.
 * It automatically detaches its listeners and cleans up resources when the solver
 * emits a terminal lifecycle event ([SolverStatus.COMPLETED] or [SolverStatus.ERROR]).
 *
 * @param solver The solver whose emitters we want to track.
 */
abstract class AbstractSolverStateTracker(
    protected val solver: Solver
) : AutoCloseable {

    private var dataConnection: Emitter.Connection? = null
    private var lifecycleConnection: Emitter.Connection? = null

    /**
     * Attaches this tracker to the solver's emitters.
     */
    fun startTracking() {
        if (dataConnection != null || lifecycleConnection != null) {
            return // Already tracking
        }

        // 1. Listen for the mathematical state snapshots
        dataConnection = solver.iterationEmitter.attach { snapshot ->
            consume(snapshot)
        }

        // 2. Listen for execution lifecycle changes
        lifecycleConnection = solver.lifeCycleEmitter.attach { status ->
            // Pass the event to subclasses in case they want to react to INITIALIZED or STARTED
            onLifecycleEvent(status)

            // Self-terminate on completion or crash
            if (status == SolverStatus.COMPLETED || status == SolverStatus.ERROR) {
                close()
            }
        }
    }

    /**
     * Safely detaches from the solver's emitters and cleans up resources.
     * This is called automatically when the solver finishes or crashes,
     * but can also be called manually via Kotlin's `use` block.
     */
    override fun close() {
        dataConnection?.let { solver.iterationEmitter.detach(it) }
        lifecycleConnection?.let { solver.lifeCycleEmitter.detach(it) }

        dataConnection = null
        lifecycleConnection = null

        // Delegate to the subclass to close files, database connections, etc.
        onCloseResources()
    }

    /**
     * Defines how the concrete tracker handles the mathematical state payload.
     */
    protected abstract fun consume(snapshot: SolverStateSnapshot)

    /**
     * Optional hook for subclasses to react to lifecycle changes
     * (e.g., drawing an empty chart on INITIALIZED).
     */
    protected open fun onLifecycleEvent(status: SolverStatus) {}

    /**
     * Optional hook for subclasses to close their specific resources.
     */
    protected open fun onCloseResources() {}
}