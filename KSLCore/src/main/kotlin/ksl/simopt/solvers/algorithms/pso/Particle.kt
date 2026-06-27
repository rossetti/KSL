package ksl.simopt.solvers.algorithms.pso

import ksl.simopt.evaluator.Solution

/**
 *  A mutable holder for the per-iteration state of a single particle in a particle swarm. A
 *  particle moves in continuous space: its [position] and [velocity] are off-grid coordinate
 *  vectors. The particle is evaluated at the granular point obtained by rounding [position] to the
 *  problem's granularity; that evaluated [Solution] is its [currentSolution]. The particle remembers
 *  its personal best [Solution] ([bestSolution]) and the continuous position at which it occurred
 *  ([bestPosition]).
 *
 *  Instances are created and managed only by [ParticleSwarmSolver]; the constructor is therefore
 *  module-internal.
 *
 *  @param position the continuous position vector (problem input order)
 *  @param velocity the continuous velocity vector (problem input order)
 */
class Particle internal constructor(
    var position: DoubleArray,
    var velocity: DoubleArray
) {
    /** The solution obtained by evaluating the particle at its current (granular) position. */
    lateinit var currentSolution: Solution

    /** The best solution this particle has personally found so far (its personal best). */
    lateinit var bestSolution: Solution

    /** The continuous position associated with the personal best [bestSolution]. */
    var bestPosition: DoubleArray = position.copyOf()
        private set

    /**
     *  Refreshes the personal best if the particle's current solution improves upon it (or if no
     *  personal best has been recorded yet), using the supplied comparator.
     */
    internal fun updatePersonalBest(comparator: Comparator<Solution>) {
        if (!::bestSolution.isInitialized || comparator.compare(currentSolution, bestSolution) < 0) {
            bestSolution = currentSolution
            bestPosition = position.copyOf()
        }
    }
}
