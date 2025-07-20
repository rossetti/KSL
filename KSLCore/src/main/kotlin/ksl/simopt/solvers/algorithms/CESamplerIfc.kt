package ksl.simopt.solvers.algorithms

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.MVSampleIfc

/**
 *  An interface to define the sampling mechanism used within
 *  the Cross-Entropy method.  Implementors of this interface
 *  are responsible for generating samples from the cross-entropy
 *  "distribution", updating the parameters based on observations of
 *  the elite samples, and determining if the sampling distribution
 *  has degenerated (converged).
 */
interface CESamplerIfc : MVSampleIfc, RNStreamControlIfc {

    /**
     *  The problem definition associated with the sampling process.
     */
    val problemDefinition: ProblemDefinition

    /**
     *  The stream provider associated with the sampling process.
     */
    val streamProvider: RNStreamProviderIfc

    /**
     * The random number stream associated with the sampling process.
     */
    val rnStream: RNStreamIfc

    /**
     *  The assigned stream number associated with the sampling process.
     */
    val streamNumber: Int

    /** The underlying parameters of the sampling mechanism should be updated.
     *  Implementors need to handle the edge case of less than 2 elites.
     *
     *  @param elites a sample containing the generated population
     *  that meet what is considered elite performance.
     */
    fun updateParameters(elites: List<DoubleArray>)

    /**
     *  @return the current values of the parameters associated with the
     *  sampling mechanism
     */
    fun parameters(): DoubleArray

    /**
     * This function should be used to specify the initial values
     * of the underlying sampling mechanism.
     * @param values the initial values of the parameters for the underlying sampling
     * mechanism.
     */
    fun initializeParameters(values: DoubleArray)

    /**
     *  @return true if the parameters of the underlying sampling mechanism
     *  are considered converged (i.e., that the cross-entropy distribution
     *  has converged to a degenerate distribution centered on the recommended
     *  parameters, which represent the solution).
     */
    fun hasConverged(): Boolean

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    override fun resetStartStream() {
        rnStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        rnStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        rnStream.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = rnStream.antithetic
        set(value) {
            rnStream.antithetic = value
        }
}