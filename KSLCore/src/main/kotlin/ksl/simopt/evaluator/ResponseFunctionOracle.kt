package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  A [SimulationOracleIfc] over a response function instead of a discrete-event simulation
 *  model. Everything above the oracle seam — caching, common random numbers, solution
 *  merging, the solvers, and the concurrent execution substrate — is oracle-agnostic, so
 *  a cheap synthetic problem (noisy test function, static Monte Carlo model) evaluated
 *  through this class exercises exactly the same machinery as an expensive simulation.
 *
 *  For each requested point, the oracle runs the point's number of replications of the
 *  [ResponseFunctionIfc] and fills the returned [ResponseMap] with the summary statistics
 *  (average, variance, count) of each response — the same summarization the simulation
 *  providers apply to replication data.
 *
 *  Stream discipline reuses [StreamTapePolicy] verbatim, mirroring `SimulationProvider`:
 *  before each point the stream is positioned absolutely (reset to the stream's origin,
 *  then advanced to the point's sub-stream block), each replication of a point runs on its
 *  own consecutive sub-stream, independent points draw from non-overlapping blocks, the
 *  CRN option makes the points of a request share a block (paired draws), and a per-member
 *  initial tape position gives concurrently executing members disjoint randomness. The
 *  reproducibility semantics match the simulation providers in structure: results depend
 *  only on the tape positions, never on request order or grouping.
 *
 *  Failure mapping matches provider semantics per point: a replication that throws, omits
 *  a requested response, or produces a non-finite value maps that point to a failed
 *  Result; other points in the request are unaffected. Requesting a response name the
 *  function does not produce is a programming error and throws.
 *
 *  Not thread-safe, by design — the same contract as `SimulationProvider`. Concurrent
 *  execution gives each member its own oracle instance with a distinct tape offset.
 *
 *  @param modelIdentifier the identifier that evaluation requests must carry to be served
 *  by this oracle; plays the role of the model identifier of a simulation model
 *  @param responseNames the names of the responses the response function produces; requests
 *  may ask for any subset (an empty request set means all of them)
 *  @param responseFunction computes one replication of every response at a design point
 *  @param streamProvider supplies the single stream the oracle consumes; defaults to a
 *  fresh provider so that identically configured oracles reproduce each other exactly
 *  @param streamTapePolicy the persistent sub-stream tape; supply one with a non-zero
 *  initial position to give a concurrent member its own block of randomness
 */
class ResponseFunctionOracle @JvmOverloads constructor(
    val modelIdentifier: String,
    val responseNames: Set<String>,
    private val responseFunction: ResponseFunctionIfc,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    private val streamTapePolicy: StreamTapePolicy = StreamTapePolicy()
) : SimulationOracleIfc {

    init {
        require(modelIdentifier.isNotBlank()) { "The model identifier must not be blank" }
        require(responseNames.isNotEmpty()) { "At least one response name must be specified" }
        for (name in responseNames) {
            require(name.isNotBlank()) { "Response names must not be blank" }
        }
    }

    private val myStream: RNStreamIfc = streamProvider.rnStream(1)

    override fun simulate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Result<ResponseMap>> {
        require(modelIdentifier == evaluationRequest.modelIdentifier) {
            "The model identifier of the request (${evaluationRequest.modelIdentifier}) must match " +
                    "the oracle's model identifier ($modelIdentifier)."
        }
        val modelInputsList = evaluationRequest.modelInputs
        val advances = streamTapePolicy.advancesFor(modelInputsList, evaluationRequest.crnOption)
        val allResults = mutableMapOf<ModelInputs, Result<ResponseMap>>()
        for ((index, modelInputs) in modelInputsList.withIndex()) {
            // Position the stream absolutely before each point: reset to the stream's origin,
            // then jump to the point's tape block. This mirrors how SimulationProvider positions
            // a reused model, so consecutive requests draw fresh sub-streams, CRN points share a
            // block, and a mid-point failure cannot contaminate the next point's randomness.
            myStream.resetStartStream()
            myStream.advanceSubStreams(advances[index].toLong())
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
                // each replication runs on its own sub-stream, exactly like model replications
                myStream.advanceToNextSubStream()
            }
            val draws = try {
                responseFunction.replication(modelInputs.inputs, myStream)
            } catch (e: Exception) {
                return Result.failure(e)
            }
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
                samples[name]!![repIndex] = value
            }
        }
        val responseMap = ResponseMap(modelIdentifier, requested)
        for ((name, data) in samples) {
            responseMap.add(EstimatedResponse(name, data))
        }
        return Result.success(responseMap)
    }
}
