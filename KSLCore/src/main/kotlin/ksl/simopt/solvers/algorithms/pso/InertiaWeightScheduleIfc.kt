package ksl.simopt.solvers.algorithms.pso

/**
 *  Defines the inertia-weight schedule used by particle swarm optimization. The inertia weight
 *  scales the contribution of a particle's previous velocity at each iteration, trading off
 *  exploration (large weight) for exploitation (small weight) as the search progresses. This is
 *  the PSO analogue of the simulated-annealing cooling schedule.
 */
interface InertiaWeightScheduleIfc {

    /** The starting inertia weight (the value at iteration 0). Must be positive. */
    var initialInertia: Double

    /**
     *  Computes the inertia weight for the given iteration.
     *
     *  @param iteration the current iteration number (>= 0)
     *  @return the inertia weight w(t)
     */
    fun nextInertia(iteration: Int): Double
}

/**
 *  Abstract base class for inertia-weight schedules.
 *
 *  @param initialInertia the starting inertia weight. Must be finite and positive.
 */
abstract class InertiaWeightSchedule(initialInertia: Double) : InertiaWeightScheduleIfc {
    init {
        require(initialInertia.isFinite()) { "The initial inertia must be finite." }
        require(initialInertia > 0.0) { "The initial inertia must be positive." }
    }

    override var initialInertia: Double = initialInertia
        set(value) {
            require(value.isFinite()) { "The initial inertia must be finite." }
            require(value > 0.0) { "The initial inertia must be positive." }
            field = value
        }
}

/**
 *  A constant inertia-weight schedule: the inertia weight is the same at every iteration.
 *
 *  @param initialInertia the (constant) inertia weight. Defaults to [ParticleSwarmSolver.defaultInitialInertia].
 */
class ConstantInertia(
    initialInertia: Double = ParticleSwarmSolver.defaultInitialInertia
) : InertiaWeightSchedule(initialInertia) {

    override fun nextInertia(iteration: Int): Double = initialInertia

    override fun toString(): String = "ConstantInertia(inertia=$initialInertia)"
}

/**
 *  A linearly decreasing inertia-weight schedule. The inertia weight decreases linearly from
 *  [initialInertia] toward [finalInertia] over [horizon] iterations, then is held at
 *  [finalInertia]. This is the most common PSO inertia schedule.
 *
 *  @param initialInertia the starting inertia weight. Defaults to [ParticleSwarmSolver.defaultInitialInertia].
 *  @param finalInertia the floor inertia weight reached at [horizon]. Must be positive and no
 *  greater than [initialInertia]. Defaults to [ParticleSwarmSolver.defaultFinalInertia].
 *  @param horizon the number of iterations over which the decrease occurs. Must be >= 1. Defaults
 *  to [ParticleSwarmSolver.psoDefaultMaxIterations]; set it to match the solver's maximum number
 *  of iterations for a full decrease across a run.
 */
class LinearDecreasingInertia(
    initialInertia: Double = ParticleSwarmSolver.defaultInitialInertia,
    finalInertia: Double = ParticleSwarmSolver.defaultFinalInertia,
    horizon: Int = ParticleSwarmSolver.psoDefaultMaxIterations
) : InertiaWeightSchedule(initialInertia) {

    init {
        require(finalInertia > 0.0) { "The final inertia must be positive." }
        require(finalInertia <= initialInertia) { "The final inertia must be <= the initial inertia." }
        require(horizon >= 1) { "The horizon must be >= 1." }
    }

    var finalInertia: Double = finalInertia
        set(value) {
            require(value > 0.0) { "The final inertia must be positive." }
            field = value
        }

    var horizon: Int = horizon
        set(value) {
            require(value >= 1) { "The horizon must be >= 1." }
            field = value
        }

    override fun nextInertia(iteration: Int): Double {
        val fraction = (iteration.toDouble() / horizon).coerceIn(0.0, 1.0)
        val w = initialInertia - (initialInertia - finalInertia) * fraction
        return maxOf(w, finalInertia)
    }

    override fun toString(): String =
        "LinearDecreasingInertia(initial=$initialInertia, final=$finalInertia, horizon=$horizon)"
}
