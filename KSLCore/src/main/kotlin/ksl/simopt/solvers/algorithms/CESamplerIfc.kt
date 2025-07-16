package ksl.simopt.solvers.algorithms

import ksl.utilities.random.rvariable.MVSampleIfc

/**
 *  An interface to define the sampling mechanism used within
 *  the Cross-Entropy method.  Implementors of this interface
 *  are responsible for generating samples from the cross-entropy
 *  "distribution", updating the parameters based on observations of
 *  the elite samples, and determining if the sampling distribution
 *  has degenerated (converged).
 */
interface CESamplerIfc : MVSampleIfc {

    /** The underlying parameters of the sampling mechanism should be updated.
     *
     *  @param elites a sample containing the generated decision variables
     *  that meet what is considered elite performance.
     */
    fun updateParameters(elites: List<DoubleArray>)

    /**
     *  @return the current values of the parameters associated with the
     *  sampling mechanism
     */
    fun parameters(): DoubleArray

    /**
     *  @return true if the parameters of the underlying sampling mechanism
     *  are considered converged (i.e., that the cross-entropy distribution
     *  has converged to a degenerate distribution centered on the recommended
     *  parameters.
     */
    fun hasConverged(): Boolean
}