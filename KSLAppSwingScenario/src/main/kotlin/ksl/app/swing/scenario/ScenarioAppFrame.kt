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
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.notification.NotificationSeverity
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

    private val simulateButton = JButton("Simulate")
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
            "(<workspace>/output/).  Required for downstream Comparison-Analyzer queries."
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
        private const val SAVE_BASE_TEXT: String = "Save Configuration"
        private const val SAVE_DIRTY_TEXT: String = "Save Configuration *"
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
        val tabs = javax.swing.JTabbedPane().apply {
            addTab("Scenarios", scenariosTab)
            addTab("Reports", ReportsTabPanel(controller, onMessage = { msg, sev ->
                notifications.show(msg, sev)
            }))
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
        val newItem = JMenuItem(object : AbstractAction("Reset to Defaults") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleNew() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutKey)
            toolTipText = "Discard all scenarios and forget the currently-associated " +
                "configuration file.  Returns the document to an empty state."
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
        add(simulateButton)
        add(Box.createHorizontalStrut(8))
        add(cancelButton)
        add(Box.createHorizontalStrut(16))
        add(JLabel("Mode:"))
        add(Box.createHorizontalStrut(4))
        add(sequentialRadio)
        add(concurrentRadio)
        add(Box.createHorizontalStrut(16))
        add(enableDbCheckbox)
        add(Box.createHorizontalGlue())
    }

    private fun handleSimulate() {
        if (controller.runningFlow.value) return
        val runnable = controller.scenarios.value.count { !it.skipOnRun }
        if (runnable == 0) {
            notifications.show(
                "No scenarios to run.  Add at least one scenario and ensure it isn't skipped.",
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
        consolePanel.clear()
        if (!controller.submit()) {
            notifications.show("Could not start the run.", NotificationSeverity.ERROR)
        }
    }

    private fun wireRunIndicators() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                simulateButton.isEnabled = !running
                cancelButton.isEnabled = running
                sequentialRadio.isEnabled = !running
                concurrentRadio.isEnabled = !running
                enableDbCheckbox.isEnabled = !running
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
            }
        }
        controller.edtScope.launch {
            controller.eventFlow.collect { ev ->
                when (ev) {
                    is RunEvent.RunFailed ->
                        notifications.show(
                            "Run failed: ${describeError(ev.error)}",
                            NotificationSeverity.ERROR
                        )
                    is RunEvent.ScenarioCompleted ->
                        if (ev.snapshot == null) {
                            notifications.show(
                                "Scenario '${ev.scenarioName}' failed.",
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
                            "${result.summary.totalItems} scenarios.",
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
            notifications.show(
                "Could not open scenario editor: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
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
            is ScenarioAppController.LoadBundleResult.Loaded -> {
                val ids = outcome.newBundleIds.joinToString(", ")
                notifications.show(
                    "Loaded ${outcome.newBundleIds.size} bundle(s): $ids",
                    NotificationSeverity.INFO
                )
            }
            ScenarioAppController.LoadBundleResult.NoBundles ->
                notifications.show(
                    "$path declares no KSLModelBundle service (or all of its bundles are already loaded).",
                    NotificationSeverity.WARNING
                )
            is ScenarioAppController.LoadBundleResult.Failed ->
                notifications.show("Could not load $path: ${outcome.reason}", NotificationSeverity.ERROR)
        }
    }

    // ── File menu handlers ──────────────────────────────────────────────────

    private fun handleNew() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and reset the document?")) return
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
            notifications.show("Could not read $path: ${t.message ?: t::class.simpleName}", NotificationSeverity.ERROR)
            return
        }
        val config = try {
            RunConfigurationToml.decode(text)
        } catch (t: Throwable) {
            notifications.show(
                "Failed to parse configuration: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
            return
        }
        when (val outcome = controller.loadConfiguration(config)) {
            is ScenarioAppController.LoadResult.Loaded -> {
                controller.markSaved(path)
                outcome.warnings.forEach {
                    notifications.show(it, NotificationSeverity.WARNING)
                }
                val unresolved = controller.unresolvedBundleReferences()
                if (unresolved.isNotEmpty()) {
                    val list = unresolved.joinToString("; ") { "${it.first}/${it.second}" }
                    notifications.show(
                        "Unresolved model references — load matching bundle JAR(s): $list",
                        NotificationSeverity.WARNING
                    )
                }
                notifications.show("Opened ${path.fileName}", NotificationSeverity.INFO)
            }
            is ScenarioAppController.LoadResult.Rejected -> {
                notifications.show(outcome.reason, NotificationSeverity.ERROR)
            }
        }
    }

    private fun handleSave() {
        val existing = controller.currentFile.value
        if (existing == null) handleSaveAs() else writeConfigurationTo(existing)
    }

    private fun handleSaveAs() {
        val startDir = WorkspaceLayout.configsDir(controller.appWorkspace, createIfMissing = true)
        val defaultName = (controller.currentFile.value?.fileName?.toString())
            ?: "${sanitizeFileName(controller.appName)}.toml"
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
            val overwrite = javax.swing.JOptionPane.showConfirmDialog(
                this,
                "${path.fileName} already exists.\nReplace it?",
                "Replace Configuration",
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
        val choice = javax.swing.JOptionPane.showConfirmDialog(
            this, question, "Unsaved Changes",
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE
        )
        return choice == javax.swing.JOptionPane.YES_OPTION
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "configuration" }

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
