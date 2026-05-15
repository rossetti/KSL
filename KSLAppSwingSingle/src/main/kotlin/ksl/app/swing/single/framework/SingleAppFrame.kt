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

package ksl.app.swing.single.framework

import kotlinx.coroutines.launch
import ksl.app.session.RunResult
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.common.notification.Notifications
import ksl.app.swing.common.runcontrol.ConsoleLogPanel
import ksl.app.swing.common.validation.DocumentHealthBanner
import ksl.app.swing.common.validation.WidgetPathRegistry
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.WindowConstants

/**
 * Default top-level frame for a `kslSingleApp(...)` instance.
 * Composes Common widgets:
 *
 *  - Menu bar with **File** (Set Working Directory…, Recent
 *    Working Directories ▶, Exit).
 *  - [DocumentHealthBanner] at the top (currently empty until
 *    N2 wires the parameter panel's validation source).
 *  - Centre placeholder + Run/Cancel button.  N2 replaces the
 *    placeholder with an editable parameter panel; N3 adds a
 *    result panel after `RunCompleted`.
 *  - [ConsoleLogPanel] at the bottom collecting the controller's
 *    [SingleAppController.eventFlow].
 *  - [WorkspaceStatusBar] strip beneath the console.
 *  - [Notifications] overlay attached to the frame's layered
 *    pane; surfaces run-start / completion / cancel / failure
 *    toasts.
 *
 * Lifecycle: closing the window closes the [SingleAppController],
 * which cancels any in-flight run and shuts the session down.
 */
class SingleAppFrame(
    private val controller: SingleAppController
) : JFrame(controller.appName) {

    private val notifications: Notifications = Notifications(rootPane.layeredPane)
    private val registry: WidgetPathRegistry = WidgetPathRegistry()

    private val runAction = object : AbstractAction("Run") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) { controller.submit() }
    }
    private val cancelAction = object : AbstractAction("Cancel") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) { controller.cancel() }
    }.apply { isEnabled = false }

    private val placeholderLabel = JLabel(
        "<html><div style='text-align:center'>" +
            "<b>${escape(controller.appName)}</b><br/>" +
            "Click <b>Run</b> to execute the model.<br/>" +
            "<i>An editable parameter panel will appear here in a later phase.</i>" +
            "</div></html>",
        JLabel.CENTER
    )

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(880, 640)

        jMenuBar = buildMenuBar()

        val banner = DocumentHealthBanner(controller.validationBus, registry, controller.edtScope)
        val centre = buildCentre()
        val console = ConsoleLogPanel(controller.eventFlow, controller.edtScope).apply {
            preferredSize = Dimension(0, 180)
        }
        val statusBar = WorkspaceStatusBar(
            store = controller.settingsStore,
            scope = controller.edtScope,
            onSetWorkingDirectory = {
                SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
                    .actionPerformed(java.awt.event.ActionEvent(this, 0, ""))
            }
        )

        val bottom = JPanel(BorderLayout()).apply {
            add(console, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }

        contentPane.apply {
            layout = BorderLayout()
            add(banner, BorderLayout.NORTH)
            add(centre, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }

        wireRunningState()
        wireTerminalNotifications()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) { controller.close() }
        })
    }

    private fun buildMenuBar(): JMenuBar {
        val setWdAction = SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
        val recentMenu = RecentWorkingDirectoriesMenu(controller.settingsStore, controller.edtScope)
        return JMenuBar().apply {
            add(JMenu("File").apply {
                add(JMenuItem(setWdAction))
                add(recentMenu)
                addSeparator()
                add(JMenuItem("Exit").apply { addActionListener { dispose() } })
            })
        }
    }

    private fun buildCentre(): JPanel {
        val runStrip = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            add(Box.createHorizontalGlue())
            add(JButton(runAction))
            add(Box.createHorizontalStrut(8))
            add(JButton(cancelAction))
            add(Box.createHorizontalGlue())
        }
        return JPanel(BorderLayout()).apply {
            add(placeholderLabel, BorderLayout.CENTER)
            add(runStrip, BorderLayout.SOUTH)
        }
    }

    private fun wireRunningState() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                runAction.isEnabled = !running
                cancelAction.isEnabled = running
                if (running) {
                    notifications.show("Run started", NotificationSeverity.INFO)
                }
            }
        }
    }

    private fun wireTerminalNotifications() {
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                when (result) {
                    null -> { /* no result yet */ }
                    is RunResult.Completed ->
                        notifications.show("Run completed", NotificationSeverity.INFO)
                    is RunResult.Cancelled ->
                        notifications.show("Run cancelled: ${result.reason}", NotificationSeverity.WARNING)
                    is RunResult.Failed ->
                        notifications.show("Run failed: ${result.error}", NotificationSeverity.ERROR)
                    is RunResult.BatchCompleted ->
                        notifications.show("Batch completed", NotificationSeverity.INFO)
                    else ->
                        notifications.show("Run finished: ${result::class.simpleName}", NotificationSeverity.INFO)
                }
            }
        }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
