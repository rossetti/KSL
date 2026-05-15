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

package ksl.app.swing.common.validation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionListener
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * Top-of-editor banner summarising the document's validation state.
 * Hidden entirely when [ValidationResult.isValid] and no warnings
 * are present.  Otherwise shows a summary line and an expandable
 * detail panel grouped by surface (bundles, scenarios,
 * document-level).
 *
 * Each detail entry carries a *Jump to source* button that focuses
 * the widget registered for its `FieldError.path` via [registry].
 * The banner does not subclass `JumpToErrorAction`; it shares
 * `JumpUtil.jumpTo`.  When no widget is registered for a path
 * (e.g. a collapsed panel), [onMissingWidget] is invoked and the
 * banner does no further work — the caller decides whether to
 * surface a fallback.
 *
 * Persistent posture per scenario workflow §4 OQ 3: the banner
 * stays visible while issues remain; the chevron toggles the
 * detail panel, not the banner itself.
 *
 * @param bus source of validation state.
 * @param registry resolves issue paths to focusable widgets.
 * @param scope owns the flow subscription.
 * @param onMissingWidget invoked when *Jump to source* targets a
 *   path that has no registered widget.
 */
class DocumentHealthBanner(
    private val bus: ValidationFeedbackBus,
    private val registry: WidgetPathRegistry,
    scope: CoroutineScope,
    private val onMissingWidget: (FieldError) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val summaryLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
    }
    private val toggleButton = JButton("Show details ▼").apply {
        isFocusPainted = false
        isContentAreaFilled = false
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }
    private val detailPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    }
    private val detailScroll = JScrollPane(
        detailPanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE0, 0xE0, 0xE0))
        preferredSize = Dimension(0, 160)
        isVisible = false
    }
    private var expanded: Boolean = false

    init {
        background = BANNER_BG
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        )

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(summaryLabel, BorderLayout.CENTER)
            add(toggleButton, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(detailScroll, BorderLayout.CENTER)
        toggleButton.addActionListener { setExpanded(!expanded) }

        scope.launch(Dispatchers.Swing) {
            bus.result
                .onEach { apply(it) }
                .collect { /* no-op terminal */ }
        }
        apply(bus.result.value)
    }

    private fun apply(result: ValidationResult) {
        val errorCount = result.errors.size
        val warningCount = result.warnings.size
        if (errorCount == 0 && warningCount == 0) {
            isVisible = false
            return
        }
        isVisible = true
        summaryLabel.text = buildSummaryText(errorCount, warningCount)
        rebuildDetails(result)
    }

    private fun buildSummaryText(errorCount: Int, warningCount: Int): String {
        val parts = buildList {
            if (errorCount > 0) add("<span style='color:#C62828'>$errorCount " + plural(errorCount, "error") + "</span>")
            if (warningCount > 0) add("<span style='color:#EF6C00'>$warningCount " + plural(warningCount, "warning") + "</span>")
        }
        return "<html>${parts.joinToString(" · ")}</html>"
    }

    private fun rebuildDetails(result: ValidationResult) {
        detailPanel.removeAll()
        val grouped = groupBySurface(result.allIssues)
        var firstGroup = true
        for ((surface, issues) in grouped) {
            if (!firstGroup) detailPanel.add(Box.createVerticalStrut(6))
            firstGroup = false
            detailPanel.add(makeGroupHeader(surface, issues.size))
            for (issue in issues) detailPanel.add(makeEntryRow(issue))
        }
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun groupBySurface(issues: List<FieldError>): Map<Surface, List<FieldError>> {
        val out = linkedMapOf<Surface, MutableList<FieldError>>()
        for (issue in issues) {
            val key = Surface.of(issue.path)
            out.getOrPut(key) { mutableListOf() }.add(issue)
        }
        return out
    }

    private fun makeGroupHeader(surface: Surface, count: Int): JComponentRow {
        val label = JLabel("${surface.displayName} ($count)").apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        }
        return JComponentRow().apply { add(label, BorderLayout.WEST) }
    }

    private fun makeEntryRow(issue: FieldError): JComponentRow {
        val row = JComponentRow()
        val severityLabel = JLabel(SeverityIcon(issue.severity))
        severityLabel.border = BorderFactory.createEmptyBorder(0, 4, 0, 6)
        val message = JLabel("<html>${escape(issue.message)} <span style='color:#777'>(${escape(issue.path)})</span></html>")
        val jumpButton = JButton("Jump to source").apply {
            isFocusPainted = false
            addActionListener(jumpListener(issue))
        }
        row.add(severityLabel, BorderLayout.WEST)
        row.add(message, BorderLayout.CENTER)
        row.add(jumpButton, BorderLayout.EAST)
        return row
    }

    private fun jumpListener(issue: FieldError): ActionListener = ActionListener {
        if (!JumpUtil.jumpTo(registry, issue.path)) onMissingWidget(issue)
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        detailScroll.isVisible = value
        toggleButton.text = if (value) "Hide details ▲" else "Show details ▼"
        revalidate()
        repaint()
    }

    private fun plural(n: Int, word: String): String = if (n == 1) word else "${word}s"

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Test-only accessor for the rendered entry rows.  Each row is a
     * `JPanel`; the entry rows are the children of the inner detail
     * panel after the first group-header row.
     */
    internal fun detailRowsForTest(): List<Component> = detailPanel.components.toList()

    /** Test-only: whether the detail panel is currently expanded. */
    internal fun isExpandedForTest(): Boolean = expanded

    /** Test-only: programmatically toggle the expand state. */
    internal fun toggleForTest() {
        setExpanded(!expanded)
    }

    private class JComponentRow : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        }
    }

    private enum class Surface(val displayName: String, private val matcher: (String) -> Boolean) {
        Bundles("Bundles", { it.startsWith("bundleRefs") }),
        Scenarios("Scenarios", { it.startsWith("scenarios") }),
        DocumentLevel("Document", { _ -> true });

        companion object {
            fun of(path: String): Surface =
                Bundles.takeIf { it.matcher(path) }
                    ?: Scenarios.takeIf { it.matcher(path) }
                    ?: DocumentLevel
        }
    }

    companion object {
        private val BANNER_BG: Color = Color(0xFF, 0xF8, 0xE1)  // soft amber wash
    }
}
