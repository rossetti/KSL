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

package ksl.app.swing.scenario

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ksl.app.config.RunConfigurationToml
import ksl.app.editor.BundleLibraryController
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.settings.WorkspaceLayout
import ksl.app.comparison.BatchCompletedComparisonSource
import ksl.app.swing.common.notification.Notifications
import ksl.app.swing.common.runcontrol.ConsoleCategory
import ksl.app.swing.common.runcontrol.ConsoleDrawer
import ksl.app.swing.common.runcontrol.ConsoleLogPanel
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import java.awt.BorderLayout
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
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Default top-level frame for a `kslScenarioApp(...)` instance.
 *
 * **Phase D scope.**  File menu (New / Open / Save / Save As /
 * Set Working Directory / Recent / Exit), notifications overlay,
 * window-title sync with [ScenarioAppController.currentFile] /
 * [ScenarioAppController.isDirty], and a placeholder central panel
 * where Phase E's Scenarios tab will land.
 *
 * Closing the window closes the [ScenarioAppController].
 */
class ScenarioAppFrame(
    private val controller: ScenarioAppController
) : JFrame(controller.appName) {

    private val notifications: Notifications = Notifications(rootPane.layeredPane)

    private lateinit var saveItem: JMenuItem
    private val bundleStatusLabel: JLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    private val simulateButton = JButton("Simulate").apply {
        // Disabled at construction; the wireRunIndicators collectors
        // re-evaluate the combined (not-running AND has-runnable-scenario)
        // gate as soon as they subscribe.
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
        toolTipText = "Capture each scenario's results in the shared KSL SQLite database " +
            "(<workspace>/output/<analysisName>/).  Required for downstream Comparison-Analyzer queries."
    }

    /** Editable text field for [ksl.app.config.OutputConfig.analysisName].
     *  Round-trips through the controller's setAnalysisName mutator
     *  on focus loss and Enter.  The value the user types is stored
     *  as-is; the substrate sanitises at the points that touch the
     *  filesystem.  Disabled while a run is in flight. */
    private val analysisNameField = javax.swing.JTextField(
        controller.outputConfig.value.analysisName, 16
    ).apply {
        toolTipText = "Identity for this analysis.  Names the output subdirectory " +
            "<workspace>/output/<analysisName>/ and the SQLite database file.  " +
            "Re-running the same analysis re-uses the same folder."
    }

    /** Dropdown for [ksl.app.config.DatabasePolicy] — what to do when
     *  <analysisName>.db already exists on disk at Simulate time.
     *  Two options: OVERWRITE (delete & replace) and NEW (timestamp
     *  suffix, keep the existing file). */
    private val databasePolicyCombo = javax.swing.JComboBox(
        arrayOf(ksl.app.config.DatabasePolicy.OVERWRITE, ksl.app.config.DatabasePolicy.NEW)
    ).apply {
        selectedItem = controller.outputConfig.value.databasePolicy
        toolTipText = "What to do when the analysis database already exists.  " +
            "OVERWRITE: delete and replace.  NEW: keep the old file, write to " +
            "<analysisName>_<timestamp>.db alongside it.  KSL's schema rejects " +
            "duplicate experiment names, so there is no append option."
    }

    private val consolePanel = ConsoleLogPanel(
        eventFlow = controller.eventFlow,
        scope = controller.edtScope,
        formatter = scenarioAwareFormatter(),
        hiddenCategories = setOf(ConsoleCategory.STDOUT),
        severityClassifier = scenarioAwareSeverity()
    )
    private val consoleDrawer = ConsoleDrawer(console = consolePanel, showCaptureToggle = false)

    /**
     *  Custom formatter that overrides the default's "(failed)" suffix
     *  on `ScenarioCompleted` events whose `snapshot == null` *and*
     *  whose row is in CANCELLED status — those completions are
     *  user-cancelled, not failed.  All other events fall through to
     *  [ksl.app.swing.common.runcontrol.DefaultEventFormatter].
     *
     *  Reads from `controller.scenarioStatuses` rather than the
     *  controller's private cancel-intent fields because the status
     *  has already been set by the controller's event handler by the
     *  time the console drawer's subscriber sees the event (the
     *  handler updates state before emitting onto [controller.eventFlow]).
     */
    private fun scenarioAwareFormatter() = ksl.app.swing.common.runcontrol.EventFormatter { event ->
        if (event is RunEvent.ScenarioCompleted &&
            event.snapshot == null &&
            controller.scenarioStatuses.value[event.scenarioName] ==
                ScenarioAppController.ScenarioStatus.CANCELLED
        ) {
            "Scenario ${event.scenarioName} (${event.index}/${event.totalScenarios}) (cancelled)"
        } else {
            ksl.app.swing.common.runcontrol.DefaultEventFormatter.format(event)
        }
    }

    /**
     *  Custom severity classifier — same idea as
     *  [scenarioAwareFormatter] but for the `[INFO]/[WARN]/[ERR]`
     *  prefix and the severity-filter pipeline.  User-cancelled
     *  scenarios are INFO (an intentional stop, not an error).  All
     *  other events fall through to [ConsoleLogPanel.severityOf].
     */
    private fun scenarioAwareSeverity(): (RunEvent) -> ksl.app.swing.common.runcontrol.ConsoleSeverity = { event ->
        if (event is RunEvent.ScenarioCompleted &&
            event.snapshot == null &&
            controller.scenarioStatuses.value[event.scenarioName] ==
                ScenarioAppController.ScenarioStatus.CANCELLED
        ) {
            ksl.app.swing.common.runcontrol.ConsoleSeverity.INFO
        } else {
            ksl.app.swing.common.runcontrol.ConsoleLogPanel.severityOf(event)
        }
    }

    /** Banner shown when the document has been edited since the
     *  most recent terminal run.  Hidden when no results exist or
     *  the document is in sync with the last submitted state. */
    private val staleResultsBanner: JLabel = JLabel(
        " Results are from a previous run — the document has been edited since."
    ).apply {
        isOpaque = true
        background = java.awt.Color(0xFF, 0xF4, 0xCC)
        foreground = java.awt.Color(0x66, 0x55, 0x00)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, java.awt.Color(0xE0, 0xC8, 0x80)),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)
        )
        isVisible = false
    }

    companion object {
        private const val SAVE_BASE_TEXT: String = "Save Scenarios"
        private const val SAVE_DIRTY_TEXT: String = "Save Scenarios *"
        private const val CONFIG_TOOLTIP: String =
            "Save / open the scenarios document (scenarios list, output options, " +
                "and execution mode) as a TOML file under <workspace>/configs/."
    }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(960, 680)

        jMenuBar = buildMenuBar()
        contentPane.layout = BorderLayout()
        val scenariosTab = ScenariosTablePanel(
            controller,
            addScenarioProvider = this::openAddScenarioDialog,
            openEditor = this::openScenarioEditor
        )
        val scenarioReportsTab = ksl.app.swing.common.batchreports.BatchReportsTabPanel(
            lastResultFlow = controller.lastResult,
            runningFlow = controller.runningFlow,
            edtScope = controller.edtScope,
            workspaceProvider = { controller.appWorkspace },
            analysisNameProvider = { controller.outputConfig.value.analysisName },
            // itemTypeName / itemTypeNamePlural / file-stem defaults
            // preserve the Scenario app's pre-extraction wording and
            // on-disk filenames — no user-visible change.
            notifier = notifications
        )
        val comparisonAnalyzerTab = ksl.app.swing.common.comparison.ComparisonAnalyzerTabPanel(
            defaultOutputDirProvider = {
                ksl.app.session.AppWorkspacePaths.reportsDir(
                    controller.appWorkspace,
                    controller.outputConfig.value.analysisName
                )
            },
            defaultFormatsProvider = { controller.outputConfig.value.reports },
            notifier = notifications
        )
        val tabs = javax.swing.JTabbedPane().apply {
            addTab("Scenarios", scenariosTab)
            addTab("Scenario Reports", scenarioReportsTab)
            addTab("Comparison Analyzer", comparisonAnalyzerTab)
        }
        // Q2 in the lifecycle plan — refresh on tab activation.
        // ChangeListener fires whenever the active tab changes; each
        // panel exposes a no-arg refreshFromDisk() hook that's safe to
        // call when the tab isn't active too.  Cheaper and cleaner than
        // the dialog-era WindowFocusListener (which over-fired on any
        // app re-focus regardless of the active tab).
        tabs.addChangeListener {
            when (tabs.selectedComponent) {
                scenarioReportsTab -> scenarioReportsTab.refreshFromDisk()
                comparisonAnalyzerTab -> comparisonAnalyzerTab.refreshFromDisk()
            }
        }
        // Feed the Comparison Analyzer tab fresh sources whenever the
        // batch result changes (R1: lastResult is nulled on Simulate
        // and re-populated when the batch completes).  An empty list
        // shows the tab's empty-state card.
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                val batch = result as? ksl.app.session.RunResult.BatchCompleted
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
                            "Reports will appear when the batch completes." +
                            "</div></html>"
                    )
                } else {
                    comparisonAnalyzerTab.setEmptyStateText(
                        "<html><div style='text-align:center;'>" +
                            "No completed batch with per-replication data yet.<br>" +
                            "Run the scenarios to populate this tab." +
                            "</div></html>"
                    )
                }
            }
        }
        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buildRunToolbar())
            add(staleResultsBanner)
        }
        contentPane.add(topStack, BorderLayout.NORTH)
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(buildBottomStack(), BorderLayout.SOUTH)

        wireWindowTitle()
        wireDirtyIndicators()
        wireBundleStatus()
        wireRunIndicators()
        wireStaleResultsBanner()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                controller.close()
            }
        })
    }

    private fun buildMenuBar(): JMenuBar {
        val setWdAction = SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
        val recentMenu = RecentWorkingDirectoriesMenu(controller.settingsStore, controller.edtScope)
        val menuShortcutKey = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        val newItem = JMenuItem(object : AbstractAction("New Scenarios") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleNew() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutKey)
            toolTipText = "Start a new, empty scenarios document.  Discards the current " +
                "scenarios, output settings, and file association.  Prompts to save " +
                "unsaved changes first."
        }
        val openItem = JMenuItem(object : AbstractAction("Open Scenarios…") {
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
        val saveAsItem = JMenuItem(object : AbstractAction("Save Scenarios As…") {
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
        val loadedBundlesItem = JMenuItem(object : AbstractAction("Loaded Bundles…") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                LoadedBundlesDialog.show(this@ScenarioAppFrame, controller.loadedBundles.value)
            }
        })
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
                add(loadedBundlesItem)
            })
            add(JMenu("View").apply {
                add(ksl.app.swing.common.appearance.ThemeMenu.build(controller.edtScope))
            })
        }
    }

    private fun buildStatusBar(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(2, 0, 2, 0)
        )
        add(bundleStatusLabel, BorderLayout.WEST)
    }

    private fun buildBottomStack(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(consoleDrawer)
        add(buildStatusBar())
    }

    private fun buildRunToolbar(): JPanel = JPanel().apply {
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
        // Analysis name commits on focus loss and on Enter — same
        // model as common form fields.  setAnalysisName is a no-op
        // when the value is unchanged, so the EDT collector that
        // pushes outputConfig changes back into the field doesn't
        // cause re-entrant churn.
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

    private fun handleSimulate() {
        if (controller.runningFlow.value) return
        val runnable = controller.scenarios.value.count { !it.skipOnRun }
        if (runnable == 0) {
            notifications.warn(
                "No scenarios to run.  Add at least one scenario and ensure it isn't skipped."
            )
            return
        }
        if (controller.isDirty.value) {
            if (!handleDirtyOnRun()) return
        }
        consolePanel.clear()
        if (!controller.submit()) {
            notifications.error("Could not start the run.")
        }
    }

    /** Apply the combined gate for the Simulate button.  Called from
     *  every collector that touches one of the inputs (running flag,
     *  scenarios list).  Q4 in the lifecycle plan: an empty scenarios
     *  list disables Simulate. */
    private fun refreshSimulateEnablement() {
        val running = controller.runningFlow.value
        val hasRunnable = controller.scenarios.value.any { !it.skipOnRun }
        simulateButton.isEnabled = !running && hasRunnable
    }

    private fun wireRunIndicators() {
        // Simulate is enabled when: not currently running AND there is
        // at least one runnable scenario.  The controller already
        // returns false from submit() under those conditions, but we
        // surface the gate visually so the user doesn't reach for a
        // button that would no-op.  Cancel and the mode/db toggles
        // stay on the simpler "not running" gate.
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
            controller.scenarios.collect { _ ->
                refreshSimulateEnablement()
            }
        }
        controller.edtScope.launch {
            controller.executionMode.collect { mode ->
                val wantSeq = mode == ksl.app.config.ExecutionMode.SEQUENTIAL
                if (sequentialRadio.isSelected != wantSeq) sequentialRadio.isSelected = wantSeq
                if (concurrentRadio.isSelected == wantSeq) concurrentRadio.isSelected = !wantSeq
            }
        }
        controller.edtScope.launch {
            controller.outputConfig.collect { cfg ->
                if (enableDbCheckbox.isSelected != cfg.enableKSLDatabase) {
                    enableDbCheckbox.isSelected = cfg.enableKSLDatabase
                }
                // Mirror analysisName + databasePolicy into the
                // toolbar widgets.  Guard the analysisName push with
                // a focus check so the field doesn't stomp the user's
                // in-progress edit when an unrelated outputConfig
                // change fires.
                if (analysisNameField.text != cfg.analysisName && !analysisNameField.hasFocus()) {
                    analysisNameField.text = cfg.analysisName
                }
                if (databasePolicyCombo.selectedItem != cfg.databasePolicy) {
                    databasePolicyCombo.selectedItem = cfg.databasePolicy
                }
            }
        }
        controller.edtScope.launch {
            controller.eventFlow.collect { ev ->
                when (ev) {
                    is RunEvent.RunFailed ->
                        notifications.error(
                            "Run failed: ${describeError(ev.error)}"
                        )
                    is RunEvent.ScenarioCompleted ->
                        if (ev.snapshot == null) {
                            notifications.warn(
                                "Scenario '${ev.scenarioName}' failed."
                            )
                        }
                    else -> { /* console drawer renders the rest */ }
                }
            }
        }
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                if (result is RunResult.BatchCompleted) {
                    notifications.info(
                        "Run completed: ${result.summary.completedItems} of " +
                            "${result.summary.totalItems} scenarios."
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

    private fun openAddScenarioDialog(): ksl.app.config.ScenarioSpec? =
        AddScenarioDialog.prompt(
            this,
            controller.loadedBundles.value,
            controller.scenarios.value.map { it.name }.toSet()
        )

    private val openEditors: MutableMap<String, ScenarioEditorWindow> = mutableMapOf()

    private fun openScenarioEditor(index: Int) {
        try {
            val spec = controller.scenarios.value.getOrNull(index) ?: return
            // Re-focus an existing editor for this scenario rather than
            // spawning a duplicate.  Keyed by current name (good enough —
            // updateScenario renames close the window before the rename
            // commits).
            openEditors[spec.name]?.let { existing ->
                existing.toFront()
                existing.requestFocus()
                return
            }
            val buffer = ScenarioEditBuffer.probe(spec, controller.loadedBundles.value)
            val others = controller.scenarios.value
                .filterIndexed { i, _ -> i != index }
                .map { it.name }
                .toSet()
            val window = ScenarioEditorWindow(controller, index, buffer, others)
            openEditors[spec.name] = window
            window.addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent?) {
                    openEditors.remove(spec.name)
                }
            })
            window.pack()
            window.setLocationRelativeTo(this)
            window.isVisible = true
        } catch (t: Throwable) {
            notifications.error(
                "Could not open scenario editor: ${t.message ?: t::class.simpleName}"
            )
        }
    }

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

    // ── File menu handlers ──────────────────────────────────────────────────

    private fun handleNew() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and reset the document?")) return
        controller.resetConfiguration()
    }

    private fun handleOpen() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and open another scenarios document?")) return
        val startDir = WorkspaceLayout.configsDir(controller.appWorkspace, createIfMissing = true)
        val chooser = JFileChooser(startDir.toFile()).apply {
            dialogTitle = "Open Scenarios"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Scenarios TOML (*.toml)", "toml")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path: Path = chooser.selectedFile?.toPath() ?: return
        val text = try {
            Files.readString(path)
        } catch (t: Throwable) {
            notifications.error("Could not read $path: ${t.message ?: t::class.simpleName}")
            return
        }
        val config = try {
            RunConfigurationToml.decode(text)
        } catch (t: Throwable) {
            notifications.error(
                "Failed to parse scenarios: ${t.message ?: t::class.simpleName}"
            )
            return
        }
        when (val outcome = controller.loadConfiguration(config)) {
            is ScenarioAppController.LoadResult.Loaded -> {
                controller.markSaved(path)
                // Wipe any stale notifications from the previous
                // document — error chips from a prior failed run,
                // info chips from a prior completed run — so the
                // overlay starts fresh.  Warnings / success info
                // emitted below appear on a clean slate.
                notifications.dismissAll()
                outcome.warnings.forEach {
                    notifications.warn(it)
                }
                val unresolved = controller.unresolvedBundleReferences()
                if (unresolved.isNotEmpty()) {
                    val list = unresolved.joinToString("; ") { "${it.first}/${it.second}" }
                    notifications.warn(
                        "Unresolved model references — load matching bundle JAR(s): $list"
                    )
                }
                notifications.info("Opened ${path.fileName}")
            }
            is ScenarioAppController.LoadResult.Rejected -> {
                notifications.error(outcome.reason)
            }
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
     *    that's the most common reason for changing the Analysis name.
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
     *  "run anyway" choice — mirrors the Single app's behaviour).
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
            "You have unsaved scenarios changes.$pathLine",
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
        val existing = controller.currentFile.value
        if (existing == null) {
            handleSaveAs()
            return
        }
        // The file was loaded earlier in the session, but check that
        // it still exists on disk.  If it's been moved or deleted
        // externally, silently recreating it at the same path would
        // surprise a user who removed it deliberately — prompt
        // instead, with explicit options to recreate, redirect, or
        // cancel.
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
        val choice = javax.swing.JOptionPane.showOptionDialog(
            this,
            "The scenarios file is no longer at:\n  $missing\n\n" +
                "It may have been moved or deleted outside the app.\n" +
                "Recreate it at this path, or save to a new location?",
            "File No Longer Exists",
            javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0]
        )
        return when (choice) {
            0 -> MissingFileChoice.RECREATE_HERE
            1 -> MissingFileChoice.SAVE_AS
            else -> MissingFileChoice.CANCEL          // CANCEL_OPTION or CLOSED_OPTION
        }
    }

    /**
     *  Default filename suggested in the Save Scenarios As… dialog.
     *  Preference order:
     *
     *  1. The user-set analysis name, sanitised for the filesystem.
     *     This is the canonical identity for the document and is what
     *     the user expects to see in the dialog when they've named
     *     their analysis.
     *  2. The current file's name, when one is loaded but no analysis
     *     name has been set (legacy documents that pre-date the
     *     analysisName field).
     *  3. The application name, as a last-resort filler for a fresh
     *     document where the user has neither saved nor named the
     *     analysis.
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
            dialogTitle = "Save Scenarios"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Scenarios TOML (*.toml)", "toml")
            selectedFile = startDir.resolve(defaultName).toFile()
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var path: Path = chooser.selectedFile?.toPath() ?: return
        if (path.fileName.toString().substringAfterLast('.', "") != "toml") {
            path = path.resolveSibling("${path.fileName}.toml")
        }
        if (Files.exists(path)) {
            val overwrite = javax.swing.JOptionPane.showConfirmDialog(
                this,
                "${path.fileName} already exists.\nReplace it?",
                "Replace Scenarios",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE
            )
            if (overwrite != javax.swing.JOptionPane.YES_OPTION) return
        }
        writeConfigurationTo(path)
    }

    private fun writeConfigurationTo(path: Path) {
        val config = controller.currentConfiguration()
        val text = try {
            RunConfigurationToml.encode(config)
        } catch (t: Throwable) {
            notifications.error(
                "Failed to encode scenarios: ${t.message ?: t::class.simpleName}"
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
        val choice = javax.swing.JOptionPane.showConfirmDialog(
            this, question, "Unsaved Changes",
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE
        )
        return choice == javax.swing.JOptionPane.YES_OPTION
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "scenarios" }

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
            kotlinx.coroutines.flow.combine(
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
