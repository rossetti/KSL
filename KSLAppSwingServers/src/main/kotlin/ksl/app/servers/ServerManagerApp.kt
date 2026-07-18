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

package ksl.app.servers

import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.system.exitProcess

private const val APP_NAME = "KSL Server Manager"

/**
 * The KSL Server Manager window: manage the one shared MCP suite for students — configure/unconfigure
 * coding agents, start/stop the suite, watch its health, and clean up leaked processes. A thin Swing
 * shell over `ServerManagerController`; every action runs off the EDT and reports into a read-only
 * console, matching the other KSL apps' look/feel via the shared FlatLaf theme.
 */
class ServerManagerFrame(private val controller: ServerManagerController) : JFrame(controller.appName) {

    private val output = JTextArea(20, 74).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    init {
        val header = JLabel(controller.appName).apply { font = font.deriveFont(Font.BOLD, 15f) }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(action("Refresh") { controller.statusReport() })
            add(action("Configure clients") { controller.configureClients() })
            add(action("Remove config") { controller.removeConfig() })
            add(action("Start suite") { controller.startSuite() })
            add(action("Stop suite") { controller.stopSuite() })
            add(action("Clean up orphans") { controller.cleanupOrphans() })
        }
        contentPane = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(header, BorderLayout.NORTH)
            add(buttons, BorderLayout.CENTER)
            add(JScrollPane(output), BorderLayout.SOUTH)
        }
        // Distribution-app idiom: run cleanup on close, then force exit (a Swing app can otherwise
        // linger on the non-daemon EDT thread). The shared suite keeps running independently.
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                controller.dispose()
                dispose()
                exitProcess(0)
            }
        })
        minimumSize = Dimension(660, 460)
        runAsync { controller.statusReport() } // populate the console on open
    }

    private fun action(label: String, task: () -> String): JButton =
        JButton(label).apply { addActionListener { runAsync(task) } }

    private fun runAsync(task: () -> String) {
        output.text = "working…"
        Thread {
            val report = runCatching { task() }.getOrElse { "Error: ${it.message}" }
            SwingUtilities.invokeLater {
                output.text = report
                output.caretPosition = 0
            }
        }.apply { isDaemon = true }.start()
    }
}

fun main() {
    LookAndFeel.install(theme = AppTheme.SYSTEM, appName = APP_NAME)
    SwingUtilities.invokeLater {
        ServerManagerFrame(ServerManagerController(APP_NAME)).apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
