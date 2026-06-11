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

package ksl.app.swing.common.bundle

import ksl.app.bundle.LoadedBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 *  Read-only modal listing of every loaded bundle and its models.
 *  Each bundle block shows: display name + bundle id, version, source
 *  (the JAR path, or "classpath" for a classpath-discovered bundle) and
 *  one indented row per model with `displayName`, `modelId`,
 *  `description`, and the `supportedApps` set.  The version + source
 *  disambiguate copies of the same bundle loaded from more than one JAR.
 *
 *  Shared by every bundle-driven app (Single / Scenario / Experiment /
 *  Simopt) so the *Bundles → Loaded Bundles…* menu item is identical
 *  across them.
 */
object LoadedBundlesDialog {

    fun show(parent: Component, bundles: List<LoadedBundle>) {
        val owner = SwingUtilities.getWindowAncestor(parent)
        val dlg = JDialog(owner, "Loaded Bundles", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        dlg.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dlg.contentPane.layout = BorderLayout()
        dlg.contentPane.add(buildBody(bundles), BorderLayout.CENTER)
        dlg.contentPane.add(buildButtons(dlg), BorderLayout.SOUTH)
        dlg.pack()
        dlg.minimumSize = Dimension(480, 320)
        dlg.setLocationRelativeTo(parent)
        dlg.isVisible = true
    }

    private fun buildBody(bundles: List<LoadedBundle>): JPanel {
        val text = JTextArea().apply {
            isEditable = false
            lineWrap = false
            text = renderText(bundles)
            caretPosition = 0
            font = font.deriveFont(font.size2D)
        }
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 6, 12)
            add(JLabel(summaryLine(bundles)).apply {
                border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            }, BorderLayout.NORTH)
            add(JScrollPane(text).apply { preferredSize = Dimension(560, 320) }, BorderLayout.CENTER)
        }
    }

    private fun buildButtons(dlg: JDialog): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
        add(Box.createHorizontalGlue())
        add(JButton("Close").apply { addActionListener { dlg.dispose() } })
    }

    private fun summaryLine(bundles: List<LoadedBundle>): String {
        val totalModels = bundles.sumOf { it.bundle.models.size }
        return "${bundles.size} bundle${if (bundles.size == 1) "" else "s"} · " +
            "$totalModels model${if (totalModels == 1) "" else "s"}"
    }

    private fun renderText(bundles: List<LoadedBundle>): String {
        if (bundles.isEmpty()) return "No bundles loaded."
        return buildString {
            for ((i, lb) in bundles.withIndex()) {
                if (i > 0) appendLine()
                appendLine("Bundle: ${lb.bundle.displayName}  [${lb.bundle.bundleId}]")
                appendLine("  Version: ${lb.bundle.version}")
                appendLine("  Source: ${lb.sourceJar?.toString() ?: "classpath"}")
                if (lb.bundle.models.isEmpty()) {
                    appendLine("  (no models declared)")
                } else {
                    appendLine("  Models:")
                    for (m in lb.bundle.models) {
                        appendLine("    • ${m.displayName}  [${m.modelId}]")
                        if (m.description.isNotBlank()) {
                            appendLine("        ${m.description}")
                        }
                        appendLine("        supports: ${m.supportedApps.joinToString(", ")}")
                    }
                }
            }
        }
    }
}
