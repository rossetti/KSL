package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  A convenience adaptor: a single-response [SimulationOracleIfc] over a static Monte
 *  Carlo replication function. This is the simopt counterpart of a sampler in the style
 *  of ksl.utilities.mcintegration.MCReplicationIfc — one noisy observation per
 *  replication — extended with the design-point inputs and an explicit stream so the
 *  problem can be optimized over and reproduced.
 *
 *  All behavior (stream-tape discipline, CRN semantics, failure mapping, thread
 *  confinement) is delegated to a [ResponseFunctionOracle] over the single response.
 *
 *  @param modelIdentifier the identifier that evaluation requests must carry
 *  @param responseName the name of the single response the sampler observes
 *  @param replicationFunction one noisy observation of the response at the design point,
 *  drawing all randomness from the supplied stream
 *  @param streamProvider supplies the stream the oracle consumes; defaults to a fresh
 *  provider so identically configured oracles reproduce each other exactly
 *  @param streamTapePolicy the persistent sub-stream tape; supply one with a non-zero
 *  initial position to give a concurrent member its own block of randomness
 */
class MCReplicationOracle @JvmOverloads constructor(
    val modelIdentifier: String,
    val responseName: String,
    replicationFunction: (inputs: Map<String, Double>, stream: RNStreamIfc) -> Double,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    streamTapePolicy: StreamTapePolicy = StreamTapePolicy()
) : SimulationOracleIfc by ResponseFunctionOracle(
    modelIdentifier = modelIdentifier,
    responseNames = setOf(responseName),
    responseFunction = ResponseFunctionIfc { inputs, stream ->
        mapOf(responseName to replicationFunction(inputs, stream))
    },
    streamProvider = streamProvider,
    streamTapePolicy = streamTapePolicy
)
