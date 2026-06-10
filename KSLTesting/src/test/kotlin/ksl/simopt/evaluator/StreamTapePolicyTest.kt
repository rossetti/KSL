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
}
