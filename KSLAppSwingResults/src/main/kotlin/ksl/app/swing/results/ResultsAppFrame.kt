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

package ksl.app.swing.results

import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSink
import ksl.app.notification.NotificationSpec
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import ksl.app.swing.results.panel.CompareExperimentsPanel
import ksl.app.swing.results.panel.DatabasePanel
import ksl.app.swing.results.panel.ExperimentSummaryPanel
import ksl.app.swing.results.panel.HistogramFrequencyPanel
import ksl.app.swing.results.panel.TimeSeriesPanel
import ksl.app.swing.results.panel.WithinReplicationPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 *  Top-level frame for the Results app — a report-first analysis
 *  workbench over a saved [ksl.utilities.io.dbutil.KSLDatabase].
 *
 *  The frame is a thin binder over [ResultsAppController]: a header
 *  with the *Open Database…* action and a one-line database summary,
 *  a [JTabbedPane] of analysis tabs, and a status line fed by a
 *  [NotificationSink] that the embedded panels emit through.  No
 *  analysis logic lives here; each tab drives a substrate entry point.
 *
 *  R4a ships the **Database** navigator and the **Compare Experiments**
 *  tab (the cross-experiment MCB / box / CI showcase).  The remaining
 *  tabs (Within-Replication, Time Series, Experiment Summary) and the
 *  export affordances land in later sub-phases.
 */
class ResultsAppFrame(private val controller: ResultsAppController) : JFrame() {

    private val tabs = JTabbedPane()
    private val dbSummaryLabel = JLabel("No database open")
    private val statusLabel = JLabel("Ready")

    /** Status-bar sink: panels emit notifications here; the frame
     *  renders them on the event-dispatch thread, coloured by severity.
     *  Safe to call from any thread per the [NotificationSink] contract. */
    private val notifier: NotificationSink = object : NotificationSink {
        override fun emit(spec: NotificationSpec) {
            SwingUtilities.invokeLater {
                statusLabel.text = spec.message
                statusLabel.foreground = when (spec.severity) {
                    NotificationSeverity.ERROR -> ERROR_COLOR
                    NotificationSeverity.WARNING -> WARN_COLOR
                    else -> defaultStatusColor
                }
            }
        }
    }

    private val defaultStatusColor: Color = statusLabel.foreground
    private val databasePanel = DatabasePanel(controller)
    private val comparePanel = CompareExperimentsPanel(controller, notifier)
    private val withinReplicationPanel = WithinReplicationPanel(controller, notifier)
    private val timeSeriesPanel = TimeSeriesPanel(controller, notifier)
    private val experimentSummaryPanel = ExperimentSummaryPanel(controller, notifier)
    private val histogramFrequencyPanel = HistogramFrequencyPanel(controller, notifier)

    /** Scope owning the workspace status bar's subscription to the
     *  settings store; cancelled when the window closes. */
    private val uiScope = CoroutineScope(Dispatchers.Swing)
    private val setWorkingDirectoryAction =
        SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
    private val workspaceStatusBar = WorkspaceStatusBar(
        controller.settingsStore,
        uiScope,
        onSetWorkingDirectory = { setWorkingDirectoryAction.actionPerformed(null) }
    )

    init {
        title = controller.appName
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        layout = BorderLayout()
        jMenuBar = buildMenuBar()
        add(buildHeader(), BorderLayout.NORTH)
        add(buildTabs(), BorderLayout.CENTER)
        add(buildStatusBar(), BorderLayout.SOUTH)
        preferredSize = Dimension(1040, 700)

        controller.addListener { onDatabaseChanged() }
    }

    // ── Construction ──────────────────────────────────────────────────────

    private fun buildMenuBar(): JMenuBar {
        val bar = JMenuBar()
        val file = JMenu("File").apply { mnemonic = KeyEvent.VK_F }
        file.add(JMenuItem("Open Database…").apply {
            addActionListener { openDatabase() }
            // Cmd+O on macOS, Ctrl+O elsewhere.
            accelerator = KeyStroke.getKeyStroke(
                KeyEvent.VK_O,
                Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
            )
        })
        file.add(JMenuItem(setWorkingDirectoryAction))
        file.addSeparator()
        file.add(JMenuItem("Exit").apply { addActionListener { dispose() } })
        bar.add(file)

        val help = JMenu("Help").apply { mnemonic = KeyEvent.VK_H }
        help.add(JMenuItem("About").apply { addActionListener { showAbout() } })
        bar.add(help)
        return bar
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        val openButton = JButton("Open Database…").apply { addActionListener { openDatabase() } }
        add(openButton, BorderLayout.WEST)
        add(dbSummaryLabel, BorderLayout.CENTER)
        dbSummaryLabel.border = BorderFactory.createEmptyBorder(0, 12, 0, 0)
    }

    private fun buildTabs(): JTabbedPane {
        tabs.addTab("Database", databasePanel)
        tabs.addTab("Compare Experiments", comparePanel)
        tabs.addTab("Within-Replication", withinReplicationPanel)
        tabs.addTab("Time Series", timeSeriesPanel)
        tabs.addTab("Histograms & Frequencies", histogramFrequencyPanel)
        tabs.addTab("Experiment Summary", experimentSummaryPanel)
        return tabs
    }

    private fun buildStatusBar(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(4, 10, 4, 10)
        )
        add(statusLabel, BorderLayout.WEST)
        // Working-directory indicator — shows the remembered workspace and
        // live-updates when the user changes it via Set Working Directory.
        add(workspaceStatusBar, BorderLayout.EAST)
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun openDatabase() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Open KSL Database (SQLite .db file or Derby directory)"
            // Derby databases are directories; SQLite databases are files.
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        try {
            controller.openDatabase(file)
            notifier.info("Opened ${controller.databaseSummary()}")
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(
                this,
                "Could not open '${file.name}' as a KSL database:\n${t.message ?: t::class.simpleName}",
                "Open Database",
                JOptionPane.ERROR_MESSAGE
            )
            notifier.error("Failed to open ${file.name}")
        }
    }

    private fun onDatabaseChanged() {
        dbSummaryLabel.text = controller.databaseSummary()
        title = controller.databaseFile?.let { "${controller.appName} — ${it.name}" } ?: controller.appName
    }

    private fun showAbout() {
        JOptionPane.showMessageDialog(
            this,
            "${controller.appName}\n\nA report-first analysis workbench over a saved KSL database.\n" +
                "Open a database, then use the analysis tabs to generate reports.",
            "About",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private companion object {
        val ERROR_COLOR = Color(0xC6, 0x28, 0x28)
        val WARN_COLOR = Color(0xE6, 0x51, 0x00)
    }
}
