package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.simopt.solvers.SolverStatus
import ksl.utilities.observers.Emitter

/**
 * An abstract base class that autonomously tracks a [Solver]'s state and lifecycle.
 * It remains permanently attached to the solver, allowing it to record multiple
 * subsequent runs seamlessly without user intervention.
 *
 * @param solver The solver whose emitters we want to track.
 */
abstract class AbstractSolverStateTracker(
    protected val solver: Solver
) {
    /** * A user-defined label for the current experiment run.
     * Users can mutate this between solver runs to semantically group their data.
     */
    var experimentName: String = "DefaultExperiment"

    // Autonomously managed by the tracker based on lifecycle events
    private var currentRunNumber: Int = 0

    /** * Generates the current context to be passed down to column extractors.
     */
    protected val trackingContext: TrackingContext
        get() = TrackingContext(currentRunNumber, experimentName)

    private var dataConnection: Emitter.Connection? = null
    private var lifecycleConnection: Emitter.Connection? = null

    /**
     * Attaches this tracker to the solver's emitters.
     */
    @Suppress("unused")
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
            // Autonomously increment the run counter when a new run begins
            if (status == SolverStatus.INITIALIZED) {
                currentRunNumber++
            }

            // Pass the event to subclasses
            onLifecycleEvent(status)
        }
    }

    /**
     * Safely detaches from the solver's emitters.
     * In the continuous tracking architecture, this is usually only called manually
     * if the user explicitly wants to silence the tracker.
     */
    fun stopTracking() {
        dataConnection?.let { solver.iterationEmitter.detach(it) }
        lifecycleConnection?.let { solver.lifeCycleEmitter.detach(it) }

        dataConnection = null
        lifecycleConnection = null
    }

    /**
     * Defines how the concrete tracker handles the mathematical state payload.
     */
    protected abstract fun consume(snapshot: SolverStateSnapshot)

    /**
     * Optional hook for subclasses to react to lifecycle changes
     * (e.g., opening/closing files on INITIALIZED/COMPLETED).
     */
    protected open fun onLifecycleEvent(status: SolverStatus) {}
}