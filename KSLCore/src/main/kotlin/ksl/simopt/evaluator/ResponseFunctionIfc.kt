package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  One observation of every response at a design point. Implementations are small
 *  components in the standard KSL style: they acquire ALL of their randomness at
 *  construction time from the stream provider they are built against (typically as
 *  random variables with explicit stream numbers, exactly like a simulation model's
 *  random variables), and each call to the replication function makes one noisy
 *  observation by drawing from those pre-acquired streams.
 *
 *  The construction-time acquisition rule is the reproducibility contract: the oracle
 *  that runs the function positions every stream of the provider (reset plus sub-stream
 *  advances) before observations are made, which only reaches streams that already
 *  exist. Requesting a new stream from the provider during a replication is a contract
 *  violation — the oracle detects it and fails loudly rather than silently breaking
 *  common random numbers. Apart from consuming its own streams, an implementation must
 *  be pure: no other mutable state, so that repeated calls with identically positioned
 *  streams reproduce exactly.
 *
 *  Instances are created per concurrent member via [ResponseFunctionBuilderIfc] — the
 *  same fresh-instance-per-member pattern that `ksl.simulation.ModelBuilderIfc` provides
 *  for simulation models. An instance is used by one member at a time (thread-confined).
 *
 *  The [ResponseFunctionOracle] adapts an implementation to the [SimulationOracleIfc]
 *  seam so that solvers can optimize over it exactly as they optimize over a
 *  discrete-event simulation model.
 */
fun interface ResponseFunctionIfc {

    /**
     *  Makes one observation of the response function at the supplied design point,
     *  drawing all randomness from the streams acquired at construction.
     *
     *  @param inputs the input (design point) values, keyed by input name
     *  @return the observed value for each response name produced by this function
     */
    fun replication(inputs: Map<String, Double>): Map<String, Double>
}

/**
 *  Builds a fresh [ResponseFunctionIfc] bound to the supplied stream provider — the
 *  response-function counterpart of `ksl.simulation.ModelBuilderIfc`. Concurrent
 *  execution creates one instance per member, each against that member's own
 *  identically seeded provider, so members are isolated while remaining reproducible.
 *
 *  Implementations must return a NEW instance on every call, with all of its randomness
 *  acquired from the supplied provider before the call returns (random variables
 *  constructed with explicit stream numbers are the natural form). Builders are invoked
 *  on worker threads and must be safe to call concurrently.
 */
fun interface ResponseFunctionBuilderIfc {

    /**
     *  Builds a fresh response function whose randomness comes entirely from the
     *  supplied provider.
     *
     *  @param streamProvider the provider the instance must acquire all of its streams
     *  from, at construction time
     *  @return the newly built response function
     */
    fun build(streamProvider: RNStreamProviderIfc): ResponseFunctionIfc
}
