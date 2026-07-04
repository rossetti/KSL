package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.NormalRV
import ksl.utilities.statistics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the response-function oracle adaptor: summary-statistics correctness against
 * closed forms, macro/micro replication semantics, independent-vs-CRN stream semantics
 * (including source-synchronized CRN with multiple dedicated streams), tape continuity
 * across requests, per-member tape offsets, the construction-time stream-acquisition
 * contract, and per-point failure mapping.
 *
 * The stream-semantics tests compare the oracle's output exactly against hand-driven
 * reference streams (fresh default providers produce identical streams), so they assert
 * the positioning discipline bit-for-bit rather than probabilistically.
 */
class ResponseFunctionOracleTest {

    private companion object {
        const val MODEL_ID = "testResponseFn"
        const val OBJ = "objFn"
    }

    /** An oracle whose single response is one uniform draw per call from a stream
     *  acquired at construction — the most direct probe of the positioning discipline. */
    private fun uniformDrawOracle(
        microRepSampleSize: Int = 1,
        tapePolicy: StreamTapePolicy = StreamTapePolicy()
    ): ResponseFunctionOracle {
        return ResponseFunctionOracle(
            modelIdentifier = MODEL_ID,
            responseNames = setOf(OBJ),
            responseFunctionBuilder = ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1)
                ResponseFunctionIfc { _ -> mapOf(OBJ to stream.randU01()) }
            },
            microRepSampleSize = microRepSampleSize,
            streamTapePolicy = tapePolicy
        )
    }

    private fun point(x: Double, numReplications: Int): ModelInputs {
        return ModelInputs(MODEL_ID, numReplications, mapOf("x" to x))
    }

    /** The uniform draws the oracle must produce for a point positioned at
     *  startSubStream: replication r takes drawsPerReplication consecutive uniforms
     *  from sub-stream startSubStream + r of stream 1. */
    private fun referenceUniformDraws(
        startSubStream: Int,
        numReplications: Int,
        drawsPerReplication: Int = 1
    ): List<DoubleArray> {
        val stream = RNStreamProvider().rnStream(1)
        stream.resetStartStream()
        stream.advanceSubStreams(startSubStream.toLong())
        val out = mutableListOf<DoubleArray>()
        for (r in 0 until numReplications) {
            if (r > 0) {
                stream.advanceToNextSubStream()
            }
            out.add(DoubleArray(drawsPerReplication) { stream.randU01() })
        }
        return out
    }

    private fun averageOf(oracle: ResponseFunctionOracle, modelInputs: ModelInputs): Double {
        return oracle.simulate(EvaluationRequest(MODEL_ID, listOf(modelInputs)))
            .getValue(modelInputs).getOrThrow().getValue(OBJ).average
    }

    @Test
    @DisplayName("Summary statistics match closed forms for a deterministic multi-response function")
    fun statisticsMatchClosedForm() {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ, "twice"),
            ResponseFunctionBuilderIfc {
                ResponseFunctionIfc { inputs ->
                    val x = inputs.getValue("x")
                    mapOf(OBJ to x + 1.0, "twice" to 2.0 * x)
                }
            }
        )
        val p = point(3.0, 5)
        val responseMap = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow()
        val obj = responseMap.getValue(OBJ)
        assertEquals(4.0, obj.average)
        assertEquals(0.0, obj.variance)
        assertEquals(5.0, obj.count)
        assertEquals(6.0, responseMap.getValue("twice").average)
    }

    @Test
    @DisplayName("A single replication yields count 1 with NaN variance, like the DEDS providers")
    fun singleReplicationHasNaNVariance() {
        val oracle = uniformDrawOracle()
        val p = point(0.0, 1)
        val estimate = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow().getValue(OBJ)
        assertEquals(1.0, estimate.count)
        assertTrue(estimate.variance.isNaN())
    }

    @Test
    @DisplayName("One replication averages microRepSampleSize consecutive micro draws; count is replications")
    fun microReplicationsAverageIntoOneObservation() {
        val microSampleSize = 4
        val numReplications = 3
        val oracle = uniformDrawOracle(microRepSampleSize = microSampleSize)
        val p = point(0.0, numReplications)
        val estimate = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow().getValue(OBJ)
        assertEquals(numReplications.toDouble(), estimate.count)
        val reference = referenceUniformDraws(0, numReplications, drawsPerReplication = microSampleSize)
        val expectedObservations = reference.map { it.average() }.toDoubleArray()
        assertEquals(expectedObservations.statistics().average, estimate.average, 1e-12)
        assertEquals(expectedObservations.statistics().variance, estimate.variance, 1e-12)
    }

    @Test
    @DisplayName("Independent points draw from consecutive, non-overlapping sub-stream blocks")
    fun independentPointsDrawFromDisjointSubStreamBlocks() {
        val oracle = uniformDrawOracle()
        val p1 = point(1.0, 3)
        val p2 = point(2.0, 2)
        val results = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(p1, p2)))
        // point 1 occupies sub-streams 0..2; point 2 starts where point 1's block ends
        val expected1 = referenceUniformDraws(0, 3).map { it.single() }.toDoubleArray()
        val expected2 = referenceUniformDraws(3, 2).map { it.single() }.toDoubleArray()
        assertEquals(expected1.statistics().average, results.getValue(p1).getOrThrow().getValue(OBJ).average)
        assertEquals(expected2.statistics().average, results.getValue(p2).getOrThrow().getValue(OBJ).average)
    }

    @Test
    @DisplayName("CRN points share the same sub-stream block, producing paired draws")
    fun crnPointsShareTheSameSubStreamBlock() {
        val oracle = uniformDrawOracle()
        val p1 = point(1.0, 4)
        val p2 = point(2.0, 4)
        val request = EvaluationRequest(MODEL_ID, listOf(p1, p2), crnOption = true, cachingAllowed = false)
        val results = oracle.simulate(request)
        val avg1 = results.getValue(p1).getOrThrow().getValue(OBJ).average
        val avg2 = results.getValue(p2).getOrThrow().getValue(OBJ).average
        // the response function ignores the inputs, so paired draws mean identical averages
        assertEquals(avg1, avg2)
        val expected = referenceUniformDraws(0, 4).map { it.single() }.toDoubleArray()
        assertEquals(expected.statistics().average, avg1)
    }

    @Test
    @DisplayName("Multiple dedicated streams get source-synchronized CRN: per-source noise pairs exactly")
    fun multiSourceCrnPairsPerStream() {
        // y = x + normal noise (stream 1) + exponential shock (stream 2): under CRN both
        // points see identical noise from each source, so avg - x matches exactly
        fun makeOracle(): ResponseFunctionOracle {
            return ResponseFunctionOracle(
                MODEL_ID, setOf(OBJ),
                ResponseFunctionBuilderIfc { streamProvider ->
                    val noiseRV = NormalRV(0.0, 4.0, streamNum = 1, streamProvider = streamProvider)
                    val shockRV = ExponentialRV(2.0, streamNum = 2, streamProvider = streamProvider)
                    ResponseFunctionIfc { inputs ->
                        mapOf(OBJ to inputs.getValue("x") + noiseRV.value + shockRV.value)
                    }
                }
            )
        }
        val p1 = point(1.0, 6)
        val p2 = point(-4.0, 6)
        val request = EvaluationRequest(MODEL_ID, listOf(p1, p2), crnOption = true, cachingAllowed = false)
        val results = makeOracle().simulate(request)
        val avg1 = results.getValue(p1).getOrThrow().getValue(OBJ).average
        val avg2 = results.getValue(p2).getOrThrow().getValue(OBJ).average
        assertEquals(avg1 - 1.0, avg2 - (-4.0), 1e-12)
    }

    @Test
    @DisplayName("The tape advances continuously across requests: split requests equal one batched request")
    fun tapeAdvancesContinuouslyAcrossRequests() {
        val p1 = point(1.0, 3)
        val p2 = point(2.0, 2)
        val sequentialOracle = uniformDrawOracle()
        sequentialOracle.simulate(EvaluationRequest(MODEL_ID, listOf(p1)))
        val secondRequest = sequentialOracle.simulate(EvaluationRequest(MODEL_ID, listOf(p2)))
        val batchedOracle = uniformDrawOracle()
        val batched = batchedOracle.simulate(EvaluationRequest(MODEL_ID, listOf(p1, p2)))
        assertEquals(
            batched.getValue(p2).getOrThrow().getValue(OBJ).average,
            secondRequest.getValue(p2).getOrThrow().getValue(OBJ).average
        )
    }

    @Test
    @DisplayName("Per-member tape offsets give disjoint randomness; equal offsets reproduce exactly")
    fun memberTapeOffsetsYieldDisjointRandomness() {
        val p = point(1.0, 3)
        val member0Avg = averageOf(uniformDrawOracle(tapePolicy = StreamTapePolicy(initialPosition = 0)), p)
        val member1Avg = averageOf(uniformDrawOracle(tapePolicy = StreamTapePolicy(initialPosition = 1000)), p)
        assertNotEquals(member0Avg, member1Avg)
        val expected = referenceUniformDraws(1000, 3).map { it.single() }.toDoubleArray()
        assertEquals(expected.statistics().average, member1Avg)
        val member1AgainAvg = averageOf(uniformDrawOracle(tapePolicy = StreamTapePolicy(initialPosition = 1000)), p)
        assertEquals(member1Avg, member1AgainAvg)
    }

    @Test
    @DisplayName("Acquiring a new stream during evaluation violates the contract and fails loudly")
    fun lateStreamAcquisitionFailsLoudly() {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1)
                var callCount = 0
                ResponseFunctionIfc { _ ->
                    callCount++
                    // contract violation: a second stream requested mid-evaluation
                    val value = if (callCount > 1) {
                        streamProvider.rnStream(2).randU01()
                    } else {
                        stream.randU01()
                    }
                    mapOf(OBJ to value)
                }
            }
        )
        val exception = assertThrows<IllegalStateException> {
            oracle.simulate(EvaluationRequest(MODEL_ID, listOf(point(0.0, 3))))
        }
        assertTrue(exception.message!!.contains("new stream"))
    }

    @Test
    @DisplayName("A non-finite response value maps that point (and only that point) to failure")
    fun nonFiniteValueMapsThatPointToFailure() {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc {
                ResponseFunctionIfc { inputs ->
                    val x = inputs.getValue("x")
                    mapOf(OBJ to if (x > 0.0) Double.POSITIVE_INFINITY else x)
                }
            }
        )
        val bad = point(1.0, 2)
        val good = point(-1.0, 2)
        val results = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(bad, good)))
        assertTrue(results.getValue(bad).isFailure)
        assertTrue(results.getValue(good).isSuccess)
        assertEquals(-1.0, results.getValue(good).getOrThrow().getValue(OBJ).average)
    }

    @Test
    @DisplayName("An exception thrown by the response function maps that point to failure")
    fun thrownExceptionMapsThatPointToFailure() {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc {
                ResponseFunctionIfc { inputs ->
                    check(inputs.getValue("x") <= 0.0) { "injected replication failure" }
                    mapOf(OBJ to 0.0)
                }
            }
        )
        val bad = point(1.0, 2)
        val good = point(-1.0, 2)
        val results = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(bad, good)))
        assertTrue(results.getValue(bad).isFailure)
        assertTrue(results.getValue(good).isSuccess)
    }

    @Test
    @DisplayName("A replication that omits a declared response maps the point to failure")
    fun missingResponseMapsToFailure() {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ, "neverProduced"),
            ResponseFunctionBuilderIfc {
                ResponseFunctionIfc { _ -> mapOf(OBJ to 0.0) }
            }
        )
        val p = point(0.0, 2)
        val results = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(p)))
        assertTrue(results.getValue(p).isFailure)
    }

    @Test
    @DisplayName("Requesting a response name the function does not produce is a programming error")
    fun requestingUnknownResponseNameThrows() {
        val oracle = uniformDrawOracle()
        val mi = ModelInputs(MODEL_ID, 2, mapOf("x" to 1.0), setOf("noSuchResponse"))
        assertThrows<IllegalArgumentException> {
            oracle.simulate(EvaluationRequest(MODEL_ID, listOf(mi)))
        }
    }

    @Test
    @DisplayName("A request for a different model identifier is rejected")
    fun mismatchedModelIdentifierThrows() {
        val oracle = uniformDrawOracle()
        val mi = ModelInputs("someOtherModel", 1, mapOf("x" to 0.0))
        assertThrows<IllegalArgumentException> {
            oracle.simulate(EvaluationRequest("someOtherModel", listOf(mi)))
        }
    }

    @Test
    @DisplayName("MCReplicationOracle behaves exactly like the equivalent single-response oracle")
    fun mcReplicationOracleMatchesEquivalentResponseFunctionOracle() {
        val mcOracle = MCReplicationOracle(
            MODEL_ID, OBJ,
            replicationFunctionBuilder = { streamProvider ->
                val stream = streamProvider.rnStream(1)
                MCReplicationFunctionIfc { inputs -> inputs.getValue("x") * 10.0 + stream.randU01() }
            }
        )
        val directOracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1)
                ResponseFunctionIfc { inputs ->
                    mapOf(OBJ to inputs.getValue("x") * 10.0 + stream.randU01())
                }
            }
        )
        val p = point(2.0, 4)
        val request = EvaluationRequest(MODEL_ID, listOf(p))
        val mcAvg = mcOracle.simulate(request).getValue(p).getOrThrow().getValue(OBJ).average
        val directAvg = directOracle.simulate(request).getValue(p).getOrThrow().getValue(OBJ).average
        assertEquals(directAvg, mcAvg)
        val expected = referenceUniformDraws(0, 4).map { it.single() }.toDoubleArray()
        assertEquals(20.0 + expected.statistics().average, mcAvg, 1e-12)
    }
}
