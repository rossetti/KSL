package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  A [SimulationOracleIfc] over a response function instead of a discrete-event simulation
 *  model. Everything above the oracle seam — caching, common random numbers, solution
 *  merging, the solvers, and the concurrent execution substrate — is oracle-agnostic, so
 *  a cheap synthetic problem (noisy test function, static Monte Carlo model) evaluated
 *  through this class exercises exactly the same machinery as an expensive simulation.
 *
 *  Replication semantics mirror the simulation case and `ksl.utilities.mcintegration`'s
 *  macro/micro vocabulary: an evaluation request asks for a number of REPLICATIONS at a
 *  design point; one replication is one statistical observation of the responses — the
 *  average of [microRepSampleSize] calls of the response function (micro replications).
 *  With the default of one micro replication per replication, an observation is a single
 *  raw evaluation; larger micro samples give observations the averaged, near-normal
 *  character of a simulation replication's within-replication average. The returned
 *  [ResponseMap] summarizes across replications (average, variance, count equal to the
 *  requested replications), exactly as the simulation providers summarize replication
 *  data. Note that the budget consumed per replication is then microRepSampleSize raw
 *  evaluations — studies should report both when the micro sample is not one.
 *
 *  Stream discipline follows the simulation model pattern: the oracle owns a stream
 *  provider, builds its response function against that provider at construction (so all
 *  of the function's streams exist before any positioning — the
 *  [ResponseFunctionBuilderIfc] contract), and positions ALL of the provider's streams
 *  the way `SimulationProvider` positions a reused model — absolutely, per point (reset
 *  to the streams' origins, then advance to the point's sub-stream block computed by the
 *  [StreamTapePolicy]) and one fresh sub-stream per replication. Micro replications
 *  within a replication draw consecutively on the replication's sub-stream, like events
 *  within a simulation replication. Independent points therefore draw from
 *  non-overlapping blocks, the CRN option makes the points of a request share a block
 *  (paired draws, per stream — a function with a dedicated stream per randomness source
 *  gets source-synchronized CRN), and a per-member initial tape position gives
 *  concurrently executing members disjoint randomness. Results depend only on the tape
 *  positions, never on request order or grouping.
 *
 *  Contract enforcement: if the response function requests a NEW stream from the
 *  provider during evaluation (violating construction-time acquisition), the oracle
 *  throws an IllegalStateException naming the violation rather than continuing with a
 *  stream that positioning never reached.
 *
 *  Failure mapping matches provider semantics per point: a micro replication that
 *  throws, omits a requested response, or produces a non-finite value maps that point to
 *  a failed Result; other points in the request are unaffected.
 *
 *  Not thread-safe, by design — the same contract as `SimulationProvider`. Concurrent
 *  execution gives each member its own oracle instance (fresh provider, fresh function,
 *  distinct tape offset).
 *
 *  @param modelIdentifier the identifier that evaluation requests must carry to be served
 *  by this oracle; plays the role of the model identifier of a simulation model
 *  @param responseNames the names of the responses the response function produces; requests
 *  may ask for any subset (an empty request set means all of them)
 *  @param responseFunctionBuilder builds this oracle's response function against the
 *  oracle's stream provider, acquiring all randomness at construction
 *  @param microRepSampleSize the number of response-function evaluations averaged into
 *  one replication (one observation); the default of 1 makes an observation a single raw
 *  evaluation. Must be at least 1.
 *  @param streamProvider the provider this oracle owns and positions; defaults to a
 *  fresh provider so that identically configured oracles reproduce each other exactly
 *  @param streamTapePolicy the persistent sub-stream tape; supply one with a non-zero
 *  initial position to give a concurrent member its own block of randomness
 */
class ResponseFunctionOracle @JvmOverloads constructor(
    val modelIdentifier: String,
    val responseNames: Set<String>,
    responseFunctionBuilder: ResponseFunctionBuilderIfc,
    val microRepSampleSize: Int = 1,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    private val streamTapePolicy: StreamTapePolicy = StreamTapePolicy()
) : SimulationOracleIfc {

    init {
        require(modelIdentifier.isNotBlank()) { "The model identifier must not be blank" }
        require(responseNames.isNotEmpty()) { "At least one response name must be specified" }
        for (name in responseNames) {
            require(name.isNotBlank()) { "Response names must not be blank" }
        }
        require(microRepSampleSize >= 1) { "The micro replication sample size must be >= 1" }
    }

    private val myStreamProvider: RNStreamProviderIfc = streamProvider

    // built against the oracle's provider so every stream exists before positioning
    private val myResponseFunction: ResponseFunctionIfc = responseFunctionBuilder.build(streamProvider)

    // the number of streams the function acquired at construction; growth after this
    // point is a contract violation (a deterministic function may acquire none)
    private val myProvidedStreamCount: Int = streamProvider.lastRNStreamNumber()

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        require(modelIdentifier == evaluationRequest.modelIdentifier) {
            "The model identifier of the request (${evaluationRequest.modelIdentifier}) must match " +
                    "the oracle's model identifier ($modelIdentifier)."
        }
        val modelInputsList = evaluationRequest.modelInputs
        val advances = streamTapePolicy.advancesFor(modelInputsList, evaluationRequest.crnOption)
        val allResults = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for ((index, modelInputs) in modelInputsList.withIndex()) {
            // Position every stream of the provider absolutely before each point: reset to
            // the streams' origins, then jump to the point's tape block. This mirrors how
            // the model runner positions a reused model's streams, so consecutive requests
            // draw fresh sub-streams, CRN points share a block, and a mid-point failure
            // cannot contaminate the next point's randomness.
            myStreamProvider.resetAllStreamsToStart()
            myStreamProvider.advanceAllStreamsBySubStreams(advances[index].toLong())
            allResults[modelInputs] = evaluatePoint(modelInputs)
        }
        return allResults
    }

    private fun evaluatePoint(modelInputs: ModelInputs): Result<ResponseMap> {
        val requested: Set<String> = modelInputs.responseNames.ifEmpty { responseNames }
        require(responseNames.containsAll(requested)) {
            "The response function for $modelIdentifier does not produce responses " +
                    "named ${requested - responseNames}"
        }
        val numReplications = modelInputs.numReplications
        val samples: Map<String, DoubleArray> = requested.associateWith { DoubleArray(numReplications) }
        for (repIndex in 0 until numReplications) {
            if (repIndex > 0) {
                // each replication (observation) starts on a fresh sub-stream of every
                // provider stream, exactly like model replications
                myStreamProvider.advanceAllStreamsToNextSubStream()
            }
            // one replication = the average of microRepSampleSize micro replications,
            // drawn consecutively on this replication's sub-stream
            for (microIndex in 0 until microRepSampleSize) {
                val draws = try {
                    myResponseFunction.replication(modelInputs.inputs)
                } catch (e: Exception) {
                    return Result.failure(e)
                }
                checkStreamAcquisitionContract(repIndex)
                for (name in requested) {
                    val value = draws[name]
                        ?: return Result.failure(
                            IllegalStateException(
                                "Replication ${repIndex + 1} for model $modelIdentifier did not " +
                                        "produce the response named '$name'."
                            )
                        )
                    if (!value.isFinite()) {
                        return Result.failure(
                            IllegalStateException(
                                "Replication ${repIndex + 1} for model $modelIdentifier produced a " +
                                        "non-finite value ($value) for the response named '$name'."
                            )
                        )
                    }
                    samples[name]!![repIndex] += value / microRepSampleSize
                }
            }
        }
        val responseMap = ResponseMap(modelIdentifier, requested)
        for ((name, data) in samples) {
            responseMap.add(EstimatedResponse(name, data))
        }
        return Result.success(responseMap)
    }

    private fun checkStreamAcquisitionContract(repIndex: Int) {
        check(myStreamProvider.lastRNStreamNumber() == myProvidedStreamCount) {
            "The response function for $modelIdentifier requested a new stream from the " +
                    "provider during replication ${repIndex + 1}. All streams must be acquired " +
                    "at construction (the ResponseFunctionBuilderIfc contract); a stream created " +
                    "mid-evaluation is never positioned, which would silently break common " +
                    "random numbers and reproducibility."
        }
    }
}
