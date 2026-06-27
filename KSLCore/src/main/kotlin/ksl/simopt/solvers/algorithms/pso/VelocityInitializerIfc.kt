package ksl.simopt.solvers.algorithms.pso

/**
 *  Strategy interface for initializing a particle's velocity at the start of a run. Randomness is
 *  drawn through the supplied solver's single random number stream
 *  ([ParticleSwarmSolver.rnStream]).
 */
fun interface VelocityInitializerIfc {

    /**
     *  Produces an initial velocity for a particle.
     *
     *  @param position the particle's initial continuous position (problem input order)
     *  @param vMax the per-coordinate maximum speed (velocity clamp magnitude)
     *  @param pso the particle swarm solver requesting the initialization
     *  @return the initial velocity vector, the same length as [position]
     */
    fun initialVelocity(position: DoubleArray, vMax: DoubleArray, pso: ParticleSwarmSolver): DoubleArray
}

/**
 *  Initializes every particle's velocity to zero. This is the default and a common, stable choice:
 *  the swarm is initially driven only by the cognitive and social pulls.
 */
class ZeroVelocity : VelocityInitializerIfc {

    override fun initialVelocity(position: DoubleArray, vMax: DoubleArray, pso: ParticleSwarmSolver): DoubleArray =
        DoubleArray(position.size)

    override fun toString(): String = "ZeroVelocity()"
}

/**
 *  Initializes each velocity component uniformly on `[-vMax_d, +vMax_d]`. Coordinates with a
 *  non-positive maximum speed are initialized to zero.
 */
class UniformRandomVelocity : VelocityInitializerIfc {

    override fun initialVelocity(position: DoubleArray, vMax: DoubleArray, pso: ParticleSwarmSolver): DoubleArray {
        val rnStream = pso.rnStream
        return DoubleArray(position.size) { d ->
            if (vMax[d] > 0.0) rnStream.rUniform(-vMax[d], vMax[d]) else 0.0
        }
    }

    override fun toString(): String = "UniformRandomVelocity()"
}
