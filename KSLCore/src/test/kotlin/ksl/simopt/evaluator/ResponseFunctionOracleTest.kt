package ksl.simopt.evaluator

import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.statistics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the response-function oracle adaptor: summary-statistics correctness against
 * closed forms, independent-vs-CRN stream semantics, tape continuity across requests,
 * per-member tape offsets, and per-point failure mapping.
 *
 * The stream-semantics tests compare the oracle's output exactly against a hand-driven
 * reference stream (fresh default providers produce identical streams), so they assert
 * the positioning discipline bit-for-bit rather than probabilistically.
 */
class ResponseFunctionOracleTest {

    private companion object {
        const val MODEL_ID = "testResponseFn"
        const val OBJ = "objFn"
    }

    /** An oracle whose single response is the first uniform draw of each replication's
     *  sub-stream — the most direct probe of the stream positioning discipline. */
    private fun uniformDrawOracle(tapePolicy: StreamTapePolicy = StreamTapePolicy()): ResponseFunctionOracle {
        return ResponseFunctionOracle(
            modelIdentifier = MODEL_ID,
            responseNames = setOf(OBJ),
            responseFunction = ResponseFunctionIfc { _, stream -> mapOf(OBJ to stream.randU01()) },
            streamTapePolicy = tapePolicy
        )
    }

    private fun point(x: Double, numReplications: Int): ModelInputs {
        return ModelInputs(MODEL_ID, numReplications, mapOf("x" to x))
    }

    /** The draws the uniform-draw oracle must produce for a point positioned at
     *  startSubStream: replication r draws the first uniform of sub-stream startSubStream + r. */
    private fun referenceUniformDraws(startSubStream: Int, numReplications: Int): DoubleArray {
        val stream = RNStreamProvider().rnStream(1)
        val out = DoubleArray(numReplications)
        stream.resetStartStream()
        stream.advanceSubStreams(startSubStream.toLong())
        for (r in 0 until numReplications) {
            if (r > 0) {
                stream.advanceToNextSubStream()
            }
            out[r] = stream.randU01()
        }
        return out
    }

    @Test
    @DisplayName("Summary statistics match closed forms for a deterministic multi-response function")
    fun statisticsMatchClosedForm() {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ, "twice"),
            ResponseFunctionIfc { inputs, _ ->
                val x = inputs.getValue("x")
                mapOf(OBJ to x + 1.0, "twice" to 2.0 * x)
            }
        )
        val p = point(3.0, 5)
        val results = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(p)))
        val responseMap = results.getValue(p).getOrThrow()
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
    @DisplayName("Independent points draw from consecutive, non-overlapping sub-stream blocks")
    fun independentPointsDrawFromDisjointSubStreamBlocks() {
        val oracle = uniformDrawOracle()
        val p1 = point(1.0, 3)
        val p2 = point(2.0, 2)
        val results = oracle.simulate(EvaluationRequest(MODEL_ID, listOf(p1, p2)))
        // point 1 occupies sub-streams 0..2; point 2 starts where point 1's block ends
        val expected1 = referenceUniformDraws(0, 3).statistics().average
        val expected2 = referenceUniformDraws(3, 2).statistics().average
        assertEquals(expected1, results.getValue(p1).getOrThrow().getValue(OBJ).average)
        assertEquals(expected2, results.getValue(p2).getOrThrow().getValue(OBJ).average)
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
        assertEquals(referenceUniformDraws(0, 4).statistics().average, avg1)
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
        val member0Avg = uniformDrawOracle(StreamTapePolicy(initialPosition = 0))
            .simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow().getValue(OBJ).average
        val member1Avg = uniformDrawOracle(StreamTapePolicy(initialPosition = 1000))
            .simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow().getValue(OBJ).average
        assertNotEquals(member0Avg, member1Avg)
        assertEquals(referenceUniformDraws(1000, 3).statistics().average, member1Avg)
        val member1AgainAvg = uniformDrawOracle(StreamTapePolicy(initialPosition = 1000))
            .simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow().getValue(OBJ).average
        assertEquals(member1Avg, member1AgainAvg)
    }

    @Test
    @DisplayName("A non-finite response value maps that point (and only that point) to failure")
    fun nonFiniteValueMapsThatPointToFailure() {
        val oracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionIfc { inputs, _ ->
                val x = inputs.getValue("x")
                mapOf(OBJ to if (x > 0.0) Double.POSITIVE_INFINITY else x)
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
            ResponseFunctionIfc { inputs, _ ->
                check(inputs.getValue("x") <= 0.0) { "injected replication failure" }
                mapOf(OBJ to 0.0)
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
            ResponseFunctionIfc { _, _ -> mapOf(OBJ to 0.0) }
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
            { inputs, stream -> inputs.getValue("x") * 10.0 + stream.randU01() }
        )
        val directOracle = ResponseFunctionOracle(
            MODEL_ID, setOf(OBJ),
            ResponseFunctionIfc { inputs, stream ->
                mapOf(OBJ to inputs.getValue("x") * 10.0 + stream.randU01())
            }
        )
        val p = point(2.0, 4)
        val mcAvg = mcOracle.simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow().getValue(OBJ).average
        val directAvg = directOracle.simulate(EvaluationRequest(MODEL_ID, listOf(p)))
            .getValue(p).getOrThrow().getValue(OBJ).average
        assertEquals(directAvg, mcAvg)
        assertEquals(20.0 + referenceUniformDraws(0, 4).statistics().average, mcAvg, 1e-12)
    }
}
