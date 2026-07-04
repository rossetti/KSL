package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  One observation of a single response at a design point — the single-response form of
 *  [ResponseFunctionIfc], in the style of ksl.utilities.mcintegration.MCReplicationIfc
 *  extended with design-point inputs. The same component contract applies: acquire all
 *  randomness at construction from the provider the instance was built against, and be
 *  pure apart from consuming those streams.
 */
fun interface MCReplicationFunctionIfc {

    /**
     *  Makes one observation of the response at the supplied design point.
     *
     *  @param inputs the input (design point) values, keyed by input name
     *  @return the observed value of the response
     */
    fun replication(inputs: Map<String, Double>): Double
}

/**
 *  A convenience adaptor: a single-response [SimulationOracleIfc] over a static Monte
 *  Carlo replication function. All behavior (macro/micro replication semantics,
 *  provider-wide stream positioning, CRN, contract enforcement, failure mapping, thread
 *  confinement) is delegated to a [ResponseFunctionOracle] over the single response.
 *
 *  @param modelIdentifier the identifier that evaluation requests must carry
 *  @param responseName the name of the single response the function observes
 *  @param replicationFunctionBuilder builds a fresh single-response function against the
 *  oracle's stream provider, acquiring all randomness at construction
 *  @param microRepSampleSize the number of function evaluations averaged into one
 *  replication (observation); defaults to 1
 *  @param streamProvider the provider the oracle owns and positions; defaults to a fresh
 *  provider so identically configured oracles reproduce each other exactly
 *  @param streamTapePolicy the persistent sub-stream tape; supply one with a non-zero
 *  initial position to give a concurrent member its own block of randomness
 */
class MCReplicationOracle @JvmOverloads constructor(
    val modelIdentifier: String,
    val responseName: String,
    replicationFunctionBuilder: (streamProvider: RNStreamProviderIfc) -> MCReplicationFunctionIfc,
    microRepSampleSize: Int = 1,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    streamTapePolicy: StreamTapePolicy = StreamTapePolicy()
) : SimulationOracleIfc by ResponseFunctionOracle(
    modelIdentifier = modelIdentifier,
    responseNames = setOf(responseName),
    responseFunctionBuilder = ResponseFunctionBuilderIfc { provider ->
        val replicationFunction = replicationFunctionBuilder(provider)
        ResponseFunctionIfc { inputs -> mapOf(responseName to replicationFunction.replication(inputs)) }
    },
    microRepSampleSize = microRepSampleSize,
    streamProvider = streamProvider,
    streamTapePolicy = streamTapePolicy
)
