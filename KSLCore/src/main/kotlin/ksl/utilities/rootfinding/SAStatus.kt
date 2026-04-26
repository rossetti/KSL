package ksl.utilities.rootfinding

/**
 *  Represents the distinct lifecycle states of a [StochasticApproximationRootFinder]
 *  run. Emitted through [SALifeCycleEmitterIfc.lifeCycleEmitter] at each transition
 *  so that subscribers can branch on outcome type without inspecting string messages.
 */
enum class SAStatus {
    /** Emitted at the end of initializeIterations() once all algorithm state is seeded. */
    INITIALIZED,
    /** Emitted before stop() is called when stoppingCriteria drops below desiredPrecision. */
    CONVERGED,
    /** Emitted in endIterations() when maxIterations is exhausted without convergence. */
    EXHAUSTED,
    /**
     *  Emitted in advanceAlgorithmState() when a bounce is attempted but currentX is
     *  already at the violated boundary. A hard stop follows immediately.
     */
    DEGENERATE_BOUNCE
}