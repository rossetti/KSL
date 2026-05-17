package ksl.app.swing.common.runcontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.datetime.Instant
import kotlinx.datetime.Instant.Companion.fromEpochMilliseconds
import ksl.app.session.RunEvent
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleLogPanelTest {

    private fun startedEvent(): RunEvent = RunEvent.ReplicationRunStarted(
        runId = "r1", modelIdentifier = "MM1", totalReplications = 10, startTime = fromEpochMilliseconds(0L)
    )

    private fun replicationStarted(rep: Int = 1): RunEvent =
        RunEvent.ReplicationStarted(repNumber = rep, totalReplications = 10)

    private fun warning(): RunEvent =
        RunEvent.RunWarning(warning = ksl.app.session.RunWarningType.InfiniteHorizonNoTimeout("MM1"))

    private fun failed(): RunEvent =
        RunEvent.RunFailed(
            error = ksl.app.session.KSLRuntimeError.ModelBuildError("boom", RuntimeException("x"))
        )

    // ── severity / category classification ─────────────────────────────────

    @Test
    fun `severity classification covers every event family`() {
        assertEquals(ConsoleSeverity.INFO, ConsoleLogPanel.severityOf(startedEvent()))
        assertEquals(ConsoleSeverity.WARNING, ConsoleLogPanel.severityOf(warning()))
        assertEquals(ConsoleSeverity.WARNING, ConsoleLogPanel.severityOf(RunEvent.RunCancelled("nope")))
        assertEquals(ConsoleSeverity.ERROR, ConsoleLogPanel.severityOf(failed()))
        assertEquals(
            ConsoleSeverity.ERROR,
            ConsoleLogPanel.severityOf(
                RunEvent.ScenarioCompleted("s", 1, 1, snapshot = null)
            )
        )
    }

    @Test
    fun `category classification groups events into the three buckets`() {
        assertEquals(ConsoleCategory.LIFECYCLE, ConsoleLogPanel.categoryOf(startedEvent()))
        assertEquals(ConsoleCategory.LIFECYCLE, ConsoleLogPanel.categoryOf(warning()))
        assertEquals(ConsoleCategory.LIFECYCLE, ConsoleLogPanel.categoryOf(failed()))
        assertEquals(ConsoleCategory.REPLICATION, ConsoleLogPanel.categoryOf(replicationStarted()))
        assertEquals(
            ConsoleCategory.REPLICATION,
            ConsoleLogPanel.categoryOf(RunEvent.SimTimeAdvanced(1.0, 0))
        )
        assertEquals(
            ConsoleCategory.ORCHESTRATOR,
            ConsoleLogPanel.categoryOf(
                RunEvent.ScenarioCompleted("s", 1, 1, snapshot = null)
            )
        )
    }

    // ── rendering with internal push ───────────────────────────────────────

    @Test
    fun `panel renders one line per pushed event`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val panel = onEdt { ConsoleLogPanel(MutableSharedFlow(), scope) }
            onEdt {
                panel.pushEventForTest(startedEvent())
                panel.pushEventForTest(replicationStarted(1))
                panel.pushEventForTest(replicationStarted(2))
            }
            onEdt { assertEquals(3, panel.renderedLineCount()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `severity filter hides matching events on toggle`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val panel = onEdt { ConsoleLogPanel(MutableSharedFlow(), scope) }
            onEdt {
                panel.pushEventForTest(startedEvent())     // INFO
                panel.pushEventForTest(warning())          // WARNING
                panel.pushEventForTest(failed())           // ERROR
            }
            onEdt { assertEquals(3, panel.renderedLineCount()) }
            onEdt { panel.simulateSeverityToggle(ConsoleSeverity.WARNING, enabled = false) }
            onEdt {
                assertEquals(2, panel.renderedLineCount())
                assertTrue(!panel.renderedText.contains("[WARNING]"))
            }
            onEdt { panel.simulateSeverityToggle(ConsoleSeverity.WARNING, enabled = true) }
            onEdt { assertEquals(3, panel.renderedLineCount()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `category filter hides matching events on toggle`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val panel = onEdt { ConsoleLogPanel(MutableSharedFlow(), scope) }
            onEdt {
                panel.pushEventForTest(startedEvent())             // LIFECYCLE
                panel.pushEventForTest(replicationStarted(1))      // REPLICATION
                panel.pushEventForTest(
                    RunEvent.ScenarioCompleted("s", 1, 1, snapshot = null)
                )                                                  // ORCHESTRATOR
            }
            onEdt { assertEquals(3, panel.renderedLineCount()) }
            onEdt { panel.simulateCategoryToggle(ConsoleCategory.REPLICATION, enabled = false) }
            onEdt {
                assertEquals(2, panel.renderedLineCount())
                assertTrue(!panel.renderedText.contains("Replication 1"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Clear Console button empties the buffer and the rendered text`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val panel = onEdt { ConsoleLogPanel(MutableSharedFlow(), scope) }
            onEdt {
                panel.pushEventForTest(startedEvent())
                panel.pushEventForTest(replicationStarted(1))
            }
            onEdt { panel.simulateClear() }
            onEdt { assertEquals(0, panel.renderedLineCount()) }
            // Re-enabling a filter should not bring cleared lines back.
            onEdt { panel.simulateSeverityToggle(ConsoleSeverity.INFO, enabled = false) }
            onEdt { panel.simulateSeverityToggle(ConsoleSeverity.INFO, enabled = true) }
            onEdt { assertEquals(0, panel.renderedLineCount()) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `event ordering is preserved in rendered output`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val panel = onEdt { ConsoleLogPanel(MutableSharedFlow(), scope) }
            onEdt {
                panel.pushEventForTest(replicationStarted(1))
                panel.pushEventForTest(replicationStarted(2))
                panel.pushEventForTest(replicationStarted(3))
            }
            onEdt {
                val text = panel.renderedText
                val idx1 = text.indexOf("Replication 1")
                val idx2 = text.indexOf("Replication 2")
                val idx3 = text.indexOf("Replication 3")
                assertTrue(idx1 in 0 until idx2 && idx2 < idx3, "lines should appear in push order: $text")
            }
        } finally {
            scope.cancel()
        }
    }

    // ── bounded buffer ─────────────────────────────────────────────────────

    @Test
    fun `buffer stays bounded and surfaces a dropped-line notice past the cap`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val panel = onEdt { ConsoleLogPanel(MutableSharedFlow(), scope) }
            val cap = ConsoleLogPanel.MAX_BUFFER_SIZE
            val batch = ConsoleLogPanel.BUFFER_TRIM_BATCH
            // Push enough events to overflow the cap by two trim batches
            // so droppedCount accumulates predictably.
            val pushCount = cap + 2 * batch + 5
            onEdt {
                repeat(pushCount) { i ->
                    panel.pushEventForTest(replicationStarted(rep = i + 1))
                }
            }
            onEdt {
                // After each trim of BUFFER_TRIM_BATCH events, buffer.size
                // drops to (cap - batch + 1).  Subsequent pushes refill
                // until the next trim.  Either way, buffer never exceeds
                // the cap.
                assertTrue(
                    panel.bufferSizeForTest <= cap,
                    "buffer should stay <= cap; got ${panel.bufferSizeForTest}"
                )
                // droppedCount must be a positive multiple of batch.
                assertTrue(
                    panel.droppedCountForTest > 0 &&
                        panel.droppedCountForTest % batch == 0,
                    "droppedCount should be a positive multiple of $batch; got ${panel.droppedCountForTest}"
                )
                assertTrue(
                    panel.renderedText.contains("earlier line(s) dropped"),
                    "rendered text should announce the dropped lines; got: ${panel.renderedText.take(200)}"
                )
            }
            // Clear resets dropped count and the notice goes away.
            onEdt { panel.simulateClear() }
            onEdt {
                assertEquals(0, panel.droppedCountForTest)
                assertEquals(0, panel.bufferSizeForTest)
                assertTrue(!panel.renderedText.contains("earlier line(s) dropped"))
            }
        } finally {
            scope.cancel()
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
