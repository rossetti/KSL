package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamIfc

/**
 *  One noisy draw of every response at a design point. Implementations compute a single
 *  replication of a response function (a static Monte Carlo evaluation) and return the
 *  observed value for each named response.
 *
 *  Implementations must be pure apart from consuming randomness from the supplied stream:
 *  the same inputs with the stream in the same state must produce the same values. All
 *  randomness must be drawn from the supplied stream — never from a shared or global
 *  stream — because that is what makes common random numbers, stream-tape positioning,
 *  and whole-experiment reproducibility work. The stream is thread-confined for the
 *  duration of the call.
 *
 *  The [ResponseFunctionOracle] adapts an implementation of this interface to the
 *  [SimulationOracleIfc] seam so that solvers can optimize over it exactly as they
 *  optimize over a discrete-event simulation model.
 */
fun interface ResponseFunctionIfc {

    /**
     *  Computes one replication of the response function at the supplied design point.
     *
     *  @param inputs the input (design point) values, keyed by input name
     *  @param stream the stream from which all randomness for this replication must be drawn
     *  @return the observed value for each response name produced by this function
     */
    fun replication(inputs: Map<String, Double>, stream: RNStreamIfc): Map<String, Double>
}
