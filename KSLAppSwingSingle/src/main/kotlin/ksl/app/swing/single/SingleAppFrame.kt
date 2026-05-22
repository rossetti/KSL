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

package ksl.app.swing.single

import kotlinx.coroutines.launch
import ksl.app.config.RunConfigurationToml
import ksl.app.session.RunResult
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.common.notification.Notifications
import ksl.app.swing.common.runcontrol.ConsoleCategory
import ksl.app.swing.common.runcontrol.ConsoleDrawer
import ksl.app.swing.common.runcontrol.ConsoleLogPanel
import ksl.app.swing.common.validation.DocumentHealthBanner
import ksl.app.swing.common.validation.WidgetPathRegistry
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.results.DefaultDesktopOpener
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import ksl.app.swing.common.editor.ControlOverridesPanel
import ksl.app.swing.common.editor.ParameterPanel
import ksl.app.swing.common.editor.RVOverridesPanel
import ksl.app.swing.single.defaults.DefaultOutputOptionsPanel
import ksl.app.swing.single.defaults.StandardReportFormat
import ksl.app.swing.single.defaults.StandardReportMaterializer
import ksl.app.swing.single.defaults.StandardReportOutcome
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JFileChooser
import javax.swing.KeyStroke
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.WindowConstants
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Default top-level frame for a `kslSingleApp(...)` instance.
 *
 * Layout (top → bottom):
 *
 *  - Menu bar (File ▸ Set Working Directory… / Recent / Exit).
 *  - [DocumentHealthBanner] for validation findings.
 *  - **Run toolbar**: *Run* and *Cancel* buttons on the left, a
 *    single-line **run-status strip** on the right (idle / running /
 *    completed badge with one-line summary).  Always visible so
 *    Run is one click from anywhere in the app.
 *  - **Centre tabs**:
 *      1. *Run Control* — [DefaultParameterPanel] for analyst-facing
 *         experiment overrides; takes the full tab area.
 *      2. *Control Overrides* — annotated-property overrides
 *         (hidden when the model exposes no controls).
 *      3. *Reports* — standard-report buttons.  Disabled until a
 *         snapshot exists (after a successful run).
 *  - **Console drawer** (above the workspace status bar):
 *         collapsible [ConsoleDrawer] hosting a [ConsoleLogPanel].
 *         Collapsed by default; the header strip shows a per-run
 *         `INFO/WARN/ERR` count so the user gets a glance signal of
 *         "anything interesting happened?" without expanding.  The
 *         ORCHESTRATOR category chip is suppressed inside the
 *         console because the single-run app never emits
 *         orchestrator events.
 *  - [WorkspaceStatusBar] strip at the very bottom.
 *  - [Notifications] overlay attached to the frame's layered pane.
 *
 * Closing the window closes the [SingleAppController], which cancels
 * any in-flight run and shuts the session down.
 */
class SingleAppFrame(
    private val controller: SingleAppController
) : JFrame(controller.appName) {

    private val notifications: Notifications = Notifications(rootPane.layeredPane)
    private val registry: WidgetPathRegistry = WidgetPathRegistry()

    private val runAction = object : AbstractAction("Simulate") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            // If the analyst has unsaved changes, give them an opportunity
            // to persist before kicking off the run.  This matches the
            // "Run = commit" mental model: edits flow into the run,
            // and the file on disk should reflect what was actually
            // executed.  Returns false if the user picks Cancel — abort
            // the run entirely.
            if (!handleDirtyOnRun()) return
            // Clear the console synchronously *before* submitting.  The
            // validator and orchestrator run on the EDT and call
            // modelBuilder.build() on this thread; anything they (or the
            // user's build code) write to stdout/stderr while capture is
            // on must survive into the visible run log.  Clearing here
            // — and letting ConsoleLogPanel.autoClearOnRunStart stay
            // off — gives a single, predictable "this run's log starts
            // now" boundary aligned with the user's click.
            consolePanel.clear()
            controller.submit()
        }
    }

    /**
     * Auto-save dance when Run is clicked with a dirty configuration.
     *
     *  - Clean → returns `true` immediately, nothing to do.
     *  - Dirty *and* a file is already associated → silently writes to
     *    that file (analyst has previously chosen where; the click is
     *    consent), notifies, and returns `true`.
     *  - Dirty *and* no file associated → prompts with three options:
     *      *Save…* (opens Save As, returns `true` only after a successful
     *        write — Cancel in the chooser aborts the run too),
     *      *Run without saving* (returns `true`, runs with dirty state
     *        intact),
     *      *Cancel* (returns `false`, abort the run).
     *
     * Returning `false` short-circuits the Run.
     */
    private fun handleDirtyOnRun(): Boolean {
        if (!controller.isDirty.value) return true
        val existing = controller.currentFile.value
        if (existing != null) {
            writeConfigurationTo(existing)
            return true
        }
        val choice = javax.swing.JOptionPane.showOptionDialog(
            this,
            "The configuration has unsaved changes.\n" +
                "Save it to a file before simulating?",
            "Unsaved Configuration",
            javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
            javax.swing.JOptionPane.QUESTION_MESSAGE,
            null,
            arrayOf<Any>("Save…", "Simulate without saving", "Cancel"),
            "Save…"
        )
        return when (choice) {
            0 -> {
                // Save…  Returns once the chooser closes.  If the user
                // picked a file, currentFile is now set; if they cancelled
                // the chooser, currentFile is still null.  Either way the
                // run proceeds — declining Save As after asking for it is
                // an explicit "run anyway" choice.
                handleSaveAs()
                true
            }
            1 -> true                          // Run without saving
            else -> false                      // Cancel (incl. dialog dismissed)
        }
    }
    private val cancelAction = object : AbstractAction("Cancel") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) { controller.cancel() }
    }.apply { isEnabled = false }

    /**
     * Toolbar action equivalent of *File → Reset to Model Defaults*.
     * Visible in the toolbar so users can find the "discard everything
     * and start fresh" gesture without opening a menu — answers the
     * "what does this app's New mean and how do I trigger it?" question
     * that menu-only placement leaves implicit.
     */
    private val resetAction = object : AbstractAction("Reset to Defaults") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleNew() }
    }

    private val parameterPanel = ParameterPanel(controller)
    private val controlOverridesPanel = ControlOverridesPanel(controller)
    private val rvOverridesPanel = RVOverridesPanel(controller)
    /**
     * Snapshot-availability flow derived from `controller.lastResult`.
     * `true` only when the most recent terminal result carries a
     * snapshot (Completed / BatchCompleted), which is the precondition
     * for the on-demand report buttons to be meaningful.
     */
    private val snapshotAvailable: kotlinx.coroutines.flow.StateFlow<Boolean> =
        kotlinx.coroutines.flow.MutableStateFlow(false).also { flow ->
            controller.edtScope.launch {
                controller.lastResult.collect { result ->
                    flow.value =
                        result is RunResult.Completed || result is RunResult.BatchCompleted
                }
            }
        }

    private val outputOptionsPanel = DefaultOutputOptionsPanel(
        controller = controller,
        onStandardReport = { format -> handleStandardReport(format) },
        onAdvanced = {
            notifications.show(
                "Advanced report configuration is not yet wired (N5).",
                NotificationSeverity.WARNING
            )
        },
        snapshotAvailable = snapshotAvailable
    )
    private val consolePanel = ConsoleLogPanel(
        eventFlow = controller.eventFlow,
        scope = controller.edtScope,
        hiddenCategories = setOf(ConsoleCategory.ORCHESTRATOR)
    )
    private val consoleDrawer = ConsoleDrawer(console = consolePanel)
    private val statusStrip = RunStatusStrip()

    private val tabs = JTabbedPane()
    // Per-tab indices, recorded as tabs are added in buildTabs().  -1
    // means "not present" (e.g. the model exposes no controls / RVs).
    private var runControlTabIndex: Int = -1
    private var controlOverridesTabIndex: Int = -1
    private var rvOverridesTabIndex: Int = -1
    private var reportsTabIndex: Int = -1
    // Base titles without the modified-from-defaults indicator dot.
    private val runControlBaseTitle: String = "Run Control"
    private val controlOverridesBaseTitle: String = "Control Overrides"
    private val rvOverridesBaseTitle: String = "RV Overrides"
    private var latestSnapshotResult: RunResult? = null
    /** Save Configuration menu item — kept field-level so we can update its
     *  text to reflect the dirty state ("Save Configuration *"). */
    private lateinit var saveItem: JMenuItem
    /** Hint label rendered above the run toolbar showing how many overrides
     *  will be applied on Run.  Hidden when no overrides are present. */
    private lateinit var overridesHint: JLabel
    /** Subtle dirty-state chip in the run toolbar.  Tooltip explains. */
    private lateinit var dirtyChip: JLabel

    companion object {
        private const val SAVE_BASE_TEXT: String = "Save Configuration"
        private const val SAVE_DIRTY_TEXT: String = "Save Configuration *"
        private const val CONFIG_TOOLTIP: String =
            "Save / open the current run parameters, control overrides, and " +
                "RV overrides as a .toml file.  Does not save model output."
    }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(960, 680)

        jMenuBar = buildMenuBar()

        val banner = DocumentHealthBanner(controller.validationBus, registry, controller.edtScope)
        val toolbar = buildRunToolbar()
        val tabsCentre = buildTabs()
        val statusBar = WorkspaceStatusBar(
            store = controller.settingsStore,
            scope = controller.edtScope,
            onSetWorkingDirectory = {
                SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
                    .actionPerformed(java.awt.event.ActionEvent(this, 0, ""))
            }
        )

        val hintStrip = buildOverridesHint()
        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(banner)
            add(toolbar)
            add(hintStrip)
        }
        // Bottom stack: collapsible console drawer above the workspace
        // status bar.  The drawer sets its own preferred height based on
        // expanded/collapsed state, so BoxLayout handles both cases.
        val bottomStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(consoleDrawer)
            add(statusBar)
        }

        contentPane.apply {
            layout = BorderLayout()
            add(topStack, BorderLayout.NORTH)
            add(tabsCentre, BorderLayout.CENTER)
            add(bottomStack, BorderLayout.SOUTH)
        }

        wireRunningState()
        wireStatusStrip()
        wireTerminalNotifications()
        wireWindowTitle()
        wireDirtyIndicators()
        wireOverridesHint()
        wireTabModifiedIndicators()
        surfaceProbeFailureIfPresent()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) { controller.close() }
        })
    }

    private fun surfaceProbeFailureIfPresent() {
        val cause = controller.probeFailure ?: return
        controller.edtScope.launch {
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
        val menuShortcutKey = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        val newItem = JMenuItem(object : AbstractAction("Reset to Model Defaults") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleNew() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutKey)
            toolTipText = "Discard all overrides and forget the currently-associated " +
                "configuration file.  Returns the editor to model-default values."
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
            add(JMenu("View").apply {
                add(ksl.app.swing.common.appearance.ThemeMenu.build(controller.edtScope))
            })
        }
    }

    // ── File menu handlers ──────────────────────────────────────────────────

    /**
     * *File → New*.  Resets the controller's editor state to empty
     * defaults.  Prompts to discard if there are unsaved changes; the
     * dialog is a [javax.swing.JOptionPane.YES_NO_OPTION] confirm — there
     * is no auto-save fallback because v1 has no scratch storage to
     * fall back to.
     */
    private fun handleNew() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and reset the editor to model defaults?")) return
        controller.resetConfiguration()
    }

    /**
     * *File → Open Configuration…*.  Picks a `.toml` from
     * `<workspace>/configs/` (the canonical save location per
     * [ksl.app.settings.WorkspaceLayout.configsDir]) and asks the
     * controller to load it.  A warning notification surfaces when the
     * loaded `modelReference` does not match this app's `appName` — the
     * load still proceeds because configurations may legitimately move
     * between apps that share a model name.
     */
    private fun handleOpen() {
        if (!confirmDiscardIfDirty("Discard unsaved changes and open another configuration?")) return
        val workspace = controller.appWorkspace
        val startDir = WorkspaceLayout.configsDir(workspace, createIfMissing = true)
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
            is SingleAppController.LoadResult.Loaded -> {
                controller.markSaved(path)
                outcome.warning?.let { notifications.show(it, NotificationSeverity.WARNING) }
                notifications.show("Opened ${path.fileName}", NotificationSeverity.INFO)
            }
            is SingleAppController.LoadResult.Rejected -> {
                notifications.show(outcome.reason, NotificationSeverity.ERROR)
            }
        }
    }

    /**
     * *File → Save*.  Writes to the currently-associated file when one
     * exists, falling back to *Save As…* when not (e.g. first save).
     */
    private fun handleSave() {
        val existing = controller.currentFile.value
        if (existing == null) handleSaveAs() else writeConfigurationTo(existing)
    }

    /**
     * *File → Save As…*.  Prompts for a destination under
     * `<workspace>/configs/`, defaulting the file name to the app name.
     * Adds the `.toml` extension if the user didn't.
     */
    /**
     *  Default filename suggested in the Save Configuration As… dialog.
     *  Preference order: user-set analysisName (sanitised) → currently
     *  loaded file's name → application name.  See the same helper on
     *  `ScenarioAppFrame` for the full rationale.
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
        val workspace = controller.appWorkspace
        val startDir = WorkspaceLayout.configsDir(workspace, createIfMissing = true)
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

    /**
     * Returns `true` when the user wants to proceed (no unsaved changes,
     * or confirmed discard); `false` to abort the calling operation.
     */
    private fun confirmDiscardIfDirty(question: String): Boolean {
        if (!controller.isDirty.value) return true
        val choice = javax.swing.JOptionPane.showConfirmDialog(
            this, question, "Unsaved Changes",
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE
        )
        return choice == javax.swing.JOptionPane.YES_OPTION
    }

    /** Strip filesystem-unsafe characters from [name] for use as a default file name. */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "configuration" }

    private fun buildRunToolbar(): JComponent {
        val runButton = JButton(runAction).apply {
            toolTipText = "Simulate the model using the current run parameters, " +
                "control overrides, and RV overrides shown in the editor tabs."
        }
        val cancelButton = JButton(cancelAction)
        val resetButton = JButton(resetAction).apply {
            toolTipText = "Discard all overrides and forget the currently-associated " +
                "configuration file.  Returns the editor to model-default values."
        }
        dirtyChip = JLabel("● Unsaved").apply {
            font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
            foreground = Color(0xE6, 0x5C, 0x00)
            border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
            toolTipText =
                "Configuration has unsaved changes.  Use ⌘S to save " +
                    "(or use Save Configuration in the File menu)."
            isVisible = false
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            add(runButton)
            add(Box.createHorizontalStrut(8))
            add(cancelButton)
            add(Box.createHorizontalStrut(16))
            add(resetButton)
            add(Box.createHorizontalStrut(16))
            add(statusStrip)
            add(Box.createHorizontalGlue())
            add(dirtyChip)
        }
    }

    /**
     * Quiet status line rendered just under the run toolbar showing how
     * many overrides are pending application on the next Run.  Hidden
     * when the analyst has set zero overrides — most first-time users
     * won't see this line at all and shouldn't be visually taxed by it.
     */
    private fun buildOverridesHint(): JComponent {
        overridesHint = JLabel().apply {
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            foreground = Color(0x66, 0x66, 0x66)
            border = BorderFactory.createEmptyBorder(0, 16, 4, 16)
            isVisible = false
        }
        return overridesHint
    }

    private fun buildTabs(): JComponent {
        // Run Control tab: parameter panel only.  Console lives in the
        // bottom-of-window drawer below the tab area.
        val scrollableParameters = JScrollPane(parameterPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        runControlTabIndex = tabs.tabCount
        tabs.addTab(runControlBaseTitle, scrollableParameters)
        if (controlOverridesPanel.isVisible) {
            controlOverridesTabIndex = tabs.tabCount
            tabs.addTab(controlOverridesBaseTitle, controlOverridesPanel)
        }
        if (rvOverridesPanel.isVisible) {
            rvOverridesTabIndex = tabs.tabCount
            tabs.addTab(rvOverridesBaseTitle, rvOverridesPanel)
        }
        // Output Options is always enabled — pre-run configuration
        // (Database / CSV / report formats) must be editable BEFORE
        // the user has simulated anything.  The on-demand report
        // buttons inside the panel gate themselves on snapshot
        // availability via the `snapshotAvailable` StateFlow.
        reportsTabIndex = tabs.tabCount
        tabs.addTab("Output Options", outputOptionsPanel)
        return tabs
    }

    /**
     * Keep the window title synchronized with `appName`, the optional
     * current file name (basename only), and the dirty flag.  Format:
     *
     * ```
     * M/M/1 Queue                       (no file, clean)
     * M/M/1 Queue *                     (no file, edited)
     * M/M/1 Queue — MM1.toml            (file open, saved)
     * M/M/1 Queue — MM1.toml *          (file open, edited)
     * ```
     *
     * Two collectors keep the binding cheap and easy to reason about —
     * StateFlows are conflated so each combine() emission is the latest
     * pair.
     */
    private fun wireWindowTitle() {
        controller.edtScope.launch {
            kotlinx.coroutines.flow.combine(
                controller.currentFile,
                controller.isDirty
            ) { file, dirty -> file to dirty }
                .collect { (file, dirty) ->
                    val fileSegment = file?.fileName?.toString()?.let { " — $it" }.orEmpty()
                    val dirtyMark = if (dirty) " *" else ""
                    title = "${controller.appName}$fileSegment$dirtyMark"
                }
        }
    }

    /**
     * Bind the dirty-state chip in the run toolbar and the *Save
     * Configuration* menu-item text to [controller.isDirty].  The
     * chip is hidden when clean; visible (orange `●ï Unsaved`) when
     * dirty.  The menu item gains an asterisk suffix when dirty so a
     * user opening the File menu can tell at a glance whether they
     * have anything to save.
     */
    private fun wireDirtyIndicators() {
        controller.edtScope.launch {
            controller.isDirty.collect { dirty ->
                dirtyChip.isVisible = dirty
                saveItem.text = if (dirty) SAVE_DIRTY_TEXT else SAVE_BASE_TEXT
            }
        }
    }

    /**
     * Bind the override-count hint above the tab pane to the three
     * override flows.  Reads `N control overrides · M RV overrides
     * will be applied` when one or both are non-zero; hides
     * otherwise.  Counts include numeric, string, and JSON control
     * families together.
     */
    private fun wireOverridesHint() {
        controller.edtScope.launch {
            kotlinx.coroutines.flow.combine(
                controller.controlOverrides,
                controller.rvOverrides
            ) { controls, rvs ->
                controls.totalControls to rvs.size
            }.collect { (controlCount, rvCount) ->
                if (controlCount == 0 && rvCount == 0) {
                    overridesHint.isVisible = false
                } else {
                    val parts = mutableListOf<String>()
                    if (controlCount > 0) parts.add(
                        "$controlCount control override${if (controlCount == 1) "" else "s"}"
                    )
                    if (rvCount > 0) parts.add(
                        "$rvCount RV override${if (rvCount == 1) "" else "s"}"
                    )
                    overridesHint.text = parts.joinToString(" · ") + " will be applied on Simulate"
                    overridesHint.isVisible = true
                }
            }
        }
    }

    /**
     * Decorate each tab's title with a `•` dot indicator when its
     * content has been modified from the model's defaults.  Gives the
     * user a spatial cue — "which tabs hold edits?" — that complements
     * the global dirty chip and override-count hint.
     *
     * - *Run Control*: dot when `runOverrides != ExperimentRunOverrides()`.
     * - *Control Overrides*: dot when `controlOverrides.totalControls > 0`.
     * - *RV Overrides*: dot when `rvOverrides.isNotEmpty()`.
     *
     * Tab indices that weren't added (e.g. *Control Overrides* when
     * the model has no controls) are simply skipped.
     */
    private fun wireTabModifiedIndicators() {
        if (runControlTabIndex >= 0) {
            controller.edtScope.launch {
                controller.runOverrides.collect { value ->
                    val modified = value != ksl.app.config.ExperimentRunOverrides()
                    tabs.setTitleAt(runControlTabIndex, titleWithDot(runControlBaseTitle, modified))
                }
            }
        }
        if (controlOverridesTabIndex >= 0) {
            controller.edtScope.launch {
                controller.controlOverrides.collect { value ->
                    val modified = value.totalControls > 0
                    tabs.setTitleAt(
                        controlOverridesTabIndex,
                        titleWithDot(controlOverridesBaseTitle, modified)
                    )
                }
            }
        }
        if (rvOverridesTabIndex >= 0) {
            controller.edtScope.launch {
                controller.rvOverrides.collect { value ->
                    val modified = value.isNotEmpty()
                    tabs.setTitleAt(
                        rvOverridesTabIndex,
                        titleWithDot(rvOverridesBaseTitle, modified)
                    )
                }
            }
        }
    }

    private fun titleWithDot(base: String, modified: Boolean): String =
        if (modified) "$base •" else base

    private fun wireRunningState() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                runAction.isEnabled = !running
                cancelAction.isEnabled = running
                resetAction.isEnabled = !running
                parameterPanel.isEnabled = !running
                controlOverridesPanel.isEnabled = !running
                rvOverridesPanel.isEnabled = !running
                if (running) {
                    notifications.show("Simulation started", NotificationSeverity.INFO)
                }
            }
        }
    }

    /**
     * Drive the [statusStrip] from the combined state of
     * `runningFlow`, `lastResult`, and `editedSinceLastSim`.
     *
     * Precedence (top wins):
     *  - running → "Running…"
     *  - lastResult exists, edited since → "Edited / Previous run: …" (stale)
     *  - lastResult exists, clean → "Completed" / "Failed" / etc. with summary
     *  - lastResult absent, edited → "Edited / Not yet simulated"
     *  - lastResult absent, clean → "Defaults / Model defaults loaded"
     *
     * Uses `editedSinceLastSim` rather than `isDirty` because the
     * status strip is reporting "does the editor agree with what was
     * last simulated?", not "does the editor agree with what's on
     * disk?".  Save flips `isDirty` but not `editedSinceLastSim`, so
     * the badge correctly stays "Edited / Previous run: …" after
     * Save (the file matches; the run does not).
     */
    private fun wireStatusStrip() {
        controller.edtScope.launch {
            kotlinx.coroutines.flow.combine(
                controller.runningFlow,
                controller.lastResult,
                controller.editedSinceLastSim
            ) { running, result, edited ->
                Triple(running, result, edited)
            }.collect { (running, result, edited) ->
                when {
                    running -> statusStrip.showRunning()
                    result != null && edited -> statusStrip.showStale(result)
                    result != null -> statusStrip.showResult(result)
                    edited -> statusStrip.showEditedPreRun()
                    else -> statusStrip.showDefaults()
                }
            }
        }
    }

    private fun wireTerminalNotifications() {
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                if (result == null) return@collect
                val hasSnapshot = result is RunResult.Completed || result is RunResult.BatchCompleted
                latestSnapshotResult = if (hasSnapshot) result else null
                when (result) {
                    is RunResult.Completed ->
                        notifications.show("Simulation completed", NotificationSeverity.INFO)
                    is RunResult.Cancelled ->
                        notifications.show("Simulation cancelled: ${result.reason}", NotificationSeverity.WARNING)
                    is RunResult.Failed ->
                        notifications.show("Simulation failed: ${result.error}", NotificationSeverity.ERROR)
                    is RunResult.BatchCompleted ->
                        notifications.show("Batch completed", NotificationSeverity.INFO)
                    else ->
                        notifications.show("Simulation finished: ${result::class.simpleName}", NotificationSeverity.INFO)
                }
                // OUT5 — auto-materialize the configured report formats.
                // Quiet failures: each materialize-or-fail is its own
                // notification.  Skipped when no snapshot or when the
                // analyst has unchecked everything in the
                // "Auto-render after Simulate" section.
                if (hasSnapshot) autoMaterializeReports(result)
            }
        }
    }

    /**
     * After a successful terminal result, materialize every
     * [ksl.app.config.ReportFormat] in `controller.outputConfig.reports`
     * via [StandardReportMaterializer].  Same code path as the
     * on-demand buttons, just driven by the auto-render set rather
     * than a user click.
     */
    private fun autoMaterializeReports(result: RunResult) {
        val formats = controller.outputConfig.value.reports
        if (formats.isEmpty()) return
        for (format in formats) {
            // ReportFormat.name happens to match the labels accepted
            // by handleStandardReport ("HTML", "Markdown", "Text") —
            // see StandardReportFormat.fromButtonLabel.
            materializeStandardReport(result, format.name)
        }
    }

    private fun handleStandardReport(formatLabel: String) {
        val result = latestSnapshotResult ?: run {
            notifications.show(
                "No completed run available — start a run first.",
                NotificationSeverity.WARNING
            )
            return
        }
        materializeStandardReport(result, formatLabel)
    }

    private fun materializeStandardReport(result: RunResult, formatLabel: String) {
        val format = StandardReportFormat.fromButtonLabel(formatLabel) ?: return
        val workspace = controller.appWorkspace
        val runId = runIdOf(result) ?: return
        val reportsDir = WorkspaceLayout.reportsDir(workspace, runId, createIfMissing = true)
        when (val outcome = StandardReportMaterializer.materialize(result, format, reportsDir)) {
            is StandardReportOutcome.Ok -> {
                val opened = when (format) {
                    StandardReportFormat.HTML -> DefaultDesktopOpener.browse(outcome.file.toURI())
                    StandardReportFormat.MARKDOWN,
                    StandardReportFormat.TEXT -> DefaultDesktopOpener.open(outcome.file)
                }
                if (opened) {
                    notifications.show(
                        "Opened ${format.labelForButton} report: ${outcome.file.name}",
                        NotificationSeverity.INFO
                    )
                } else {
                    notifications.show(
                        "${format.labelForButton} report written to ${outcome.file.absolutePath} " +
                            "(could not auto-open; open it from your file manager).",
                        NotificationSeverity.WARNING
                    )
                }
            }
            is StandardReportOutcome.Failed -> {
                notifications.show(outcome.reason, NotificationSeverity.ERROR)
            }
        }
    }

    private fun runIdOf(result: RunResult): String? = when (result) {
        is RunResult.Completed -> result.summary.runId
        is RunResult.BatchCompleted -> result.summary.runId
        else -> null
    }

    /**
     * Single-line widget shown on the right side of the run toolbar.
     * Renders a status badge + one-line summary that updates as the
     * controller transitions through idle → running → terminal states.
     */
    private class RunStatusStrip : JPanel() {

        private val badge: JLabel = JLabel().apply {
            font = font.deriveFont(Font.BOLD)
            isOpaque = true
            border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        }
        private val summary: JLabel = JLabel()

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(badge)
            add(Box.createHorizontalStrut(8))
            add(summary)
            showIdle()
        }

        fun showIdle() {
            setBadge("Idle", BG_IDLE, FG_IDLE)
            summary.text = ""
        }

        /**
         * Initial "freshly loaded" phase — defaults loaded, no edits
         * yet, no run yet.  Distinct from [showIdle] only in label;
         * makes the "you're at the model's defaults" state visible
         * instead of letting it look identical to a generic idle.
         */
        fun showDefaults() {
            setBadge("Defaults", BG_IDLE, FG_IDLE)
            summary.text = "Model defaults loaded — click Simulate to run"
        }

        /**
         * "User has made edits but hasn't simulated yet" phase — the
         * pre-run analog of [showStale].  Same orange palette as the
         * post-run stale state so both edited-but-not-simulated cases
         * read with the same urgency.
         */
        fun showEditedPreRun() {
            setBadge("Edited", BG_WARN, FG_WARN)
            summary.text = "Not yet simulated — click Simulate to run"
        }

        fun showRunning() {
            setBadge("Running…", BG_RUN, FG_RUN)
            summary.text = ""
        }

        fun showResult(result: RunResult) {
            when (result) {
                is RunResult.Completed -> {
                    setBadge("Completed", BG_OK, FG_OK)
                    summary.text =
                        "${result.summary.completedReplications} / " +
                            "${result.summary.requestedReplications} replications" +
                            "  ·  ${formatDuration(result.summary.wallClockDuration)}" +
                            "  ·  ${result.summary.endingStatus}"
                }
                is RunResult.BatchCompleted -> {
                    setBadge("Batch completed", BG_OK, FG_OK)
                    summary.text =
                        "${result.summary.completedItems} / ${result.summary.totalItems} items" +
                            (if (result.summary.failedItems > 0) " (${result.summary.failedItems} failed)" else "") +
                            "  ·  ${formatDuration(result.summary.endTime - result.summary.beginTime)}"
                }
                is RunResult.Cancelled -> {
                    setBadge("Cancelled", BG_WARN, FG_WARN)
                    summary.text = result.reason
                }
                is RunResult.Failed -> {
                    setBadge("Failed", BG_ERR, FG_ERR)
                    summary.text = result.error.toString()
                }
                is RunResult.OptimizationCompleted -> {
                    setBadge("Optimization completed", BG_OK, FG_OK)
                    summary.text =
                        "${result.summary.completedItems} / ${result.summary.totalItems} iterations" +
                            "  ·  ${formatDuration(result.summary.endTime - result.summary.beginTime)}"
                }
            }
        }

        /**
         * Stale variant of [showResult]: render the previous run's
         * summary but flip the badge to "Edited" (orange) and prefix
         * the summary with "Previous run:" so the analyst can tell
         * the displayed numbers are no longer current with the
         * in-memory configuration.  Triggered by
         * `SingleAppController.runConfigurationStale` flipping true.
         */
        fun showStale(previousResult: RunResult) {
            // Render the previous result first to populate `summary`,
            // then overwrite the badge and prefix the summary text.
            showResult(previousResult)
            setBadge("Edited", BG_WARN, FG_WARN)
            summary.text = "Previous run: ${summary.text}"
        }

        private fun setBadge(label: String, bg: Color, fg: Color) {
            badge.text = label
            badge.background = bg
            badge.foreground = fg
        }

        private fun formatDuration(d: Duration): String {
            val seconds = d.toDouble(DurationUnit.SECONDS)
            return when {
                seconds < 1.0 -> "%.3f s".format(seconds)
                seconds < 60.0 -> "%.1f s".format(seconds)
                else -> {
                    val totalSec = seconds.toInt()
                    val m = totalSec / 60
                    val s = totalSec % 60
                    "%d m %02d s".format(m, s)
                }
            }
        }

        companion object {
            private val BG_IDLE: Color = Color(0xEE, 0xEE, 0xEE)
            private val FG_IDLE: Color = Color(0x55, 0x55, 0x55)
            private val BG_RUN: Color = Color(0xE3, 0xF2, 0xFD)
            private val FG_RUN: Color = Color(0x0D, 0x47, 0xA1)
            private val BG_OK: Color = Color(0xE8, 0xF5, 0xE9)
            private val FG_OK: Color = Color(0x1B, 0x5E, 0x20)
            private val BG_WARN: Color = Color(0xFF, 0xF3, 0xE0)
            private val FG_WARN: Color = Color(0xE6, 0x5C, 0x00)
            private val BG_ERR: Color = Color(0xFF, 0xEB, 0xEE)
            private val FG_ERR: Color = Color(0xC6, 0x28, 0x28)
        }
    }
}
