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

package ksl.book.mcp

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * A minimal no-terminal setup window, ported from KSLServerMcp. Double-clicking
 * the jar opens this; the buttons delegate to the same tested logic the console
 * modes use ([AgentSetup], [buildSetupReport] / [buildDoctorReport] /
 * [buildRemoveReport]), so GUI and console never diverge.
 */
internal object SetupGui {

    fun launch(jarPath: String) {
        if (GraphicsEnvironment.isHeadless()) {
            // No display (e.g. SSH / server) — fall back to console setup.
            println(buildSetupReport(jarPath, AgentSetup.configureDetected(jarPath)))
            return
        }
        SwingUtilities.invokeLater {
            val output = JTextArea(18, 64).apply {
                isEditable = false
                lineWrap = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                text = "Click \"Configure my coding agent\" to wire up Claude Desktop / Codex,\n" +
                    "then restart your agent and ask it about the KSL textbook.\n\n" +
                    "Universal snippet (for any other agent):\n\n" +
                    AgentSetup.universalSnippet(jarPath)
            }

            fun show(report: String) { output.text = report; output.caretPosition = 0 }

            val configure = JButton("Configure my coding agent").apply {
                addActionListener { show(buildSetupReport(jarPath, AgentSetup.configureDetected(jarPath))) }
            }
            val selfTest = JButton("Self-test").apply {
                addActionListener { show(buildDoctorReport()) }
            }
            val remove = JButton("Remove KSL Book").apply {
                addActionListener { show(buildRemoveReport(AgentSetup.removeDetected())) }
            }

            val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(configure); add(selfTest); add(remove)
            }
            val header = JLabel("KSL Book MCP Server - Setup").apply {
                font = font.deriveFont(Font.BOLD, 15f)
                border = BorderFactory.createEmptyBorder(10, 10, 4, 10)
            }
            val content = JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
                add(header, BorderLayout.NORTH)
                add(buttons, BorderLayout.CENTER)
                add(JScrollPane(output), BorderLayout.SOUTH)
            }

            JFrame("KSL Book MCP Server - Setup").apply {
                defaultCloseOperation = JFrame.EXIT_ON_CLOSE
                contentPane = content
                minimumSize = Dimension(560, 360)
                pack()
                setLocationRelativeTo(null)
                isVisible = true
            }
        }
    }
}
