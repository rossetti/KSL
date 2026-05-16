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
import ksl.app.swing.single.framework.defaults.DefaultParameterPanel
import ksl.app.swing.single.framework.defaults.DefaultResultPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JScrollPane
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

    private val parameterPanel = DefaultParameterPanel(controller)

    private val cardLayout = CardLayout()
    private val cardContainer = JPanel(cardLayout)
    private val resultSlot = JPanel(BorderLayout())   // replaced fresh on each terminal state
    private var lastRenderedResult: RunResult? = null

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
        surfaceProbeFailureIfPresent()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) { controller.close() }
        })
    }

    private fun surfaceProbeFailureIfPresent() {
        val cause = controller.probeFailure ?: return
        controller.edtScope.launch {
            // Defer one tick so the frame is visible before the notification appears.
            javax.swing.SwingUtilities.invokeLater {
                notifications.show(
                    ksl.app.swing.common.notification.NotificationSpec(
                        message = "Model builder probe failed (using safe defaults): " +
                            (cause.message ?: cause::class.simpleName ?: "unknown error"),
                        severity = NotificationSeverity.ERROR,
                        dismissAfter = null
                    )
                )
            }
        }
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
        val scrollablePanel = JScrollPane(parameterPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        val parameterCard = JPanel(BorderLayout()).apply {
            add(scrollablePanel, BorderLayout.CENTER)
            add(runStrip, BorderLayout.SOUTH)
        }
        cardContainer.add(parameterCard, CARD_PARAMETER)
        cardContainer.add(resultSlot, CARD_RESULT)
        cardLayout.show(cardContainer, CARD_PARAMETER)
        return cardContainer
    }

    private fun showResultCard(result: RunResult) {
        lastRenderedResult = result
        val panel = DefaultResultPanel(
            result = result,
            onBack = { showParameterCard() },
            onPlaceholderArtifact = { label ->
                notifications.show(
                    "$label is not yet wired (requires OutputConfig orchestrator threading — later N-commit).",
                    NotificationSeverity.WARNING
                )
            }
        )
        resultSlot.removeAll()
        resultSlot.add(panel, BorderLayout.CENTER)
        resultSlot.revalidate()
        resultSlot.repaint()
        cardLayout.show(cardContainer, CARD_RESULT)
    }

    private fun showParameterCard() {
        cardLayout.show(cardContainer, CARD_PARAMETER)
        resultSlot.removeAll()
        lastRenderedResult = null
    }

    private fun wireRunningState() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                runAction.isEnabled = !running
                cancelAction.isEnabled = running
                parameterPanel.isEnabled = !running
                if (running) {
                    notifications.show("Run started", NotificationSeverity.INFO)
                }
            }
        }
    }

    private fun wireTerminalNotifications() {
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                if (result == null || result === lastRenderedResult) return@collect
                when (result) {
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
                showResultCard(result)
            }
        }
    }

    companion object {
        private const val CARD_PARAMETER: String = "parameter"
        private const val CARD_RESULT: String = "result"
    }
}
