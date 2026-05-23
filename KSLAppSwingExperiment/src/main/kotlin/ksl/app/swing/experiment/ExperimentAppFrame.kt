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
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.batchreports.BatchReportsTabPanel
import ksl.app.swing.common.comparison.BatchCompletedComparisonSource
import ksl.app.swing.common.comparison.ComparisonAnalyzerFrame
import ksl.app.swing.common.comparison.ComparisonAnalyzerTabPanel
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.common.notification.Notifications
import ksl.app.swing.common.runcontrol.ConsoleCategory
import ksl.app.swing.common.runcontrol.ConsoleDrawer
import ksl.app.swing.common.runcontrol.ConsoleLogPanel
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
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
    private val bundleStatusLabel: JLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    // ── Run-toolbar widgets ────────────────────────────────────────────────

    private val simulateButton = JButton("Simulate").apply {
        // Disabled at construction; the wireRunIndicators collectors
        // re-evaluate the combined (not-running AND has-model AND
        // has-factor) gate as soon as they subscribe.
        isEnabled = false
    }
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val sequentialRadio = javax.swing.JRadioButton(
        "Sequential",
        controller.executionMode.value == ksl.app.config.ExecutionMode.SEQUENTIAL
    )
    private val concurrentRadio = javax.swing.JRadioButton(
        "Concurrent",
        controller.executionMode.value == ksl.app.config.ExecutionMode.CONCURRENT
    )
    private val enableDbCheckbox = javax.swing.JCheckBox(
        "Enable database",
        controller.outputConfig.value.enableKSLDatabase
    ).apply {
        toolTipText = "Capture each design point's results in the shared KSL SQLite database " +
            "(<workspace>/output/<analysisName>/).  Required for downstream Regression + " +
            "Comparison-Analyzer surfaces."
    }
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

    /** Status line under the run toolbar, ambient design summary.
     *  Real content arrives in Phase E8/E11 once the design preview
     *  is enumerable; for now it shows a placeholder so the layout
     *  region is reserved. */
    private val designSummaryLabel: JLabel = JLabel(
        "Design summary — populated in Phase E8."
    ).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xE6, 0xE6, 0xE6)),
            BorderFactory.createEmptyBorder(3, 12, 3, 12)
        )
        foreground = Color(0x66, 0x66, 0x66)
    }

    companion object {
        private const val SAVE_BASE_TEXT: String = "Save Configuration"
        private const val SAVE_DIRTY_TEXT: String = "Save Configuration *"
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

        // Authoring tabs (E5–E8) — stub panels in this phase.
        val modelTab = placeholderPanel("Model picker — Phase E5.")
        val factorsTab = placeholderPanel("Factor editor — Phase E6.")
        val designTab = placeholderPanel("Design type + stream policy — Phase E7.")
        val designPointsTab = placeholderPanel("Design-point preview — Phase E8.")
        val regressionTab = placeholderPanel("Regression configuration + HTML-report materialisation — Phase E9.")

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
            onMessage = { msg, sev -> notifications.show(msg, sev) }
        )
        val comparisonAnalyzerTab = ComparisonAnalyzerTabPanel(
            defaultOutputDirProvider = {
                controller.appWorkspace
                    .resolve("output")
                    .resolve(ksl.app.config.sanitizeAnalysisName(controller.outputConfig.value.analysisName))
                    .resolve("reports")
            },
            defaultFormatsProvider = { controller.outputConfig.value.reports },
            onMessage = { msg, sev ->
                notifications.show(
                    msg,
                    when (sev) {
                        ComparisonAnalyzerFrame.Severity.INFO -> NotificationSeverity.INFO
                        ComparisonAnalyzerFrame.Severity.WARNING -> NotificationSeverity.WARNING
                        ComparisonAnalyzerFrame.Severity.ERROR -> NotificationSeverity.ERROR
                    }
                )
            }
        )

        val tabs = JTabbedPane().apply {
            addTab("Model", modelTab)
            addTab("Factors", factorsTab)
            addTab("Design", designTab)
            addTab("Design Points", designPointsTab)
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

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buildRunToolbar())
            add(staleResultsBanner)
            add(designSummaryLabel)
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
        val openItem = JMenuItem(object : AbstractAction("Open Configuration…") {
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
        val saveAsItem = JMenuItem(object : AbstractAction("Save Configuration As…") {
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

    private fun buildRunToolbar(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        simulateButton.addActionListener { handleSimulate() }
        cancelButton.addActionListener { controller.cancel() }
        val group = javax.swing.ButtonGroup().apply {
            add(sequentialRadio); add(concurrentRadio)
        }
        @Suppress("UNUSED_EXPRESSION") group
        sequentialRadio.addActionListener {
            if (sequentialRadio.isSelected) controller.setExecutionMode(ksl.app.config.ExecutionMode.SEQUENTIAL)
        }
        concurrentRadio.addActionListener {
            if (concurrentRadio.isSelected) controller.setExecutionMode(ksl.app.config.ExecutionMode.CONCURRENT)
        }
        enableDbCheckbox.addActionListener {
            controller.setEnableKSLDatabase(enableDbCheckbox.isSelected)
        }
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
        add(simulateButton)
        add(Box.createHorizontalStrut(8))
        add(cancelButton)
        add(Box.createHorizontalStrut(16))
        add(JLabel("Mode:"))
        add(Box.createHorizontalStrut(4))
        add(sequentialRadio)
        add(concurrentRadio)
        add(Box.createHorizontalStrut(16))
        add(JLabel("Analysis:"))
        add(Box.createHorizontalStrut(4))
        add(analysisNameField)
        add(Box.createHorizontalStrut(16))
        add(enableDbCheckbox)
        add(Box.createHorizontalStrut(4))
        add(JLabel("DB:"))
        add(Box.createHorizontalStrut(4))
        add(databasePolicyCombo)
        add(Box.createHorizontalGlue())
    }

    // ── Submit + Cancel ────────────────────────────────────────────────────

    private fun handleSimulate() {
        if (controller.runningFlow.value) return
        if (controller.modelReference.value == null) {
            notifications.show(
                "No model selected.  Pick a model on the Model tab before simulating.",
                NotificationSeverity.WARNING
            )
            return
        }
        if (controller.factors.value.isEmpty()) {
            notifications.show(
                "No factors defined.  Add at least one factor on the Factors tab.",
                NotificationSeverity.WARNING
            )
            return
        }
        if (controller.isDirty.value) {
            val choice = JOptionPane.showOptionDialog(
                this,
                "You have unsaved configuration changes.\n" +
                    "Save them before simulating?",
                "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                arrayOf<Any>("Save…", "Simulate without saving", "Cancel"),
                "Save…"
            )
            when (choice) {
                0 -> handleSave()
                1 -> { /* fall through to submit */ }
                else -> return
            }
        }
        // SEQUENTIAL + CRN: substrate silently degrades.  Surface a
        // one-shot warning before the user commits to the run.
        if (controller.sequentialIgnoresStreamPolicy()) {
            notifications.show(
                "Common Random Numbers is silently ignored under SEQUENTIAL execution mode.  " +
                    "Switch to CONCURRENT to apply the stream policy.",
                NotificationSeverity.WARNING
            )
        }
        consolePanel.clear()
        if (!controller.submit()) {
            notifications.show(
                "Could not start the run.  Check the model reference resolves against " +
                    "the loaded bundles.",
                NotificationSeverity.ERROR
            )
        }
    }

    /** Combined Simulate-button gate: not running AND model selected
     *  AND at least one factor. */
    private fun refreshSimulateEnablement() {
        val running = controller.runningFlow.value
        val hasModel = controller.modelReference.value != null
        val hasFactors = controller.factors.value.isNotEmpty()
        simulateButton.isEnabled = !running && hasModel && hasFactors
    }

    private fun wireRunIndicators() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                cancelButton.isEnabled = running
                sequentialRadio.isEnabled = !running
                concurrentRadio.isEnabled = !running
                enableDbCheckbox.isEnabled = !running
                analysisNameField.isEnabled = !running
                databasePolicyCombo.isEnabled = !running
                refreshSimulateEnablement()
            }
        }
        controller.edtScope.launch {
            controller.modelReference.collect { _ -> refreshSimulateEnablement() }
        }
        controller.edtScope.launch {
            controller.factors.collect { _ -> refreshSimulateEnablement() }
        }
        controller.edtScope.launch {
            controller.executionMode.collect { mode ->
                val wantSeq = mode == ksl.app.config.ExecutionMode.SEQUENTIAL
                if (sequentialRadio.isSelected != wantSeq) sequentialRadio.isSelected = wantSeq
                if (concurrentRadio.isSelected == wantSeq) concurrentRadio.isSelected = !wantSeq
            }
        }
    }

    private fun wireOutputConfigCollector() {
        controller.edtScope.launch {
            controller.outputConfig.collect { cfg ->
                if (enableDbCheckbox.isSelected != cfg.enableKSLDatabase) {
                    enableDbCheckbox.isSelected = cfg.enableKSLDatabase
                }
                if (analysisNameField.text != cfg.analysisName && !analysisNameField.hasFocus()) {
                    analysisNameField.text = cfg.analysisName
                }
                if (databasePolicyCombo.selectedItem != cfg.databasePolicy) {
                    databasePolicyCombo.selectedItem = cfg.databasePolicy
                }
            }
        }
    }

    private fun wireEventNotifications() {
        controller.edtScope.launch {
            controller.eventFlow.collect { ev ->
                when (ev) {
                    is RunEvent.RunFailed ->
                        notifications.show(
                            "Run failed: ${describeError(ev.error)}",
                            NotificationSeverity.ERROR
                        )
                    is RunEvent.DesignPointCompleted ->
                        if (ev.snapshot == null) {
                            notifications.show(
                                "Design point ${ev.pointId} failed.",
                                NotificationSeverity.WARNING
                            )
                        }
                    else -> { /* console drawer renders the rest */ }
                }
            }
        }
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                if (result is RunResult.BatchCompleted) {
                    notifications.show(
                        "Run completed: ${result.summary.completedItems} of " +
                            "${result.summary.totalItems} design points.",
                        NotificationSeverity.INFO
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
            is ExperimentAppController.LoadBundleResult.Loaded -> {
                val ids = outcome.newBundleIds.joinToString(", ")
                notifications.show(
                    "Loaded ${outcome.newBundleIds.size} bundle(s): $ids",
                    NotificationSeverity.INFO
                )
            }
            ExperimentAppController.LoadBundleResult.NoBundles ->
                notifications.show(
                    "$path declares no KSLModelBundle service (or all of its bundles are already loaded).",
                    NotificationSeverity.WARNING
                )
            is ExperimentAppController.LoadBundleResult.Failed ->
                notifications.show("Could not load $path: ${outcome.reason}", NotificationSeverity.ERROR)
        }
    }

    // ── File menu handlers ─────────────────────────────────────────────────

    private fun handleNew() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and start a new experiment?")) return
        controller.resetConfiguration()
    }

    private fun handleOpen() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and open another configuration?")) return
        val startDir = WorkspaceLayout.configsDir(controller.appWorkspace, createIfMissing = true)
        val chooser = JFileChooser(startDir.toFile()).apply {
            dialogTitle = "Open Configuration"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Configuration TOML (*.toml)", "toml")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path: Path = chooser.selectedFile?.toPath() ?: return
        val text = try {
            Files.readString(path)
        } catch (t: Throwable) {
            notifications.show(
                "Could not read $path: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
            return
        }
        val config = try {
            ExperimentConfigurationToml.decode(text)
        } catch (t: Throwable) {
            notifications.show(
                "Failed to parse configuration: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
            return
        }
        when (val outcome = controller.loadConfiguration(config)) {
            is ExperimentAppController.LoadResult.Loaded -> {
                controller.markSaved(path)
                notifications.dismissAll()
                outcome.warnings.forEach {
                    notifications.show(it, NotificationSeverity.WARNING)
                }
                notifications.show("Opened ${path.fileName}", NotificationSeverity.INFO)
            }
            is ExperimentAppController.LoadResult.Failed -> {
                notifications.show(outcome.reason, NotificationSeverity.ERROR)
            }
        }
    }

    private fun handleSave() {
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
            "The configuration file is no longer at:\n  $missing\n\n" +
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
     *  Default filename suggested in the Save Configuration As… dialog.
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
        val startDir = WorkspaceLayout.configsDir(controller.appWorkspace, createIfMissing = true)
        val defaultName = defaultSaveAsName()
        val chooser = JFileChooser(startDir.toFile()).apply {
            dialogTitle = "Save Configuration"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Configuration TOML (*.toml)", "toml")
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
                "Replace Configuration",
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
            notifications.show(
                "Cannot save: ${t.message ?: "model reference is required"}",
                NotificationSeverity.WARNING
            )
            return
        }
        val text = try {
            ExperimentConfigurationToml.encode(config)
        } catch (t: Throwable) {
            notifications.show(
                "Failed to encode configuration: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
            return
        }
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        } catch (t: Throwable) {
            notifications.show(
                "Could not write $path: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
            return
        }
        controller.markSaved(path)
        notifications.show("Saved ${path.fileName}", NotificationSeverity.INFO)
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
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "configuration" }

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
