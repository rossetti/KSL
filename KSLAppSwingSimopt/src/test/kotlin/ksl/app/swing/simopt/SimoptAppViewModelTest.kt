package ksl.app.swing.simopt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.examples.general.appsupport.LKInventoryBundle
import ksl.examples.general.appsupport.MM1Bundle
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [SimoptAppViewModel]'s state machine.  No Swing components
 * are constructed — an injected `Dispatchers.IO`-backed scope keeps the
 * tests headless.
 */
class SimoptAppViewModelTest {

    private fun headlessScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun `initial state is Idle and optimization populated from the selected model`() = runBlocking {
        val scope = headlessScope()
        SimoptAppViewModel(scope = scope).use { vm ->
            assertIs<UiState.Idle>(vm.uiState.value)
            assertEquals(LKInventoryBundle.MODEL_ID, vm.selectedModelId)
            assertEquals(2, vm.optimization.problem.inputs.size)
            assertTrue(vm.optimization.solver.maxIterations > 0)
        }
        scope.cancel()
    }

    @Test
    fun `submit transitions to Completed for the bundled LK optimization`() = runBlocking {
        val scope = headlessScope()
        SimoptAppViewModel(scope = scope).use { vm ->
            val terminal = awaitTerminal(vm) { vm.submit() }
            assertIs<UiState.Completed>(terminal)
            val r = terminal.result
            assertTrue(r.summary.completedItems > 0,
                "Expected at least one completed iteration")
            assertEquals(0, r.summary.failedItems)
            assertTrue(r.iterationHistory.isNotEmpty(),
                "Iteration history should be non-empty on a completed run")
            assertTrue(r.bestSolution.bestSolutionSoFar.inputMap.isNotEmpty(),
                "Best solution should carry an input map")
        }
        scope.cancel()
    }

    @Test
    fun `acknowledgeResult returns to Idle from a terminal state`() = runBlocking {
        val scope = headlessScope()
        SimoptAppViewModel(scope = scope).use { vm ->
            awaitTerminal(vm) { vm.submit() }
            vm.acknowledgeResult()
            assertIs<UiState.Idle>(vm.uiState.value)
        }
        scope.cancel()
    }

    @Test
    fun `selectModel rejects models without a bundled optimization`() = runBlocking {
        val scope = headlessScope()
        SimoptAppViewModel(scope = scope).use { vm ->
            // MM1 ships in MM1Bundle but has no bundled
            // optimization configuration — the picker must reject it.
            assertFailsWith<IllegalArgumentException> {
                vm.selectModel(MM1Bundle.MODEL_ID)
            }
            assertEquals(LKInventoryBundle.MODEL_ID, vm.selectedModelId)
        }
        scope.cancel()
    }

    private suspend fun awaitTerminal(
        vm: SimoptAppViewModel,
        action: () -> Unit
    ): UiState {
        action()
        return withTimeout(TIMEOUT_MS) {
            vm.uiState.first {
                it is UiState.Completed || it is UiState.Cancelled || it is UiState.Failed
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
