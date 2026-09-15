package ksl.simopt.evaluator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StreamTapePolicyTest {

    private fun inputs(vararg reps: Int): List<ModelInputs> =
        reps.map { ModelInputs(modelIdentifier = "M", numReplications = it, inputs = mapOf("x" to 1.0)) }

    @Test
    fun independentAdvancesCumulativelyWithinAndAcrossRequests() {
        val policy = StreamTapePolicy()
        assertEquals(listOf(0, 3), policy.advancesFor(inputs(3, 3), crnOption = false))
        assertEquals(6, policy.position)
        // A second request continues the tape rather than restarting at 0 (the cross-request fix).
        assertEquals(listOf(6, 9), policy.advancesFor(inputs(3, 3), crnOption = false))
        assertEquals(12, policy.position)
    }

    @Test
    fun crnSharesBlockThenAdvancesByMaxReps() {
        val policy = StreamTapePolicy()
        assertEquals(listOf(0, 0, 0), policy.advancesFor(inputs(2, 5, 3), crnOption = true))
        assertEquals(5, policy.position)   // advanced by the request's max replications (5)
        assertEquals(listOf(5, 5), policy.advancesFor(inputs(4, 1), crnOption = true))
        assertEquals(9, policy.position)   // 5 + max(4, 1)
    }

    @Test
    fun mixedIndependentThenCrnSharesOneTape() {
        val policy = StreamTapePolicy()
        assertEquals(listOf(0, 2), policy.advancesFor(inputs(2, 2), crnOption = false))   // tape -> 4
        assertEquals(listOf(4, 4), policy.advancesFor(inputs(3, 3), crnOption = true))    // tape -> 7
        assertEquals(7, policy.position)
    }

    @Test
    fun emptyInputsYieldNoAdvancesAndLeaveTapeUnchanged() {
        val policy = StreamTapePolicy()
        policy.advancesFor(inputs(3), crnOption = false)   // tape -> 3
        assertEquals(emptyList<Int>(), policy.advancesFor(emptyList<ModelInputs>(), crnOption = false))
        assertEquals(3, policy.position)
    }

    @Test
    fun resetReturnsTapeToZero() {
        val policy = StreamTapePolicy()
        policy.advancesFor(inputs(5), crnOption = false)
        policy.reset()
        assertEquals(0, policy.position)
    }

    @Test
    fun initialPositionOffsetsTheTapeAndReset() {
        val policy = StreamTapePolicy(initialPosition = 100)
        assertEquals(100, policy.position)
        assertEquals(100, policy.initialPosition)
        assertEquals(listOf(100, 103), policy.advancesFor(inputs(3, 3), crnOption = false))
        assertEquals(106, policy.position)
        // reset returns to the configured initial position, not to zero
        policy.reset()
        assertEquals(100, policy.position)
    }

    @Test
    fun memberBlockOffsetsProduceNonOverlappingSubStreamRegions() {
        val blockSize = 1000
        val consumedPositions = mutableListOf<Int>()
        for (member in 0 until 4) {
            val policy = StreamTapePolicy(initialPosition = member * blockSize)
            val advances = policy.advancesFor(inputs(5, 5, 5), crnOption = false)
            // each point consumes sub-streams [advance, advance + reps)
            for (advance in advances) {
                for (subStream in advance until advance + 5) {
                    consumedPositions.add(subStream)
                }
            }
            // the member stayed within its own block
            assertEquals(member * blockSize + 15, policy.position)
        }
        // no sub-stream index is consumed by two members
        assertEquals(consumedPositions.size, consumedPositions.toSet().size)
    }

    // ── The replication-count overload ────────────────────────────────────────
    //
    // Positioning a run on the tape depends on nothing about a point except how many replications
    // it consumes, so the arithmetic is reusable by any harness that positions replications
    // absolutely -- without it having to manufacture ModelInputs values it has no other use for.

    /**
     * The two overloads must be one implementation, not two that agree today. Driving each with the
     * same replication counts from a fresh tape has to give identical advances and an identical
     * final position, under both stream options.
     */
    @Test
    fun theCountOverloadAndTheModelInputsOverloadAgree() {
        for (crn in listOf(false, true)) {
            for (counts in listOf(listOf(3, 3), listOf(2, 5, 3), listOf(7), listOf(4, 1, 4, 1))) {
                val viaInputs = StreamTapePolicy()
                val viaCounts = StreamTapePolicy()
                assertEquals(
                    viaInputs.advancesFor(inputs(*counts.toIntArray()), crnOption = crn),
                    viaCounts.advancesFor(counts, crnOption = crn)
                ) { "advances differ for counts=$counts crn=$crn" }
                assertEquals(viaInputs.position, viaCounts.position) {
                    "tape position differs for counts=$counts crn=$crn"
                }
            }
        }
    }

    /** The tape is one tape: the overloads can be interleaved on the same policy. */
    @Test
    fun theOverloadsShareOneTape() {
        val policy = StreamTapePolicy()
        assertEquals(listOf(0, 2), policy.advancesFor(listOf(2, 2), crnOption = false))
        assertEquals(listOf(4, 4), policy.advancesFor(inputs(3, 3), crnOption = true))
        assertEquals(listOf(7), policy.advancesFor(listOf(5), crnOption = false))
        assertEquals(12, policy.position)
    }

    @Test
    fun emptyCountsYieldNoAdvancesAndLeaveTapeUnchanged() {
        val policy = StreamTapePolicy(initialPosition = 9)
        assertEquals(emptyList<Int>(), policy.advancesFor(emptyList<Int>(), crnOption = false))
        assertEquals(9, policy.position)
    }

    /** A negative count would silently move the tape backwards, overlapping what was already used. */
    @Test
    fun aNegativeReplicationCountIsRejected() {
        val policy = StreamTapePolicy()
        assertThrows(IllegalArgumentException::class.java) {
            policy.advancesFor(listOf(3, -1), crnOption = false)
        }
    }
}
