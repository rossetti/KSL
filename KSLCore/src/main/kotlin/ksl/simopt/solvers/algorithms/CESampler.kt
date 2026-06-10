package ksl.simopt.solvers.algorithms

import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.MVSampleIfc

/**
 *  Abstract base class for the sampling mechanism (the "reference distribution") used by the
 *  Cross-Entropy method. Sub-classes implement the distribution-specific behavior: generating a
 *  sample (via [MVSampleIfc]), updating the parameters from an elite sample, and reporting
 *  convergence. The Cross-Entropy method permits different reference distributions; [CENormalSampler]
 *  is the multivariate-normal default and currently the only implementation.
 *
 *  A sampler is self-sufficient: by default it owns a fresh provider and a stream, so it can be
 *  exercised standalone (for example, in tests) without a [CrossEntropySolver]. When a sampler is
 *  handed to a solver, the solver adopts it onto the solver's own stream provider, on a stream
 *  distinct from the solver's base stream. That adoption is the single point at which the two
 *  providers become the same. The adoption is performed only by the owning solver (it is
 *  module-internal), so client code cannot reassign a sampler's stream and short-circuit the process.
 *
 * @param problemDefinition the problem definition associated with the sampling process
 * @param streamNum the stream number used while the sampler is standalone (unattached); 0 (the
 * default) means the next available stream. It is superseded once the sampler is attached to a solver.
 * @param streamProvider the provider used while the sampler is standalone; defaults to a fresh
 * RNStreamProvider so the sampler has its own streams. It is replaced by the solver's provider on
 * attachment.
 */
abstract class CESampler(
    val problemDefinition: ProblemDefinition,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
) : MVSampleIfc, RNStreamControlIfc {

    /**
     *  The stream provider currently backing this sampler. Initially the sampler's own provider;
     *  replaced by the owning solver's provider when the sampler is attached.
     */
    var streamProvider: RNStreamProviderIfc = streamProvider
        private set

    /**
     * The random number stream used by the sampler. Initially drawn from the sampler's own provider;
     * replaced with a distinct stream from the solver's provider when the sampler is attached.
     */
    var rnStream: RNStreamIfc = streamProvider.rnStream(streamNum)
        private set

    /**
     *  The assigned stream number of the current stream within the current provider.
     */
    val streamNumber: Int
        get() = streamProvider.streamNumber(rnStream)

    /**
     *  Adopts this sampler onto the supplied provider, drawing a fresh (next available) stream so the
     *  sampler's stream is distinct from the owning solver's base stream. Called only by the
     *  CrossEntropySolver to which this sampler is attached; it is intentionally not part of the public
     *  surface, so clients cannot reassign the stream and bypass the cross-entropy process.
     */
    internal fun attachStreamProvider(provider: RNStreamProviderIfc) {
        streamProvider = provider
        rnStream = provider.nextRNStream()
    }

    /** The underlying parameters of the sampling mechanism should be updated. Implementors need to
     *  handle the edge case of fewer than 2 elites.
     *  @param elites a sample containing the generated population members that meet what is considered
     *  elite performance. */
    abstract fun updateParameters(elites: List<DoubleArray>)

    /** @return the current values of the parameters associated with the sampling mechanism */
    abstract fun parameters(): DoubleArray

    /** Specifies the initial values of the underlying sampling mechanism.
     *  @param values the initial values of the parameters for the underlying sampling mechanism. */
    abstract fun initializeParameters(values: DoubleArray)

    /** @return true if the parameters of the underlying sampling mechanism are considered converged
     *  (i.e., the cross-entropy distribution has degenerated onto the recommended parameters, which
     *  represent the solution). */
    abstract fun hasConverged(): Boolean

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
