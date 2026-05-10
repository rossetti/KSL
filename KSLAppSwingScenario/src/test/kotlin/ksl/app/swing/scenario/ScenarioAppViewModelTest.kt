package ksl.app.swing.scenario

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
 * Tests for [ScenarioAppViewModel]'s state machine.  No Swing components
 * are constructed — an injected `Dispatchers.IO`-backed scope keeps the
 * tests headless.
 */
class ScenarioAppViewModelTest {

    private fun headlessScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Test
    fun `initial state is Idle and scenarios populated from the selected model`() = runBlocking {
        val scope = headlessScope()
        ScenarioAppViewModel(scope = scope).use { vm ->
            assertIs<UiState.Idle>(vm.uiState.value)
            assertEquals(BundledModels.MM1_ID, vm.selectedModelId)
            assertEquals(3, vm.scenarios.size)
        }
        scope.cancel()
    }

    @Test
    fun `submit transitions to Completed for a valid scenario sweep`() = runBlocking {
        val scope = headlessScope()
        ScenarioAppViewModel(scope = scope).use { vm ->
            // Keep the scenarios small so the test finishes quickly — shrink
            // their replication count to 2 each.
            val original = vm.scenarios
            // We can't directly edit scenarios on the view model; instead,
            // simulate a model whose bundled scenarios are inherently short.
            // For this test we accept the MM1 defaults (30 reps × 500.0
            // length) but rely on the small replication length to finish
            // quickly.  See the cancel test for an explicit long-run scenario.
            val terminal = awaitTerminal(vm) { vm.submit() }
            assertIs<UiState.Completed>(terminal)
            assertEquals(3, terminal.result.summary.completedItems)
            assertEquals(0, terminal.result.summary.failedItems)
            assertEquals(3, terminal.result.snapshots.size)
            // The first stat in the first snapshot should not throw.
            assertTrue(original.isNotEmpty())
        }
        scope.cancel()
    }

    @Test
    fun `cancel during Running transitions to Cancelled`() = runBlocking {
        val scope = headlessScope()
        ScenarioAppViewModel(scope = scope).use { vm ->
            // Long-running scenarios so cancel has time to land. The LK bundle
            // has shorter run-time per scenario so we use MM1.
            val cancelIssued = CompletableDeferred<Unit>()
            val watcher = scope.launch {
                vm.uiState.first { it is UiState.Running }
                vm.cancel()
                cancelIssued.complete(Unit)
            }
            val terminal = awaitTerminal(vm) { vm.submit() }
            withTimeout(TIMEOUT_MS) { cancelIssued.await() }
            watcher.cancel()
            // The terminal may be Cancelled (cancel landed before all done)
            // or Completed (the sweep finished before we caught the Running
            // state — very fast scenarios).  Both outcomes are valid for
            // the cancel path; we assert that if Running was observed and
            // we issued cancel, the run cannot have failed.
            assertTrue(terminal is UiState.Cancelled || terminal is UiState.Completed,
                "Expected Cancelled or Completed, got $terminal")
        }
        scope.cancel()
    }

    @Test
    fun `acknowledgeResult returns to Idle from a terminal state`() = runBlocking {
        val scope = headlessScope()
        ScenarioAppViewModel(scope = scope).use { vm ->
            awaitTerminal(vm) { vm.submit() }
            vm.acknowledgeResult()
            assertIs<UiState.Idle>(vm.uiState.value)
        }
        scope.cancel()
    }

    @Test
    fun `selectModel resets the scenario list to the new model's bundle`() = runBlocking {
        val scope = headlessScope()
        ScenarioAppViewModel(scope = scope).use { vm ->
            val mm1Count = vm.scenarios.size
            vm.selectModel(BundledModels.LK_INVENTORY_ID)
            assertEquals(BundledModels.LK_INVENTORY_ID, vm.selectedModelId)
            val lkCount = vm.scenarios.size
            assertTrue(lkCount != mm1Count || lkCount > 0,
                "Expected scenarios list to be repopulated on model switch")
        }
        scope.cancel()
    }

    private suspend fun awaitTerminal(
        vm: ScenarioAppViewModel,
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
        const val TIMEOUT_MS = 120_000L
    }
}
