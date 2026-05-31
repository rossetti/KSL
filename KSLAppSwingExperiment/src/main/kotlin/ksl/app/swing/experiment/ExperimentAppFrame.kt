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

package ksl.app.swing.experiment

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ksl.app.config.experiment.ExperimentConfigurationToml
import ksl.app.editor.BundleLibraryController
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.batchreports.BatchReportsTabPanel
import ksl.app.comparison.BatchCompletedComparisonSource
import ksl.app.swing.common.comparison.ComparisonAnalyzerFrame
import ksl.app.swing.common.comparison.ComparisonAnalyzerTabPanel
import ksl.app.swing.common.notification.Notifications
import ksl.app.swing.common.runcontrol.ConsoleCategory
import ksl.app.swing.common.runcontrol.ConsoleDrawer
import ksl.app.swing.common.runcontrol.ConsoleLogPanel
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
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
import javax.swing.SwingConstants
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

/**
 *  Default top-level frame for a `kslExperimentApp(...)` instance.
 *
 *  **Phase E4 scope.**  File menu (New / Open / Save / Save As /
 *  Working Directory / Recent / Exit), run toolbar (Simulate / Cancel /
 *  Mode / Analysis / DB), notifications overlay, console drawer,
 *  stale-results banner, and seven empty tab shells.  Tabs are filled
 *  by later phases:
 *
 *  - Model (E5), Factors (E6), Design (E7), Design Points (E8)
 *  - Regression (E9)
 *  - Comparison Analyzer + Reports (E4 wires the existing generic
 *    panels directly — they already handle their own empty-state
 *    cards until a run completes)
 *
 *  Modelled on `ksl.app.swing.scenario.ScenarioAppFrame`.  Shared
 *  patterns (R1 lifecycle, file-state coupling, analysisName-driven
 *  output dir, DatabasePolicy, the SAVE_BASE / SAVE_DIRTY toggle,
 *  defaultSaveAsName, confirmDiscardIfDirty, the stale-results
 *  banner) are reused; experiment-specific changes are documented
 *  inline.
 *
 *  Closing the window closes the [ExperimentAppController].
 */
class ExperimentAppFrame(
    private val controller: ExperimentAppController
) : JFrame(controller.appName) {

    private val notifications: Notifications = Notifications(rootPane.layeredPane)

    private lateinit var saveItem: JMenuItem
    private lateinit var regressionTabPanel: RegressionTabPanel
    private val bundleStatusLabel: JLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    // ── Toolbar widgets ────────────────────────────────────────────────────
    //
    // The toolbar holds output-config concerns (analysisName +
    // databasePolicy) only.  Simulate / Cancel / Mode / Enable-database
    // widgets moved into the Simulate tab as part of the E7.9
    // restructure — see SimulateTabPanel.

    private val analysisNameField = javax.swing.JTextField(
        controller.outputConfig.value.analysisName, 16
    ).apply {
        toolTipText = "Identity for this analysis.  Names the output subdirectory " +
            "<workspace>/output/<analysisName>/ and the SQLite database file.  " +
            "Re-running the same analysis re-uses the same folder."
    }
    private val databasePolicyCombo = javax.swing.JComboBox(
        arrayOf(ksl.app.config.DatabasePolicy.OVERWRITE, ksl.app.config.DatabasePolicy.NEW)
    ).apply {
        selectedItem = controller.outputConfig.value.databasePolicy
        toolTipText = "What to do when the analysis database already exists.  " +
            "OVERWRITE: delete and replace.  NEW: keep the old file, write to " +
            "<analysisName>_<timestamp>.db alongside it."
    }

    // ── Console / drawer ───────────────────────────────────────────────────

    private val consolePanel = ConsoleLogPanel(
        eventFlow = controller.eventFlow,
        scope = controller.edtScope,
        hiddenCategories = setOf(ConsoleCategory.STDOUT)
    )
    private val consoleDrawer = ConsoleDrawer(console = consolePanel, showCaptureToggle = false)

    // ── Stale-results banner ───────────────────────────────────────────────

    private val staleResultsBanner: JLabel = JLabel(
        " Results are from a previous run — the document has been edited since."
    ).apply {
        isOpaque = true
        background = Color(0xFF, 0xF4, 0xCC)
        foreground = Color(0x66, 0x55, 0x00)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xE0, 0xC8, 0x80)),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)
        )
        isVisible = false
    }

    /** One-line live summary of the loaded configuration.  Always
     *  visible above the tab bar so the user can see at a glance
     *  what model / factors / design / reps / streams are loaded
     *  without having to switch through every tab.  Updates on
     *  every relevant StateFlow change.  Hidden when no model is
     *  loaded (nothing meaningful to show).
     *
     *  E7.13 (#1A revised): label sits inside [designSummaryRow]
     *  (a wrapper panel with BorderLayout) so it can fill the
     *  topStack horizontally and left-anchor under the toolbar
     *  rather than centering in the slack.  The wrapper carries
     *  the matte+padding border; the label itself just carries
     *  its HTML text.  refreshDesignSummaryLabel computes the
     *  HTML body width from the wrapper's actual width and a
     *  ComponentListener re-renders on resize so the text always
     *  wraps to the current container width. */
    private val designSummaryLabel: JLabel = JLabel(" ").apply {
        foreground = Color(0x44, 0x44, 0x44)
    }
    private val designSummaryRow: JPanel = JPanel(BorderLayout()).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xE6, 0xE6, 0xE6)),
            BorderFactory.createEmptyBorder(3, 12, 3, 12)
        )
        add(designSummaryLabel, BorderLayout.CENTER)
        isVisible = false
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                // Resize → recompute HTML body width and re-render
                // so the label wraps to the new container width.
                refreshDesignSummaryLabel()
            }
        })
    }

    // runProgressLabel removed in E7.9 — per-design-point status now
    // lives on the Simulate tab (SimulateTabPanel.statusLabel) where
    // it's always reachable from the same tab that hosts the
    // Simulate / Cancel buttons.

    companion object {
        private const val SAVE_BASE_TEXT: String = "Save Experiment"
        private const val SAVE_DIRTY_TEXT: String = "Save Experiment *"
        private const val CONFIG_TOOLTIP: String =
            "Save / open the experiment document (model reference, factors, design, " +
                "replications, stream policy, output options, execution mode) as a TOML " +
                "file under <workspace>/configs/."
    }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(960, 680)

        jMenuBar = buildMenuBar()
        contentPane.layout = BorderLayout()

        // Authoring tabs (Model / Factors / Design) and execution tab
        // (Simulate) take the document from "what to run" to "running
        // it".  The toolbar Simulate / Cancel / Mode / Enable-DB
        // widgets moved into the Simulate tab as part of the E7.9
        // restructure; the toolbar now only carries analysisName +
        // databasePolicy (output-config concerns).
        val modelTab = ModelTabPanel(controller, notifications)
        val factorsTab = FactorsTabPanel(controller, notifications)
        val designTab = DesignTabPanel(controller, notifications)
        val simulateTab = SimulateTabPanel(
            controller,
            notifier = notifications,
            onSimulateRequested = { handleSimulate() }
        )
        val regressionTab = RegressionTabPanel(
            controller,
            notifier = notifications
        )
        this.regressionTabPanel = regressionTab

        // Analysis tabs that already exist as generic Common panels.
        val reportsTab = BatchReportsTabPanel(
            lastResultFlow = controller.lastResult,
            runningFlow = controller.runningFlow,
            edtScope = controller.edtScope,
            workspaceProvider = { controller.appWorkspace },
            analysisNameProvider = { controller.outputConfig.value.analysisName },
            // Domain-natural item-type labels — design points, not scenarios.
            itemTypeName = "design point",
            itemTypeNamePlural = "design points",
            itemFileStemPrefix = "design-point-summary",
            batchFileStem = "experiment-summary",
            notifier = notifications
        )
        val comparisonAnalyzerTab = ComparisonAnalyzerTabPanel(
            defaultOutputDirProvider = {
                ksl.app.session.AppWorkspacePaths.reportsDir(
                    controller.appWorkspace,
                    controller.outputConfig.value.analysisName
                )
            },
            defaultFormatsProvider = { controller.outputConfig.value.reports },
            notifier = notifications
        )

        val tabs = JTabbedPane().apply {
            addTab("Model", modelTab)
            addTab("Factors", factorsTab)
            addTab("Design", designTab)
            addTab("Simulate", simulateTab)
            addTab("Regression", regressionTab)
            addTab("Comparison Analyzer", comparisonAnalyzerTab)
            addTab("Reports", reportsTab)
        }
        // Refresh the Reports / Comparison Analyzer tabs on activation
        // — same pattern Scenario uses (external file deletion catch
        // for Reports; no-op for Comparison Analyzer's hook).
        tabs.addChangeListener {
            when (tabs.selectedComponent) {
                reportsTab -> reportsTab.refreshFromDisk()
                comparisonAnalyzerTab -> comparisonAnalyzerTab.refreshFromDisk()
            }
        }

        // Feed the Comparison Analyzer fresh data on each new batch.
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                val batch = result as? RunResult.BatchCompleted
                val sources = if (batch != null && batch.replicationsByItem.isNotEmpty()) {
                    listOf(BatchCompletedComparisonSource(batch))
                } else {
                    emptyList()
                }
                comparisonAnalyzerTab.setSources(sources)
            }
        }
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                if (running) {
                    comparisonAnalyzerTab.setEmptyStateText(
                        "<html><div style='text-align:center;'>" +
                            "Simulation in progress.<br>" +
                            "Reports will appear when the experiment completes." +
                            "</div></html>"
                    )
                } else {
                    comparisonAnalyzerTab.setEmptyStateText(
                        "<html><div style='text-align:center;'>" +
                            "No completed experiment with per-replication data yet.<br>" +
                            "Run the experiment to populate this tab." +
                            "</div></html>"
                    )
                }
            }
        }

        // E7.14 — GridBagLayout instead of BoxLayout Y_AXIS so each
        // row spans the full container width with NORTHWEST anchor.
        // BoxLayout Y_AXIS aligns siblings on a single vertical line
        // at max(alignmentX) × containerWidth; mixing default-CENTER
        // children (toolbar, banner) with a LEFT-aligned child
        // (designSummaryRow) pushed the LEFT child's left edge TO
        // the alignment line at containerWidth/2 — that's why the
        // summary appeared indented roughly half the frame width
        // under the toolbar.  GridBagLayout with fill=HORIZONTAL +
        // weightx=1.0 + anchor=NORTHWEST has no such interaction:
        // each row sits at x=0 spanning the full width.
        val topStack = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
            }
            gbc.gridy = 0; add(buildRunToolbar(), gbc)
            gbc.gridy = 1; add(staleResultsBanner, gbc)
            gbc.gridy = 2; add(designSummaryRow, gbc)
        }
        contentPane.add(topStack, BorderLayout.NORTH)
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(buildBottomStack(), BorderLayout.SOUTH)

        wireWindowTitle()
        wireDirtyIndicators()
        wireBundleStatus()
        wireRunIndicators()
        wireStaleResultsBanner()
        wireOutputConfigCollector()
        wireEventNotifications()
        wireDesignSummaryLabel()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                controller.close()
            }
        })
    }

    private fun placeholderPanel(text: String): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel(text, SwingConstants.CENTER).apply {
            foreground = Color(0x88, 0x88, 0x88)
        }, BorderLayout.CENTER)
    }

    // ── Menu bar ───────────────────────────────────────────────────────────

    private fun buildMenuBar(): JMenuBar {
        val setWdAction = SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
        val recentMenu = RecentWorkingDirectoriesMenu(controller.settingsStore, controller.edtScope)
        val menuShortcutKey = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        val newItem = JMenuItem(object : AbstractAction("New Experiment") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleNew() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutKey)
            toolTipText = "Start a new, empty experiment.  Discards the current model " +
                "reference, factors, design, and file association.  Prompts to save " +
                "unsaved changes first."
        }
        val openItem = JMenuItem(object : AbstractAction("Open Experiment…") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleOpen() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, menuShortcutKey)
            toolTipText = CONFIG_TOOLTIP
        }
        saveItem = JMenuItem(object : AbstractAction(SAVE_BASE_TEXT) {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleSave() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, menuShortcutKey)
            toolTipText = CONFIG_TOOLTIP
        }
        val saveAsItem = JMenuItem(object : AbstractAction("Save Experiment As…") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleSaveAs() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(
                KeyEvent.VK_S, menuShortcutKey or KeyEvent.SHIFT_DOWN_MASK
            )
            toolTipText = CONFIG_TOOLTIP
        }
        val loadBundleItem = JMenuItem(object : AbstractAction("Load Bundle JAR…") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleLoadBundleJar() }
        }).apply { toolTipText = "Load a JAR that ships one or more KSLModelBundle service registrations." }
        return JMenuBar().apply {
            add(JMenu("File").apply {
                add(newItem)
                add(openItem)
                addSeparator()
                add(saveItem)
                add(saveAsItem)
                addSeparator()
                add(JMenuItem(setWdAction))
                add(recentMenu)
                addSeparator()
                add(JMenuItem("Exit").apply { addActionListener { dispose() } })
            })
            add(JMenu("Bundles").apply {
                add(loadBundleItem)
            })
            add(JMenu("View").apply {
                add(ksl.app.swing.common.appearance.ThemeMenu.build(controller.edtScope))
            })
        }
    }

    // ── Layout helpers ─────────────────────────────────────────────────────

    private fun buildStatusBar(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(2, 0, 2, 0)
        )
        add(bundleStatusLabel, BorderLayout.WEST)
    }

    private fun buildBottomStack(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(consoleDrawer)
        add(buildStatusBar())
    }

    /**
     *  Toolbar holds only output-config concerns now (analysisName +
     *  database policy combo).  Simulate / Cancel / Mode (execution)
     *  / Enable-database moved into the Simulate tab as part of the
     *  E7.9 restructure (configuration vs. execution separation).
     */
    private fun buildRunToolbar(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        analysisNameField.addActionListener {
            controller.setAnalysisName(analysisNameField.text)
        }
        analysisNameField.addFocusListener(object : java.awt.event.FocusListener {
            override fun focusGained(e: java.awt.event.FocusEvent) { /* no-op */ }
            override fun focusLost(e: java.awt.event.FocusEvent) {
                controller.setAnalysisName(analysisNameField.text)
            }
        })
        databasePolicyCombo.addActionListener {
            val selected = databasePolicyCombo.selectedItem as? ksl.app.config.DatabasePolicy
                ?: return@addActionListener
            controller.setDatabasePolicy(selected)
        }
        add(JLabel("Analysis:"))
        add(Box.createHorizontalStrut(4))
        add(analysisNameField)
        add(Box.createHorizontalStrut(16))
        add(JLabel("DB policy:"))
        add(Box.createHorizontalStrut(4))
        add(databasePolicyCombo)
        add(Box.createHorizontalGlue())
    }

    // ── Submit + Cancel ────────────────────────────────────────────────────

    private fun handleSimulate() {
        if (controller.runningFlow.value) return
        if (controller.modelReference.value == null) {
            notifications.warn(
                "No model selected.  Pick a model on the Model tab before simulating."
            )
            return
        }
        if (controller.factors.value.isEmpty()) {
            notifications.warn(
                "No factors defined.  Add at least one factor on the Factors tab."
            )
            return
        }
        if (controller.isDirty.value) {
            if (!handleDirtyOnRun()) return
        }
        // Unsaved regression fits: about to be wiped by the R1
        // clearRunState() at submit-time.  Give the user a chance to
        // materialise them to disk before they're gone — mirrors the
        // "Unsaved Changes" dialog shape so it feels familiar.
        val unsavedFitCount = controller.recentRegressionFits.value.count { it.savedPaths.isEmpty() }
        if (unsavedFitCount > 0) {
            val choice = JOptionPane.showOptionDialog(
                this,
                "You have $unsavedFitCount unsaved regression fit(s).\n" +
                    "Running a new simulation will replace the experiment results " +
                    "and clear all in-memory fits.\n\n" +
                    "Save them to the reports directory first?",
                "Unsaved Regression Fits",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                arrayOf<Any>("Save all and simulate", "Simulate (discard fits)", "Cancel"),
                "Save all and simulate"
            )
            when (choice) {
                0 -> regressionTabPanel.saveAllUnsavedRegressionFits()
                1 -> { /* fall through */ }
                else -> return
            }
        }
        // SEQUENTIAL + CRN: substrate silently degrades.  Surface a
        // one-shot warning before the user commits to the run.
        if (controller.sequentialIgnoresStreamPolicy()) {
            notifications.warn(
                "Common Random Numbers is silently ignored under SEQUENTIAL execution mode.  " +
                    "Switch to CONCURRENT to apply the stream policy."
            )
        }
        consolePanel.clear()
        if (!controller.submit()) {
            notifications.error(
                "Could not start the run.  Check the model reference resolves against " +
                    "the loaded bundles."
            )
        }
    }

    /**
     *  Toolbar enablement tracking the run state.  The SimulateTab
     *  panel handles its own Simulate / Cancel / Mode / Enable-DB
     *  enablement; the frame only tracks the few widgets that
     *  remain (analysisName, databasePolicy).
     */
    private fun wireRunIndicators() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                analysisNameField.isEnabled = !running
                databasePolicyCombo.isEnabled = !running
            }
        }
    }

    private fun wireOutputConfigCollector() {
        controller.edtScope.launch {
            controller.outputConfig.collect { cfg ->
                if (analysisNameField.text != cfg.analysisName && !analysisNameField.hasFocus()) {
                    analysisNameField.text = cfg.analysisName
                }
                if (databasePolicyCombo.selectedItem != cfg.databasePolicy) {
                    databasePolicyCombo.selectedItem = cfg.databasePolicy
                }
            }
        }
    }

    /**
     *  Live one-line configuration summary subscriber.  Watches every
     *  relevant state flow and re-renders the label.  Hidden when
     *  no model is loaded.
     */
    private fun wireDesignSummaryLabel() {
        val refresh: () -> Unit = { refreshDesignSummaryLabel() }
        controller.edtScope.launch { controller.modelReference.collect { refresh() } }
        controller.edtScope.launch { controller.currentModelDescriptor.collect { refresh() } }
        controller.edtScope.launch { controller.factors.collect { refresh() } }
        controller.edtScope.launch { controller.designSpec.collect { refresh() } }
        controller.edtScope.launch { controller.replications.collect { refresh() } }
        controller.edtScope.launch { controller.streamPolicy.collect { refresh() } }
        controller.edtScope.launch { controller.runParameterOverrides.collect { refresh() } }
        refresh()
    }

    private fun refreshDesignSummaryLabel() {
        val modelRef = controller.modelReference.value
        if (modelRef == null) {
            designSummaryLabel.text = " "
            designSummaryRow.isVisible = false
            return
        }
        val modelName = controller.currentModelDescriptor.value?.modelName
            ?: when (modelRef) {
                is ksl.app.config.ModelReference.Embedded -> modelRef.modelName
                is ksl.app.config.ModelReference.ByBundleAndModelId -> modelRef.modelId
                is ksl.app.config.ModelReference.ByJar -> modelRef.builderClassName ?: "(unknown)"
                is ksl.app.config.ModelReference.ByProviderId -> modelRef.providerId
            }
        val factors = controller.factors.value
        val factorCount = factors.size
        val familyAndPoints = describeDesignFamilyAndPoints()
        val reps = describeReplications()
        val streams = describeStreamPolicy()
        val totalRuns = describeTotalRuns()
        val plain = buildString {
            append("Model: ").append(modelName)
            append(" · ").append(factorCount).append(" factor")
                .append(if (factorCount == 1) "" else "s")
            if (familyAndPoints.isNotEmpty()) append(" · Design: ").append(familyAndPoints)
            if (reps.isNotEmpty()) append(" · Reps: ").append(reps)
            if (streams.isNotEmpty()) append(" · Streams: ").append(streams)
            if (totalRuns != null) append(" · Total runs: ").append(totalRuns)
        }
        // E7.13 (#1A revised): wrap at the wrapper row's CURRENT
        // width (less padding) rather than a hard-coded 900px.
        // The wrapper's ComponentListener re-fires this method on
        // resize so the label re-renders for the new container
        // width.  Clamp to 300px so very-narrow windows still
        // produce something usable (HTML wraps aggressively
        // below that).
        val rowWidth = (designSummaryRow.width - 24).coerceAtLeast(300)
        designSummaryLabel.text = "<html><body width='$rowWidth'>$plain</body></html>"
        designSummaryRow.isVisible = true
    }

    private fun describeDesignFamilyAndPoints(): String {
        val spec = controller.designSpec.value
        val factors = controller.factors.value
        val k = factors.size
        return when (spec) {
            is ksl.app.config.experiment.DesignSpec.FullFactorial -> {
                if (k == 0) return "Full factorial"
                val product = factors.fold(1L) { acc, f -> acc * f.levels.size.toLong() }
                "Full factorial, $product point${if (product == 1L) "" else "s"}"
            }
            is ksl.app.config.experiment.DesignSpec.TwoLevelFactorial -> {
                if (k == 0) return "Two-level factorial"
                val n: Long = when (val f = spec.fraction) {
                    ksl.app.config.experiment.Fraction.Full -> 1L shl k
                    is ksl.app.config.experiment.Fraction.HalfFraction -> (1L shl k) / 2
                    is ksl.app.config.experiment.Fraction.Custom ->
                        1L shl (k - f.words.size).coerceAtLeast(0)
                }
                val tag = when (val f = spec.fraction) {
                    ksl.app.config.experiment.Fraction.Full -> "full 2^$k"
                    is ksl.app.config.experiment.Fraction.HalfFraction -> "half-fraction"
                    is ksl.app.config.experiment.Fraction.Custom -> "2^($k-${f.words.size}) fraction"
                }
                "Two-level factorial ($tag), $n point${if (n == 1L) "" else "s"}"
            }
            is ksl.app.config.experiment.DesignSpec.CentralComposite -> {
                if (k == 0) return "Central composite"
                val factorial = 1L shl k
                val axials = 2L * k
                val total = factorial + axials + 1
                "Central composite, $total points " +
                    "($factorial factorial + $axials axial + 1 centre)"
            }
            is ksl.app.config.experiment.DesignSpec.Manual ->
                "Custom (${spec.points.size} point${if (spec.points.size == 1) "" else "s"})"
        }
    }

    private fun describeReplications(): String =
        when (val rep = controller.replications.value) {
            is ksl.app.config.experiment.ReplicationSpec.Uniform ->
                "Uniform (${rep.replications} each)"
            is ksl.app.config.experiment.ReplicationSpec.PerPoint ->
                "Per-point (default ${rep.default}, ${rep.overrides.size} override" +
                    "${if (rep.overrides.size == 1) "" else "s"})"
        }

    private fun describeStreamPolicy(): String =
        when (controller.streamPolicy.value) {
            is ksl.app.config.experiment.StreamPolicy.Independent -> "Independent"
            is ksl.app.config.experiment.StreamPolicy.CommonRandomNumbers -> "Common Random Numbers"
        }

    /**
     *  Best-effort total-runs estimate.  CCD's three-way rep split
     *  is computed exactly; FullFactorial / TwoLevelFactorial /
     *  Manual multiply the document-level Uniform reps by the point
     *  count (PerPoint policy falls back to the default since the
     *  status bar can't fan out every override).
     */
    private fun describeTotalRuns(): String? {
        val factors = controller.factors.value
        if (factors.isEmpty()) return null
        val k = factors.size
        val rep = controller.replications.value
        val baseReps = when (rep) {
            is ksl.app.config.experiment.ReplicationSpec.Uniform -> rep.replications
            is ksl.app.config.experiment.ReplicationSpec.PerPoint -> rep.default
        }
        return when (val spec = controller.designSpec.value) {
            is ksl.app.config.experiment.DesignSpec.FullFactorial -> {
                val product = factors.fold(1L) { acc, f -> acc * f.levels.size.toLong() }
                (product * baseReps).toString()
            }
            is ksl.app.config.experiment.DesignSpec.TwoLevelFactorial -> {
                val n: Long = when (val f = spec.fraction) {
                    ksl.app.config.experiment.Fraction.Full -> 1L shl k
                    is ksl.app.config.experiment.Fraction.HalfFraction -> (1L shl k) / 2
                    is ksl.app.config.experiment.Fraction.Custom ->
                        1L shl (k - f.words.size).coerceAtLeast(0)
                }
                (n * baseReps).toString()
            }
            is ksl.app.config.experiment.DesignSpec.CentralComposite -> {
                val factorial = 1L shl k
                val axials = 2L * k
                val total = factorial * spec.numFactorialReps +
                    axials * spec.numAxialReps +
                    spec.numCenterReps
                total.toString()
            }
            is ksl.app.config.experiment.DesignSpec.Manual ->
                (spec.points.size.toLong() * baseReps).toString()
        }
    }

    /**
     *  Frame-level event subscriber.  Per-design-point progress
     *  tracking moved to the Simulate tab in E7.9; this subscriber
     *  now only handles the frame-level notifications (run failure
     *  popup, per-point failure popup).
     */
    private fun wireEventNotifications() {
        controller.edtScope.launch {
            controller.eventFlow.collect { ev ->
                when (ev) {
                    is RunEvent.DesignPointCompleted -> {
                        // Surface per-point failures as toast notifications.
                        // Cancelled points stay quiet (the user requested it).
                        if (ev.snapshot == null && !ev.wasCancelled) {
                            notifications.warn(
                                "Design point ${ev.pointId} failed."
                            )
                        }
                    }
                    is RunEvent.RunFailed -> {
                        notifications.error(
                            "Run failed: ${describeError(ev.error)}"
                        )
                    }
                    else -> { /* console drawer + Simulate tab handle the rest */ }
                }
            }
        }
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                if (result is RunResult.BatchCompleted) {
                    notifications.info(
                        "Run completed: ${result.summary.completedItems} of " +
                            "${result.summary.totalItems} design points."
                    )
                }
            }
        }
    }

    private fun describeError(error: ksl.app.session.KSLRuntimeError): String = when (error) {
        is ksl.app.session.KSLRuntimeError.ModelBuildError -> error.message
        is ksl.app.session.KSLRuntimeError.JarLoadError -> error.message
        is ksl.app.session.KSLRuntimeError.ExecutiveError ->
            error.cause.message ?: error.cause::class.simpleName.orEmpty()
        is ksl.app.session.KSLRuntimeError.ConfigurationError -> error.message
    }

    // ── Bundle loading ─────────────────────────────────────────────────────

    private fun handleLoadBundleJar() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Load Bundle JAR"
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter("Bundle JAR (*.jar)", "jar")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path: Path = chooser.selectedFile?.toPath() ?: return
        when (val outcome = controller.loadBundleJar(path)) {
            is BundleLibraryController.LoadBundleResult.Loaded -> {
                val ids = outcome.newBundleIds.joinToString(", ")
                notifications.info(
                    "Loaded ${outcome.newBundleIds.size} bundle(s): $ids"
                )
            }
            is BundleLibraryController.LoadBundleResult.Reloaded ->
                notifications.info(
                    "Reloaded from disk: " + outcome.bundleIds.joinToString(", ")
                )
            is BundleLibraryController.LoadBundleResult.AlreadyLoaded ->
                notifications.info(
                    "Already loaded (no change): " + outcome.bundleIds.joinToString(", ")
                )
            BundleLibraryController.LoadBundleResult.NoBundles ->
                notifications.warn("$path declares no KSLModelBundle service.")
            is BundleLibraryController.LoadBundleResult.Failed ->
                notifications.error("Could not load $path: ${outcome.reason}")
        }
    }

    // ── File menu handlers ─────────────────────────────────────────────────

    private fun handleNew() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and start a new experiment?")) return
        controller.resetConfiguration()
    }

    private fun handleOpen() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and open another experiment?")) return
        val startDir = WorkspaceLayout.configsDir(controller.appWorkspace, createIfMissing = true)
        val chooser = JFileChooser(startDir.toFile()).apply {
            dialogTitle = "Open Experiment"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Experiment TOML (*.toml)", "toml")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path: Path = chooser.selectedFile?.toPath() ?: return
        val text = try {
            Files.readString(path)
        } catch (t: Throwable) {
            notifications.error(
                "Could not read $path: ${t.message ?: t::class.simpleName}"
            )
            return
        }
        val config = try {
            ExperimentConfigurationToml.decode(text)
        } catch (t: Throwable) {
            notifications.error(
                "Failed to parse experiment: ${t.message ?: t::class.simpleName}"
            )
            return
        }
        when (val outcome = controller.loadConfiguration(config)) {
            is ExperimentAppController.LoadResult.Loaded -> {
                controller.markSaved(path)
                notifications.dismissAll()
                outcome.warnings.forEach {
                    notifications.warn(it)
                }
                notifications.info("Opened ${path.fileName}")
            }
            is ExperimentAppController.LoadResult.Failed -> {
                notifications.error(outcome.reason)
            }
        }
    }

    /**
     *  Push the analysis-name field's current text to the controller
     *  before any save / preview action.  Without this, a user who
     *  types into the field and immediately clicks File → Save (or
     *  the Materialize button) loses the typed value because the
     *  field commits only on Enter or focus-lost, and menu/button
     *  activation order doesn't reliably interleave focus events
     *  before the action runs.  Pinned reactively in:
     *  - [handleSave] / [handleSaveAs]   (filename derivation)
     *  - [defaultSaveAsName] reads the freshly committed value
     */
    private fun flushPendingAnalysisName() {
        val typed = analysisNameField.text
        if (typed != controller.outputConfig.value.analysisName) {
            controller.setAnalysisName(typed)
        }
    }

    /**
     *  Dirty-on-run gate fired by Simulate when the configuration has
     *  unsaved changes.  Distinguishes between two save destinations:
     *
     *  - **Save to original file** — overwrite the file the analyst
     *    opened (or last Saved-As to).  In-place update; matches the
     *    "edit and re-run" workflow.  Hidden when no original file is
     *    associated.
     *  - **Save As new file…** — open the Save As chooser so the
     *    modified configuration lands in a new file, leaving the
     *    original untouched.  Matches the "fork to a variant" workflow
     *    that's the most common reason for changing the Output Name.
     *
     *  When no `currentFile` is associated, the original-file button
     *  disappears (only Save As makes sense).  The dialog shows the
     *  destination file inline so the analyst has informed consent
     *  about what each Save button writes over.
     *
     *  Returns `true` when the caller may proceed with Simulate, `false`
     *  when the analyst cancelled.  *Simulate without saving* and a
     *  successful save both return `true`; cancelling the Save As
     *  chooser after picking "Save As new file…" also returns `true`
     *  (declining the chooser after asking for it is an explicit
     *  "run anyway" choice — mirrors the Scenario and Single apps).
     */
    private fun handleDirtyOnRun(): Boolean {
        val existing = controller.currentFile.value
        val pathLine = if (existing != null) {
            "\n\nCurrent file:  $existing"
        } else {
            ""
        }
        val options = if (existing != null) {
            arrayOf<Any>(
                "Save to original file",
                "Save As new file…",
                "Simulate without saving",
                "Cancel"
            )
        } else {
            arrayOf<Any>(
                "Save As new file…",
                "Simulate without saving",
                "Cancel"
            )
        }
        val defaultOption = options[0]
        val choice = JOptionPane.showOptionDialog(
            this,
            "You have unsaved experiment changes.$pathLine",
            "Unsaved Changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            defaultOption
        )
        if (choice < 0 || choice >= options.size) return false  // window-close / Esc
        return when (options[choice]) {
            "Save to original file" -> {
                handleSave()
                true
            }
            "Save As new file…" -> {
                handleSaveAs()
                true
            }
            "Simulate without saving" -> true
            else -> false   // Cancel
        }
    }

    private fun handleSave() {
        flushPendingAnalysisName()
        val existing = controller.currentFile.value
        if (existing == null) {
            handleSaveAs()
            return
        }
        if (!Files.exists(existing)) {
            when (promptForMissingFile(existing)) {
                MissingFileChoice.RECREATE_HERE -> writeConfigurationTo(existing)
                MissingFileChoice.SAVE_AS -> handleSaveAs()
                MissingFileChoice.CANCEL -> { /* isDirty stays true; user can retry */ }
            }
            return
        }
        writeConfigurationTo(existing)
    }

    private enum class MissingFileChoice { RECREATE_HERE, SAVE_AS, CANCEL }

    private fun promptForMissingFile(missing: Path): MissingFileChoice {
        val options = arrayOf("Recreate Here", "Save As…", "Cancel")
        val choice = JOptionPane.showOptionDialog(
            this,
            "The experiment file is no longer at:\n  $missing\n\n" +
                "It may have been moved or deleted outside the app.\n" +
                "Recreate it at this path, or save to a new location?",
            "File No Longer Exists",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        )
        return when (choice) {
            0 -> MissingFileChoice.RECREATE_HERE
            1 -> MissingFileChoice.SAVE_AS
            else -> MissingFileChoice.CANCEL
        }
    }

    /**
     *  Default filename suggested in the Save Experiment As… dialog.
     *  Preference order: user-set analysisName (sanitised) → currently
     *  loaded file's name → application name.  Same shape as Scenario
     *  + Single apps.
     */
    private fun defaultSaveAsName(): String {
        val analysis = controller.outputConfig.value.analysisName
        if (analysis != "Untitled") {
            return "${ksl.app.config.sanitizeAnalysisName(analysis)}.toml"
        }
        controller.currentFile.value?.fileName?.toString()?.let { return it }
        return "${sanitizeFileName(controller.appName)}.toml"
    }

    private fun handleSaveAs() {
        flushPendingAnalysisName()
        val startDir = WorkspaceLayout.configsDir(controller.appWorkspace, createIfMissing = true)
        val defaultName = defaultSaveAsName()
        val chooser = JFileChooser(startDir.toFile()).apply {
            dialogTitle = "Save Experiment"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Experiment TOML (*.toml)", "toml")
            selectedFile = startDir.resolve(defaultName).toFile()
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var path: Path = chooser.selectedFile?.toPath() ?: return
        if (path.fileName.toString().substringAfterLast('.', "") != "toml") {
            path = path.resolveSibling("${path.fileName}.toml")
        }
        if (Files.exists(path)) {
            val overwrite = JOptionPane.showConfirmDialog(
                this,
                "${path.fileName} already exists.\nReplace it?",
                "Replace Experiment",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (overwrite != JOptionPane.YES_OPTION) return
        }
        writeConfigurationTo(path)
    }

    private fun writeConfigurationTo(path: Path) {
        val config = try {
            controller.currentConfiguration()
        } catch (t: IllegalStateException) {
            notifications.warn(
                "Cannot save: ${t.message ?: "model reference is required"}"
            )
            return
        }
        val text = try {
            ExperimentConfigurationToml.encode(config)
        } catch (t: Throwable) {
            notifications.error(
                "Failed to encode experiment: ${t.message ?: t::class.simpleName}"
            )
            return
        }
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        } catch (t: Throwable) {
            notifications.error(
                "Could not write $path: ${t.message ?: t::class.simpleName}"
            )
            return
        }
        controller.markSaved(path)
        notifications.info("Saved ${path.fileName}")
    }

    private fun confirmDiscardIfDirty(question: String): Boolean {
        if (!controller.isDirty.value) return true
        val choice = JOptionPane.showConfirmDialog(
            this, question, "Unsaved Changes",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return choice == JOptionPane.YES_OPTION
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "experiment" }

    // ── Reactive plumbing ──────────────────────────────────────────────────

    private fun wireWindowTitle() {
        controller.edtScope.launch {
            combine(controller.currentFile, controller.isDirty) { file, dirty -> file to dirty }
                .collect { (file, dirty) ->
                    val fileSegment = file?.fileName?.toString()?.let { " — $it" }.orEmpty()
                    val dirtyMark = if (dirty) " *" else ""
                    title = "${controller.appName}$fileSegment$dirtyMark"
                }
        }
    }

    private fun wireDirtyIndicators() {
        controller.edtScope.launch {
            controller.isDirty.collect { dirty ->
                saveItem.text = if (dirty) SAVE_DIRTY_TEXT else SAVE_BASE_TEXT
            }
        }
    }

    private fun wireStaleResultsBanner() {
        controller.edtScope.launch {
            combine(
                controller.editedSinceLastSim,
                controller.lastResult
            ) { edited, result -> edited && result != null }
                .collect { staleResultsBanner.isVisible = it }
        }
    }

    private fun wireBundleStatus() {
        controller.edtScope.launch {
            controller.loadedBundles.collect { bundles ->
                val modelCount = bundles.sumOf { it.bundle.models.size }
                bundleStatusLabel.text =
                    "${bundles.size} bundle${if (bundles.size == 1) "" else "s"} · " +
                        "$modelCount model${if (modelCount == 1) "" else "s"}"
            }
        }
    }
}
