/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.common.runcontrol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.session.RunEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Severity bucket used by [ConsoleLogPanel].  Independent from
 * [Category].
 */
enum class ConsoleSeverity { INFO, WARNING, ERROR }

/**
 * Category bucket used by [ConsoleLogPanel].  Independent from
 * [ConsoleSeverity].
 */
enum class ConsoleCategory { LIFECYCLE, REPLICATION, ORCHESTRATOR, STDOUT }

/**
 * Renders a [RunEvent] as a single human-readable line.  Override
 * to integrate custom run-event types, or use [DefaultEventFormatter].
 */
fun interface EventFormatter {
    fun format(event: RunEvent): String
}

/**
 * Default [EventFormatter]: a compact one-line summary per event
 * type.  Failing `ScenarioCompleted` / `DesignPointCompleted` events
 * (snapshot is null) render with a *(failed)* tag.
 */
object DefaultEventFormatter : EventFormatter {
    override fun format(event: RunEvent): String = when (event) {
        is RunEvent.ReplicationRunStarted -> "Run started: ${event.modelIdentifier} (replications=${event.totalReplications})"
        is RunEvent.ScenarioRunStarted -> "Scenario run started: ${event.modelIdentifier} (scenarios=${event.totalScenarios})"
        is RunEvent.ExperimentRunStarted -> "Experiment run started: ${event.modelIdentifier} (designPoints=${event.totalDesignPoints})"
        is RunEvent.OptimizationRunStarted -> "Optimization run started: ${event.modelIdentifier} (maxIterations=${event.maxIterations})"
        is RunEvent.RunWarning -> "Warning: ${event.warning}"
        is RunEvent.ReplicationStarted -> "Replication ${event.repNumber} / ${event.totalReplications} started"
        is RunEvent.ReplicationEnded -> "Replication ${event.repNumber} / ${event.totalReplications} ended"
        is RunEvent.SimTimeAdvanced -> "Simulation time advanced: ${event.simTime} (events=${event.eventsExecuted})"
        is RunEvent.RunFailed -> "Run failed: ${event.error}"
        is RunEvent.RunCancelled -> "Run cancelled: ${event.reason}"
        is RunEvent.RunCompleted -> "Run completed"
        is RunEvent.ScenarioCompleted ->
            "Scenario ${event.scenarioName} (${event.index}/${event.totalScenarios})" +
                if (event.snapshot == null) " (failed)" else " complete"
        is RunEvent.DesignPointCompleted ->
            "Design point ${event.pointId} (${event.index}/${event.totalDesignPoints})" +
                if (event.snapshot == null) " (failed)" else " complete"
        is RunEvent.IterationCompleted ->
            "Iteration ${event.iteration}: best objective = ${event.estimatedObjectiveValue}"
        // Captured stdout/stderr lines: render the raw text only so
        // user code's `println` output looks like itself.  The
        // appendLine path still prepends `[INFO]` / `[ERR]` decoration
        // and applies severity coloring; that's enough to indicate
        // origin without injecting a framework-style prefix.
        is RunEvent.StdOutLine -> event.text
    }
}

/**
 * Collapsible run-event console.  Subscribes to a caller-supplied
 * [SharedFlow] of [RunEvent]s and renders one line per event, with
 * toggleable severity and category filters and a *Clear Console*
 * button.  Per scenario workflow §10 the console persists across
 * runs — only the *Clear Console* button clears it.
 *
 * Auto-scroll behaviour matches IDE-console convention: appended
 * events scroll the view to the bottom only when the user is
 * already at the bottom.  Scrolled-back users keep their position.
 *
 * @param eventFlow source of events to render.
 * @param scope owns the flow subscription.
 * @param formatter renders one line per [RunEvent].
 * @param hiddenCategories category buckets whose toggle chip should
 *   not be rendered in the header.  Events of these categories still
 *   pass the filter (they're treated as always-enabled) — they just
 *   can't be toggled off by the user.  Use to suppress chips for
 *   categories that don't apply to a given app surface (e.g.
 *   `ORCHESTRATOR` in the single-run app, where no orchestrator
 *   events are ever emitted).
 * @param autoClearOnRunStart when `true` (default), the buffer and
 *   text pane are cleared on every `RunEvent.Started` so each new run
 *   begins with a fresh log.  Set to `false` if you want lines to
 *   accumulate across runs.  The clear fires *before* the start event
 *   is itself rendered, so the start event appears as the first line
 *   of the new run's log.
 */
class ConsoleLogPanel(
    eventFlow: SharedFlow<RunEvent>,
    scope: CoroutineScope,
    private val formatter: EventFormatter = DefaultEventFormatter,
    hiddenCategories: Set<ConsoleCategory> = emptySet(),
    private val autoClearOnRunStart: Boolean = true
) : JPanel(BorderLayout()) {

    private val buffer: ArrayDeque<RunEvent> = ArrayDeque()
    private val enabledSeverities: MutableSet<ConsoleSeverity> = ConsoleSeverity.values().toMutableSet()
    private val enabledCategories: MutableSet<ConsoleCategory> = ConsoleCategory.values().toMutableSet()
    private val onClearListeners: MutableList<() -> Unit> = mutableListOf()
    private val afterEventListeners: MutableList<(RunEvent) -> Unit> = mutableListOf()
    /**
     * Running count of buffer entries that have been dropped from the
     * head due to the [MAX_BUFFER_SIZE] cap.  Surfaced as a dimmed
     * "… N earlier line(s) dropped" notice at the top of the rendered
     * text, so the user can tell their session has truncated history.
     * Reset to zero by [clearConsole].
     */
    private var droppedCount: Int = 0

    private val textPane = javax.swing.JTextPane().apply {
        isEditable = false
        background = Color.WHITE
    }
    private val scrollPane = JScrollPane(textPane).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val severityChips = ConsoleSeverity.values().associateWith { severity ->
        JToggleButton(severityLabel(severity)).apply {
            isSelected = true
            isFocusable = false
            toolTipText = "${severity.name} severity events"
            applyRailButtonSizing()
            addActionListener { onSeverityToggle(severity, this.isSelected) }
        }
    }
    private val categoryChips = ConsoleCategory.values()
        .filterNot { it in hiddenCategories }
        .associateWith { category ->
            JToggleButton(categoryLabel(category)).apply {
                isSelected = true
                isFocusable = false
                toolTipText = "${category.name} category events"
                applyRailButtonSizing()
                addActionListener { onCategoryToggle(category, this.isSelected) }
            }
        }
    private val clearButton = JButton("Clear").apply {
        isFocusable = false
        toolTipText = "Clear console (does not affect filters)"
        applyRailButtonSizing()
        addActionListener { clearConsole() }
    }

    init {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC))
        add(buildFilterRail(), BorderLayout.WEST)
        add(scrollPane, BorderLayout.CENTER)

        scope.launch(Dispatchers.Swing) {
            eventFlow
                .onEach { onEvent(it) }
                .collect { /* no-op terminal */ }
        }
    }

    /**
     * Vertical filter rail rendered on the WEST edge of the console.
     * Items stack top→bottom:
     *  - "Filter" header label
     *  - severity chips (INFO / WARN / ERR)
     *  - category chips (Life / Rep / Orch, minus any in `hiddenCategories`)
     *  - vertical glue (pushes Clear to the bottom)
     *  - "Clear" button
     *
     * Each chip and the Clear button share a fixed [RAIL_BUTTON_WIDTH]
     * so the rail reads as a clean column.  Full enum names are in
     * tooltips for affordance.
     */
    private fun buildFilterRail(): JPanel {
        val rail = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        }
        val title = JLabel("Filter", SwingConstants.LEFT).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.BOLD)
        }
        rail.add(title)
        rail.add(Box.createVerticalStrut(4))
        for (chip in severityChips.values) {
            chip.alignmentX = Component.LEFT_ALIGNMENT
            rail.add(chip)
            rail.add(Box.createVerticalStrut(2))
        }
        if (categoryChips.isNotEmpty()) {
            rail.add(Box.createVerticalStrut(4))
            rail.add(JSeparator(SwingConstants.HORIZONTAL).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(RAIL_BUTTON_WIDTH, 2)
            })
            rail.add(Box.createVerticalStrut(4))
            for (chip in categoryChips.values) {
                chip.alignmentX = Component.LEFT_ALIGNMENT
                rail.add(chip)
                rail.add(Box.createVerticalStrut(2))
            }
        }
        rail.add(Box.createVerticalGlue())
        clearButton.alignmentX = Component.LEFT_ALIGNMENT
        rail.add(clearButton)
        return rail
    }

    private fun JToggleButton.applyRailButtonSizing() {
        val h = preferredSize.height.coerceAtLeast(22)
        preferredSize = Dimension(RAIL_BUTTON_WIDTH, h)
        maximumSize = Dimension(RAIL_BUTTON_WIDTH, h)
        minimumSize = Dimension(RAIL_BUTTON_WIDTH, h)
        margin = java.awt.Insets(1, 4, 1, 4)
    }

    private fun JButton.applyRailButtonSizing() {
        val h = preferredSize.height.coerceAtLeast(22)
        preferredSize = Dimension(RAIL_BUTTON_WIDTH, h)
        maximumSize = Dimension(RAIL_BUTTON_WIDTH, h)
        minimumSize = Dimension(RAIL_BUTTON_WIDTH, h)
        margin = java.awt.Insets(1, 4, 1, 4)
    }

    /** Test-only: number of events currently rendered in the text pane. */
    internal fun renderedLineCount(): Int =
        if (textPane.document.length == 0) 0
        else textPane.text.count { it == '\n' } + if (textPane.text.endsWith("\n")) 0 else 1

    /** Test-only: the current rendered text. */
    internal val renderedText: String get() = textPane.text

    /** Test-only: synchronously deliver an event, bypassing the flow plumbing. */
    internal fun pushEventForTest(event: RunEvent) = onEvent(event)

    /** Test-only: simulate a click on the *Clear Console* button. */
    internal fun simulateClear() = clearButton.doClick()

    /** Test-only: simulate toggling a severity filter off. */
    internal fun simulateSeverityToggle(severity: ConsoleSeverity, enabled: Boolean) {
        val chip = severityChips.getValue(severity)
        chip.isSelected = enabled
        onSeverityToggle(severity, enabled)
    }

    /** Test-only: simulate toggling a category filter off. */
    internal fun simulateCategoryToggle(category: ConsoleCategory, enabled: Boolean) {
        val chip = categoryChips.getValue(category)
        chip.isSelected = enabled
        onCategoryToggle(category, enabled)
    }

    /**
     * Register a callback fired after the buffer + text pane are
     * cleared (whether by the Clear button or by auto-clear on run
     * start).  Use to keep external counters/summaries in sync with
     * the panel's state.  Runs on the EDT.
     */
    fun addOnClearListener(listener: () -> Unit) {
        onClearListeners.add(listener)
    }

    /**
     * Inject a captured stdout/stderr line into the panel's event
     * pipeline.  Synthesizes a [RunEvent.StdOutLine] and routes it
     * through the standard [onEvent] path so the panel's buffer,
     * filters, after-event listeners, and bounded-cap behavior all
     * apply uniformly.
     *
     * Safe to call from any thread — non-EDT callers are dispatched
     * onto the EDT via `SwingUtilities.invokeLater`.  Intended for
     * use by a host's stdout-capture machinery (see
     * `StdoutCapture`).  Framework code emitting `StdOutLine`
     * through the normal `SharedFlow` does not need this method.
     *
     * @param text the captured line (without trailing newline).
     * @param fromErr `true` for `System.err` lines (rendered as ERROR
     *   severity), `false` for `System.out` (INFO severity).
     */
    fun injectStdOutLine(text: String, fromErr: Boolean) {
        val event = RunEvent.StdOutLine(text, fromErr)
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            onEvent(event)
        } else {
            javax.swing.SwingUtilities.invokeLater { onEvent(event) }
        }
    }

    /**
     * Register a callback fired after each [RunEvent] has been
     * processed by the panel (appended to the buffer and, if it
     * passes the active filters, rendered).  Use to drive external
     * counters/summaries from the same single thread of control that
     * owns the buffer, avoiding races against a parallel collector.
     * Runs on the EDT.
     */
    fun addAfterEventListener(listener: (RunEvent) -> Unit) {
        afterEventListeners.add(listener)
    }

    private fun onEvent(event: RunEvent) {
        if (autoClearOnRunStart && event is RunEvent.Started) {
            clearConsole()
        }
        buffer.add(event)
        if (buffer.size > MAX_BUFFER_SIZE) {
            // Soft cap exceeded — drop a batch of oldest entries at
            // once (amortizes the rebuild cost over BUFFER_TRIM_BATCH
            // events so it isn't O(N) per event past the cap) and
            // re-render so the "earlier lines dropped" header refreshes.
            repeat(BUFFER_TRIM_BATCH) {
                if (buffer.isNotEmpty()) buffer.removeFirst()
            }
            droppedCount += BUFFER_TRIM_BATCH
            rebuild()
        } else if (passesFilters(event)) {
            appendLine(event)
        }
        for (l in afterEventListeners) l(event)
    }

    private fun passesFilters(event: RunEvent): Boolean =
        severityOf(event) in enabledSeverities && categoryOf(event) in enabledCategories

    private fun appendLine(event: RunEvent) {
        val atBottom = isScrolledToBottom()
        val severity = severityOf(event)
        val doc = textPane.styledDocument
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, colorFor(severity))
        try {
            doc.insertString(doc.length, "[${severity.name}] ${formatter.format(event)}\n", attrs)
        } catch (_: javax.swing.text.BadLocationException) {
            // Defensive: shouldn't happen with insert-at-length.
        }
        if (atBottom) textPane.caretPosition = doc.length
    }

    private fun isScrolledToBottom(): Boolean {
        val sb = scrollPane.verticalScrollBar
        // Consider "at bottom" when the visible window's end is within a few px
        // of the scrollbar's maximum — covers the just-added-content race.
        return sb.value + sb.visibleAmount >= sb.maximum - SCROLL_EPSILON
    }

    private fun onSeverityToggle(severity: ConsoleSeverity, enabled: Boolean) {
        if (enabled) enabledSeverities.add(severity) else enabledSeverities.remove(severity)
        rebuild()
    }

    private fun onCategoryToggle(category: ConsoleCategory, enabled: Boolean) {
        if (enabled) enabledCategories.add(category) else enabledCategories.remove(category)
        rebuild()
    }

    private fun rebuild() {
        textPane.text = ""
        if (droppedCount > 0) appendDroppedNotice()
        for (event in buffer) if (passesFilters(event)) appendLine(event)
    }

    /**
     * Insert a dimmed italic notice at the current end of the document
     * telling the user the buffer has dropped older entries.  Always
     * called from [rebuild]; the buffer-trim path goes through rebuild
     * so this line is always at the top of a freshly rendered view.
     */
    private fun appendDroppedNotice() {
        val doc = textPane.styledDocument
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, Color(0x88, 0x88, 0x88))
        StyleConstants.setItalic(attrs, true)
        try {
            doc.insertString(
                doc.length,
                "… $droppedCount earlier line(s) dropped (buffer capped at $MAX_BUFFER_SIZE)\n",
                attrs
            )
        } catch (_: javax.swing.text.BadLocationException) {
            // Defensive: shouldn't happen with insert-at-length.
        }
    }

    private fun clearConsole() {
        buffer.clear()
        textPane.text = ""
        droppedCount = 0
        for (l in onClearListeners) l()
    }

    private fun colorFor(severity: ConsoleSeverity): Color = when (severity) {
        ConsoleSeverity.INFO -> Color(0x33, 0x33, 0x33)
        ConsoleSeverity.WARNING -> Color(0xEF, 0x6C, 0x00)
        ConsoleSeverity.ERROR -> Color(0xC6, 0x28, 0x28)
    }

    /** Test-only: number of events dropped from the head due to the buffer cap. */
    internal val droppedCountForTest: Int get() = droppedCount

    /** Test-only: current buffer size (events not yet dropped). */
    internal val bufferSizeForTest: Int get() = buffer.size

    companion object {
        private const val SCROLL_EPSILON: Int = 10

        /**
         * Fixed width of the filter-rail buttons.  Sized to comfortably
         * fit "WARN" / "Life" / "Clear" — the longest abbreviated label.
         */
        private const val RAIL_BUTTON_WIDTH: Int = 64

        /**
         * Soft cap on the in-memory event buffer.  When exceeded, the
         * oldest [BUFFER_TRIM_BATCH] entries are dropped at once.  Sized
         * so a chatty model (`println` on every replication × 30 reps
         * × 500 events ≈ 15 000 lines) overflows only a few times in
         * a typical run — keeping the dropped-count footer visible
         * but not alarming.  Memory footprint ~10 K events × small
         * `RunEvent` data class ≈ a couple of MB.
         */
        const val MAX_BUFFER_SIZE: Int = 10_000

        /**
         * Number of oldest entries discarded in one trim pass when the
         * buffer hits the cap.  Larger than 1 so we don't pay the
         * rebuild cost on every event past the cap — amortized cost
         * per event is O(1) when this is significant.
         */
        const val BUFFER_TRIM_BATCH: Int = 1_000

        /** Short chip label for a severity bucket. */
        private fun severityLabel(s: ConsoleSeverity): String = when (s) {
            ConsoleSeverity.INFO -> "INFO"
            ConsoleSeverity.WARNING -> "WARN"
            ConsoleSeverity.ERROR -> "ERR"
        }

        /** Short chip label for a category bucket. */
        private fun categoryLabel(c: ConsoleCategory): String = when (c) {
            ConsoleCategory.LIFECYCLE -> "Life"
            ConsoleCategory.REPLICATION -> "Rep"
            ConsoleCategory.ORCHESTRATOR -> "Orch"
            ConsoleCategory.STDOUT -> "Out"
        }

        /** Severity classification for a [RunEvent]. */
        fun severityOf(event: RunEvent): ConsoleSeverity = when (event) {
            is RunEvent.RunFailed -> ConsoleSeverity.ERROR
            is RunEvent.RunCancelled -> ConsoleSeverity.WARNING
            is RunEvent.RunWarning -> ConsoleSeverity.WARNING
            is RunEvent.ScenarioCompleted -> if (event.snapshot == null) ConsoleSeverity.ERROR else ConsoleSeverity.INFO
            is RunEvent.DesignPointCompleted -> if (event.snapshot == null) ConsoleSeverity.ERROR else ConsoleSeverity.INFO
            is RunEvent.StdOutLine -> if (event.fromErr) ConsoleSeverity.ERROR else ConsoleSeverity.INFO
            else -> ConsoleSeverity.INFO
        }

        /** Category classification for a [RunEvent]. */
        fun categoryOf(event: RunEvent): ConsoleCategory = when (event) {
            is RunEvent.ReplicationStarted,
            is RunEvent.ReplicationEnded,
            is RunEvent.SimTimeAdvanced -> ConsoleCategory.REPLICATION
            is RunEvent.ScenarioCompleted,
            is RunEvent.DesignPointCompleted,
            is RunEvent.IterationCompleted -> ConsoleCategory.ORCHESTRATOR
            is RunEvent.StdOutLine -> ConsoleCategory.STDOUT
            else -> ConsoleCategory.LIFECYCLE
        }
    }
}
