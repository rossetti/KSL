package ksl.app.swing.single

import ksl.examples.general.appsupport.LKInventoryBundle
import ksl.examples.general.appsupport.MM1Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.session.KSLRuntimeError
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [SingleAppViewModel]'s state machine.  No Swing components are
 * constructed — the view model uses an injected [Dispatchers.IO]-backed scope
 * so the tests run headless.
 *
 * State machine:
 *   Idle → Submitting → Running → (Completed | Cancelled | Failed)
 *                                                       ↓
 *                                                  Idle (after acknowledge)
 */
class SingleAppViewModelTest {

    private fun headlessScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun `initial state is Idle`() = runBlocking {
        val scope = headlessScope()
        SingleAppViewModel(scope = scope).use { vm ->
            assertIs<UiState.Idle>(vm.uiState.value)
            assertEquals(MM1Bundle.MODEL_ID, vm.selectedModelId)
        }
        scope.cancel()
    }

    @Test
    fun `submit transitions to Completed for a valid MM1 run`() = runBlocking {
        val scope = headlessScope()
        SingleAppViewModel(scope = scope).use { vm ->
            // Keep the run small so the test finishes quickly.
            vm.updateRunParameters(
                vm.runParameters.copy(
                    numberOfReplications = 2,
                    lengthOfReplication = 50.0,
                    lengthOfReplicationWarmUp = 0.0
                )
            )
            val completed = awaitTerminal(vm) { vm.submit() }
            assertIs<UiState.Completed>(completed)
            assertTrue(completed.result.summary.completedReplications == 2)
        }
        scope.cancel()
    }

    @Test
    fun `submit transitions to Failed for an invalid run configuration`() = runBlocking {
        val scope = headlessScope()
        SingleAppViewModel(scope = scope).use { vm ->
            // Warm-up == replication length is rejected by RunConfigurationValidator.
            // Build that combination by replacing the run parameters wholesale.
            vm.updateRunParameters(
                vm.runParameters.copy(
                    numberOfReplications = 1,
                    lengthOfReplication = 10.0,
                    lengthOfReplicationWarmUp = 10.0
                )
            )
            val failed = awaitTerminal(vm) { vm.submit() }
            assertIs<UiState.Failed>(failed)
            assertIs<KSLRuntimeError.ConfigurationError>(failed.error)
        }
        scope.cancel()
    }

    @Test
    fun `cancel during Running transitions to Cancelled`() = runBlocking {
        val scope = headlessScope()
        SingleAppViewModel(scope = scope).use { vm ->
            // Make the run long enough that we can cancel before it completes.
            vm.updateRunParameters(
                vm.runParameters.copy(
                    numberOfReplications = 30,
                    lengthOfReplication = 50_000.0,
                    lengthOfReplicationWarmUp = 0.0
                )
            )
            // Launch a watcher that issues cancel as soon as the first Running
            // state appears.
            val cancelIssued = CompletableDeferred<Unit>()
            val watcher = scope.launch {
                // Wait for any Running state, then cancel.
                vm.uiState.first { it is UiState.Running }
                vm.cancel()
                cancelIssued.complete(Unit)
            }
            val terminal = awaitTerminal(vm) { vm.submit() }
            withTimeout(TIMEOUT_MS) { cancelIssued.await() }
            watcher.cancel()
            val cancelled = assertIs<UiState.Cancelled>(terminal)
            assertEquals("user cancelled from GUI", cancelled.reason)
        }
        scope.cancel()
    }

    @Test
    fun `acknowledgeResult returns to Idle from a terminal state`() = runBlocking {
        val scope = headlessScope()
        SingleAppViewModel(scope = scope).use { vm ->
            vm.updateRunParameters(
                vm.runParameters.copy(
                    numberOfReplications = 1,
                    lengthOfReplication = 50.0,
                    lengthOfReplicationWarmUp = 0.0
                )
            )
            awaitTerminal(vm) { vm.submit() }
            vm.acknowledgeResult()
            assertIs<UiState.Idle>(vm.uiState.value)
        }
        scope.cancel()
    }

    @Test
    fun `selectModel switches the editable run parameters to the new model defaults`() = runBlocking {
        val scope = headlessScope()
        SingleAppViewModel(scope = scope).use { vm ->
            val mm1Reps = vm.runParameters.numberOfReplications
            vm.selectModel(LKInventoryBundle.MODEL_ID)
            assertEquals(LKInventoryBundle.MODEL_ID, vm.selectedModelId)
            // The LK model's default replication count differs from MM1's, so
            // selecting a different model must have replaced the parameters.
            val lkReps = vm.runParameters.numberOfReplications
            assertTrue(lkReps != mm1Reps,
                "Expected switching models to reset run parameters to model defaults")
        }
        scope.cancel()
    }

    private suspend fun awaitTerminal(
        vm: SingleAppViewModel,
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
        const val TIMEOUT_MS = 60_000L
    }
}
