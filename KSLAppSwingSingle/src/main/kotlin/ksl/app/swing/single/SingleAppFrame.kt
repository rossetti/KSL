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
import ksl.app.swing.single.defaults.DefaultControlOverridesPanel
import ksl.app.swing.single.defaults.DefaultParameterPanel
import ksl.app.swing.single.defaults.DefaultReportsPanel
import ksl.app.swing.single.defaults.DefaultRVOverridesPanel
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

    private val runAction = object : AbstractAction("Run") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
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
    private val cancelAction = object : AbstractAction("Cancel") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) { controller.cancel() }
    }.apply { isEnabled = false }

    private val parameterPanel = DefaultParameterPanel(controller)
    private val controlOverridesPanel = DefaultControlOverridesPanel(controller)
    private val rvOverridesPanel = DefaultRVOverridesPanel(controller)
    private val reportsPanel = DefaultReportsPanel(
        onStandardReport = { format -> handleStandardReport(format) },
        onAdvanced = {
            notifications.show(
                "Advanced report configuration is not yet wired (N5).",
                NotificationSeverity.WARNING
            )
        }
    )
    private val consolePanel = ConsoleLogPanel(
        eventFlow = controller.eventFlow,
        scope = controller.edtScope,
        hiddenCategories = setOf(ConsoleCategory.ORCHESTRATOR)
    )
    private val consoleDrawer = ConsoleDrawer(console = consolePanel)
    private val statusStrip = RunStatusStrip()

    private val tabs = JTabbedPane()
    private var reportsTabIndex: Int = -1
    private var latestSnapshotResult: RunResult? = null

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

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(banner)
            add(toolbar)
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
        wireTerminalNotifications()
        wireWindowTitle()
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
        val newItem = JMenuItem(object : AbstractAction("New") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleNew() }
        }).apply { accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcutKey) }
        val openItem = JMenuItem(object : AbstractAction("Open Configuration…") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleOpen() }
        }).apply { accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_O, menuShortcutKey) }
        val saveItem = JMenuItem(object : AbstractAction("Save") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleSave() }
        }).apply { accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_S, menuShortcutKey) }
        val saveAsItem = JMenuItem(object : AbstractAction("Save As…") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { handleSaveAs() }
        }).apply {
            accelerator = KeyStroke.getKeyStroke(
                KeyEvent.VK_S, menuShortcutKey or KeyEvent.SHIFT_DOWN_MASK
            )
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
        if (!confirmDiscardIfDirty("Discard unsaved changes and start a new configuration?")) return
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
        val workspace = controller.settingsStore.activeWorkspace()
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
    private fun handleSaveAs() {
        val workspace = controller.settingsStore.activeWorkspace()
        val startDir = WorkspaceLayout.configsDir(workspace, createIfMissing = true)
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
        val runButton = JButton(runAction)
        val cancelButton = JButton(cancelAction)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            add(runButton)
            add(Box.createHorizontalStrut(8))
            add(cancelButton)
            add(Box.createHorizontalStrut(16))
            add(statusStrip)
            add(Box.createHorizontalGlue())
        }
    }

    private fun buildTabs(): JComponent {
        // Run Control tab: parameter panel only.  Console lives in the
        // bottom-of-window drawer below the tab area.
        val scrollableParameters = JScrollPane(parameterPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        tabs.addTab("Run Control", scrollableParameters)
        if (controlOverridesPanel.isVisible) {
            tabs.addTab("Control Overrides", controlOverridesPanel)
        }
        if (rvOverridesPanel.isVisible) {
            tabs.addTab("RV Overrides", rvOverridesPanel)
        }
        reportsTabIndex = tabs.tabCount
        tabs.addTab("Reports", reportsPanel)
        tabs.setEnabledAt(reportsTabIndex, false)
        tabs.setToolTipTextAt(reportsTabIndex, "Run the model to enable reports")
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

    private fun wireRunningState() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                runAction.isEnabled = !running
                cancelAction.isEnabled = running
                parameterPanel.isEnabled = !running
                controlOverridesPanel.isEnabled = !running
                rvOverridesPanel.isEnabled = !running
                if (running) {
                    statusStrip.showRunning()
                    notifications.show("Run started", NotificationSeverity.INFO)
                }
            }
        }
    }

    private fun wireTerminalNotifications() {
        controller.edtScope.launch {
            controller.lastResult.collect { result ->
                if (result == null) return@collect
                statusStrip.showResult(result)
                val hasSnapshot = result is RunResult.Completed || result is RunResult.BatchCompleted
                if (reportsTabIndex >= 0) {
                    tabs.setEnabledAt(reportsTabIndex, hasSnapshot)
                    tabs.setToolTipTextAt(
                        reportsTabIndex,
                        if (hasSnapshot) null else "Run the model to enable reports"
                    )
                }
                latestSnapshotResult = if (hasSnapshot) result else null
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
            }
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
        val workspace = controller.settingsStore.activeWorkspace()
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
