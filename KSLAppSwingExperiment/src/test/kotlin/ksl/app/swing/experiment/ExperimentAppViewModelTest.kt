package ksl.app.swing.experiment

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

/**
 * Tests for [ExperimentAppViewModel]'s state machine.  No Swing
 * components are constructed — an injected `Dispatchers.IO`-backed scope
 * keeps the tests headless.
 */
class ExperimentAppViewModelTest {

    private fun headlessScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun `initial state is Idle and experiment populated from the selected model`() = runBlocking {
        val scope = headlessScope()
        ExperimentAppViewModel(scope = scope).use { vm ->
            assertIs<UiState.Idle>(vm.uiState.value)
            assertEquals(LKInventoryBundle.MODEL_ID, vm.selectedModelId)
            // LK 2-factor 2-level full factorial → 4 design points
            assertEquals(4, vm.experiment.design.designPoints().size)
        }
        scope.cancel()
    }

    @Test
    fun `submit transitions to Completed for a valid LK experiment`() = runBlocking {
        val scope = headlessScope()
        ExperimentAppViewModel(scope = scope).use { vm ->
            val terminal = awaitTerminal(vm) { vm.submit() }
            assertIs<UiState.Completed>(terminal)
            assertEquals(4, terminal.result.summary.totalItems)
            assertEquals(4, terminal.result.summary.completedItems)
            assertEquals(0, terminal.result.summary.failedItems)
        }
        scope.cancel()
    }

    @Test
    fun `acknowledgeResult returns to Idle from a terminal state`() = runBlocking {
        val scope = headlessScope()
        ExperimentAppViewModel(scope = scope).use { vm ->
            awaitTerminal(vm) { vm.submit() }
            vm.acknowledgeResult()
            assertIs<UiState.Idle>(vm.uiState.value)
        }
        scope.cancel()
    }

    @Test
    fun `selectModel rejects models without a bundled experiment`() = runBlocking {
        val scope = headlessScope()
        ExperimentAppViewModel(scope = scope).use { vm ->
            // MM1 ships in MM1Bundle but has only one @KSLControl
            // property — FactorialDesign requires ≥ 2 factors, so it has
            // no bundled experiment and the picker must reject it.
            assertFailsWith<IllegalArgumentException> {
                vm.selectModel(MM1Bundle.MODEL_ID)
            }
            assertEquals(LKInventoryBundle.MODEL_ID, vm.selectedModelId)
        }
        scope.cancel()
    }

    private suspend fun awaitTerminal(
        vm: ExperimentAppViewModel,
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
