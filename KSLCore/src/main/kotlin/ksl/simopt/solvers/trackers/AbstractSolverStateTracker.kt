package ksl.simopt.solvers.trackers

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverStateSnapshot
import ksl.utilities.observers.Emitter

/**
 * An abstract base class designed to safely consume [SolverStateSnapshot] events
 * from a solver's IterationEmitter.
 * * It manages the lifecycle of the emitter connection and implements [AutoCloseable]
 * to ensure that observers are cleanly detached when tracking is complete.
 *
 * @param solver The solver whose iteration emitter we want to track.
 */
abstract class AbstractSolverStateTracker(
    private val solver: Solver // Assuming your Solver class exposes `iterationEmitter`
) : AutoCloseable {

    // Holds the active connection to the emitter so it can be detached later
    private var connection: Emitter.Connection? = null

    /**
     * Attaches this consumer to the solver's iteration emitter.
     * Begins funneling emitted snapshots to the [consume] template method.
     */
    fun startTracking() {
        if (connection == null) {
            connection = solver.iterationEmitter.attach { snapshot ->
                consume(snapshot)
            }
        }
    }

    /**
     * Stops tracking and detaches from the emitter.
     * Safely ignores multiple calls.
     */
    fun stopTracking() {
        connection?.let {
            solver.iterationEmitter.detach(it)
            connection = null
        }
    }

    /**
     * Fulfills the [AutoCloseable] contract.
     * Subclasses can override this to close their specific resources (like files or database connections),
     * but they MUST call super.close() to ensure the emitter is detached.
     */
    override fun close() {
        stopTracking()
    }

    /**
     * Template method that concrete subclasses must implement.
     * This defines HOW the specific tracker handles the snapshot data.
     *
     * @param snapshot The immutable state payload emitted by the solver.
     */
    protected abstract fun consume(snapshot: SolverStateSnapshot)
}