package ksl.simopt.solvers.algorithms

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

    val streamProvider: RNStreamProviderIfc

    /**
     * rnStream provides a reference to the underlying stream of random numbers.
     */
    val rnStream: RNStreamIfc

    /**
     *  The assigned stream number for the generation process
     */
    val streamNumber: Int

    /** The underlying parameters of the sampling mechanism should be updated.
     *
     *  @param elites a sample containing the generated decision variables
     *  that meet what is considered elite performance.
     */
    fun updateParameters(elites: List<DoubleArray>)

    /**
     *  @return the current values of the solution associated with the
     *  sampling mechanism
     */
    fun solution(): DoubleArray

    /**
     *  Sets the initial value of the parameters for the sampler.
     */
    fun initialize(parameters: DoubleArray)

    /**
     *  @return true if the parameters of the underlying sampling mechanism
     *  are considered converged (i.e., that the cross-entropy distribution
     *  has converged to a degenerate distribution centered on the recommended
     *  parameters, which represent the solution.
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