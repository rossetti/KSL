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
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
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
enum class ConsoleCategory { LIFECYCLE, REPLICATION, ORCHESTRATOR }

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
 */
class ConsoleLogPanel(
    eventFlow: SharedFlow<RunEvent>,
    scope: CoroutineScope,
    private val formatter: EventFormatter = DefaultEventFormatter
) : JPanel(BorderLayout()) {

    private val buffer: MutableList<RunEvent> = mutableListOf()
    private val enabledSeverities: MutableSet<ConsoleSeverity> = ConsoleSeverity.values().toMutableSet()
    private val enabledCategories: MutableSet<ConsoleCategory> = ConsoleCategory.values().toMutableSet()

    private val textPane = javax.swing.JTextPane().apply {
        isEditable = false
        background = Color.WHITE
    }
    private val scrollPane = JScrollPane(textPane).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val severityChips = ConsoleSeverity.values().associateWith { severity ->
        JToggleButton(severity.name).apply {
            isSelected = true
            isFocusable = false
            addActionListener { onSeverityToggle(severity, this.isSelected) }
        }
    }
    private val categoryChips = ConsoleCategory.values().associateWith { category ->
        JToggleButton(category.name).apply {
            isSelected = true
            isFocusable = false
            addActionListener { onCategoryToggle(category, this.isSelected) }
        }
    }
    private val clearButton = JButton("Clear Console").apply {
        isFocusable = false
        addActionListener { clearConsole() }
    }

    init {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC))
        add(buildHeader(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        scope.launch(Dispatchers.Swing) {
            eventFlow
                .onEach { onEvent(it) }
                .collect { /* no-op terminal */ }
        }
    }

    private fun buildHeader(): JPanel {
        val header = JPanel(BorderLayout())
        header.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("Console", SwingConstants.LEFT))
            for (chip in severityChips.values) add(chip)
            for (chip in categoryChips.values) add(chip)
        }
        header.add(left, BorderLayout.CENTER)
        header.add(clearButton, BorderLayout.EAST)
        return header
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

    private fun onEvent(event: RunEvent) {
        buffer.add(event)
        if (passesFilters(event)) appendLine(event)
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
        for (event in buffer) if (passesFilters(event)) appendLine(event)
    }

    private fun clearConsole() {
        buffer.clear()
        textPane.text = ""
    }

    private fun colorFor(severity: ConsoleSeverity): Color = when (severity) {
        ConsoleSeverity.INFO -> Color(0x33, 0x33, 0x33)
        ConsoleSeverity.WARNING -> Color(0xEF, 0x6C, 0x00)
        ConsoleSeverity.ERROR -> Color(0xC6, 0x28, 0x28)
    }

    companion object {
        private const val SCROLL_EPSILON: Int = 10

        /** Severity classification for a [RunEvent]. */
        fun severityOf(event: RunEvent): ConsoleSeverity = when (event) {
            is RunEvent.RunFailed -> ConsoleSeverity.ERROR
            is RunEvent.RunCancelled -> ConsoleSeverity.WARNING
            is RunEvent.RunWarning -> ConsoleSeverity.WARNING
            is RunEvent.ScenarioCompleted -> if (event.snapshot == null) ConsoleSeverity.ERROR else ConsoleSeverity.INFO
            is RunEvent.DesignPointCompleted -> if (event.snapshot == null) ConsoleSeverity.ERROR else ConsoleSeverity.INFO
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
            else -> ConsoleCategory.LIFECYCLE
        }
    }
}
