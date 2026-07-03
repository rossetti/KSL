package ksl.simopt.evaluator

import org.junit.jupiter.api.Assertions.assertEquals
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
        assertEquals(emptyList<Int>(), policy.advancesFor(emptyList(), crnOption = false))
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
}
