/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ksl.app.editor.BundleLibraryController
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.appearance.ThemeMenu
import ksl.app.notification.NotificationSeverity
import ksl.app.swing.common.bundle.BundleJarChooser
import ksl.app.swing.common.bundle.BundleLoadNotices
import ksl.app.swing.common.bundle.BundleModelPickerDialog
import ksl.app.swing.common.editor.ControlOverridesPanel
import ksl.app.swing.common.editor.ParameterPanel
import ksl.app.swing.common.editor.RVOverridesPanel
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
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
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The animation authoring window (9C.2): the thin Swing shell over an [AnimationAppController]. It owns
 * the menu bar (a config document + a separate layout document + theme), a four-tab pane
 * (**Capture** · **Run** · **Layout** · **Replay**), and a title bound to both documents' file/dirty
 * state. Construction is separate from visibility — `init` assembles the frame; the caller calls
 * `isVisible = true` (see [KSLAnimationApp]).
 *
 * Tab status (9C.2): **Run** reuses the shared `ConfigurationEditorState` editor panels and **Replay**
 * embeds [ReplayPanel]; **Capture** and **Layout** are placeholders filled by 9D and 9E.
 */
class AnimationAppFrame(private val controller: AnimationAppController) : JFrame(controller.appName) {

    private val parameterPanel = ParameterPanel(controller)
    private val capturePanel = CapturePanel(controller)
    private val layoutPanel = LayoutPanel(controller)
    private val replayPanel = ReplayPanel(controller)
    private val tabs = JTabbedPane()
    private val simulateButton = JButton("Simulate").apply {
        toolTipText = "Run the model and write an animation trace (.atf), then offer to open it in Replay"
    }
    private val cancelButton = JButton("Cancel").apply { toolTipText = "Cancel the in-progress run" }
    private val setWorkingDirAction =
        SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
    private val guidanceLabel = JLabel()

    /**
     * The bundle library used by Bundles ▸ Load JAR / File ▸ Open Model (10.2). In bundle mode it is the
     * controller's own library; in builder mode a library is created lazily and seeded from the classpath and
     * the user bundles directory, so a developer-launched app can still load a JAR and switch models.
     */
    private val bundleLibrary: BundleLibraryController by lazy {
        controller.bundleLibrary ?: BundleLibraryController().also {
            val activeWorkspace = UserSettingsStore().activeWorkspace()
            // Use the app's working folder (APP_FOLDER = "KSLAnimation"), not the sanitized display appName — the
            // display name resolves to a different folder ("KSL_Animation_App"), so discovery missed the app's own
            // bundles dir (see AnimationApp.resolveController).
            val appWorkspace = AppWorkspacePaths.appWorkspaceDir(activeWorkspace, AnimationAppController.APP_FOLDER)
            it.discoverFromDirectories(*WorkspaceLayout.appBundleDirs(appWorkspace, activeWorkspace))
        }
    }

    init {
        jMenuBar = buildMenuBar()
        contentPane.layout = BorderLayout()
        contentPane.add(JPanel(BorderLayout()).apply {
            add(buildModelBar(), BorderLayout.NORTH)      // current bundle + model selector (item 3)
            add(buildGuidanceBanner(), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        contentPane.add(buildTabs(), BorderLayout.CENTER)
        contentPane.add(
            WorkspaceStatusBar(
                controller.settingsStore, controller.edtScope,
                onSetWorkingDirectory = { setWorkingDirAction.actionPerformed(null) }
            ),
            BorderLayout.SOUTH
        )
        bindTitle()
        bindRun()
        bindWorkflow()
        preferredSize = Dimension(1100, 760)
        defaultCloseOperation = DISPOSE_ON_CLOSE
        // Closing the window closes the controller: cancels any in-flight run, closes the
        // session, and closes the bundle classloaders this controller owns.  A model switch
        // hands ownership to the successor first (see reopenWith), so only the last window
        // standing actually closes the library.
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) {
                runCatching { controller.close() }
            }
        })
        pack()
        setLocationRelativeTo(null)
    }

    // ── Tabs ────────────────────────────────────────────────────────────────

    private fun buildTabs(): JTabbedPane {
        tabs.addTab("Capture", capturePanel)
        tabs.addTab("Run", buildRunTab())
        tabs.addTab("Layout", layoutPanel)
        tabs.addTab("Replay", replayPanel)
        // Rescan the trace/layout folders whenever the user switches to Replay, so freshly produced or
        // saved files appear without a manual rescan.
        tabs.addChangeListener { if (tabs.selectedComponent === replayPanel) replayPanel.rescan() }
        return tabs
    }

    // ── Guided workflow (9F.5): a "next step" banner + per-tab progress check-marks ──

    /**
     * The current bundle + a model dropdown to switch to another model in that bundle without the Bundles menu
     * (item 3). In embedded/builder mode (no bundle) it just names the model. Switching swaps the controller
     * via [reopenWith] (after a dirty-state confirm).
     */
    private fun buildModelBar(): JComponent = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)).apply {
        val lib = controller.bundleLibrary
        // No-model startup state (bundle mode, nothing picked yet): prompt the user to open a model, with a
        // shortcut button alongside the Bundles menu. Picking one reopens the frame on a model-backed controller.
        if (!controller.hasModel) {
            add(JLabel("No model loaded."))
            add(JButton("Open Model…").apply {
                toolTipText = "Choose a model from a loaded bundle (or load a JAR first)"
                addActionListener { handleOpenModel() }
            })
            return@apply
        }
        val bId = controller.bundleId
        val bundle = if (lib != null && bId != null) lib.findBundle(bId)?.bundle else null
        if (bundle == null || lib == null) {
            add(JLabel("Model: ${controller.appName}"))
            return@apply
        }
        add(JLabel("Bundle: ${bundle.displayName}    Model:"))
        val models = bundle.models
        add(JComboBox(models.map { it.modelId }.toTypedArray()).apply {
            selectedItem = controller.modelId
            toolTipText = "Switch to another model in this bundle"
            renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean): java.awt.Component {
                    val c = super.getListCellRendererComponent(list, value, index, sel, focus)
                    (c as? JLabel)?.text = models.firstOrNull { it.modelId == value }?.displayName ?: value?.toString()
                    return c
                }
            }
            addActionListener {
                val sel = selectedItem as? String ?: return@addActionListener
                if (sel == controller.modelId) return@addActionListener
                if (!confirmDiscardIfDirty()) { selectedItem = controller.modelId; return@addActionListener }
                runCatching { AnimationAppController.fromBundle(controller.appName, lib, bundle.bundleId, sel) }
                    .onSuccess { reopenWith(it) }
                    .onFailure { showError("Failed to open model: ${it.message}"); selectedItem = controller.modelId }
            }
        })
    }

    private fun buildGuidanceBanner(): JComponent = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)).apply {
        guidanceLabel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        add(guidanceLabel)
    }

    /** Recompute the workflow status whenever a relevant document changes, and reflect it in the UI. */
    private fun bindWorkflow() {
        controller.edtScope.launch {
            combine(controller.lastTraceFile, controller.layout, controller.captureSpec) { _, _, _ -> }
                .collect { refreshWorkflow() }
        }
    }

    private fun refreshWorkflow() {
        if (!controller.hasModel) {
            guidanceLabel.text = "Next: open a model — Bundles ▸ Open Model… (or Load JAR… to add one first)"
            return
        }
        val status = controller.workflowStatus()
        guidanceLabel.text = "Next: ${status.nextStep}"
        val states = listOf(status.capture, status.run, status.layout, status.replay)
        listOf("Capture", "Run", "Layout", "Replay").forEachIndexed { i, name ->
            tabs.setTitleAt(i, if (states[i] == StageState.DONE) "$name ✓" else name)
        }
    }

    // ── Run action ──────────────────────────────────────────────────────────

    /** Simulate/Cancel toolbar bound to the controller's run lifecycle. */
    private fun buildRunToolbar(): JComponent = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT)).apply {
        simulateButton.addActionListener { controller.submit() }
        cancelButton.addActionListener { controller.cancel() }
        cancelButton.isEnabled = false
        add(simulateButton)
        add(cancelButton)
        // Opt-in agent debugging/teaching overlays (G11/G12); off by default so a normal run pays no capture cost.
        add(javax.swing.JCheckBox("Capture flow field").apply {
            toolTipText = "Record the flow-field gradient (for agent models that expose one) — a teaching overlay"
            addActionListener { controller.setCaptureFlowField(isSelected) }
        })
        add(javax.swing.JCheckBox("Capture paths").apply {
            toolTipText = "Record agents' planned routes (for models that report them) — a teaching overlay"
            addActionListener { controller.setCapturePlannedPaths(isSelected) }
        })
        add(javax.swing.JCheckBox("Capture velocity").apply {
            toolTipText = "Record per-agent velocity vectors (for models with linked dynamics) — sampled, ~5/sec"
            addActionListener { controller.setCaptureVelocities(isSelected) }
        })
        add(javax.swing.JCheckBox("Capture force").apply {
            toolTipText = "Record per-agent net steering force vectors (for models with linked dynamics) — sampled, ~5/sec"
            addActionListener { controller.setCaptureForces(isSelected) }
        })
        add(javax.swing.JCheckBox("Capture pulses").apply {
            toolTipText = "Record transient event highlights, e.g. completed deliveries (for models that report them)"
            addActionListener { controller.setCaptureMarkerPulses(isSelected) }
        })
    }

    /**
     * Wires the toolbar to [AnimationAppController.runningFlow] (enable/disable). A finished run does **not**
     * switch tabs or auto-load a layout into Replay — the Replay tab rescans its trace/layout folders on focus,
     * so the new trace appears there and the user pairs + Loads it explicitly (and gets the auto-derived layout
     * only via "Auto-Layout from Model" on the Layout tab, or Quick view in Replay, if they want it).
     */
    private fun bindRun() {
        controller.edtScope.launch {
            controller.runningFlow.collect { running ->
                simulateButton.isEnabled = !running && controller.hasModel // nothing to run with no model selected
                cancelButton.isEnabled = running
            }
        }
        // Announce run completion (G4): a finished run writes a .atf trace but used to do so silently. Surface
        // the produced file and offer to open it in Replay. The StateFlow replays null at startup (ignored).
        controller.edtScope.launch {
            controller.lastResult.collect { result -> if (result != null) announceRunResult(result) }
        }
    }

    /** Post-run feedback: name the produced trace and offer to jump to Replay, or report a failure (G4). */
    private fun announceRunResult(result: RunResult) {
        when (result) {
            is RunResult.Completed -> {
                val trace = controller.lastTraceFile.value
                if (trace == null) {
                    JOptionPane.showMessageDialog(this, "Simulation complete.", "Run complete", JOptionPane.INFORMATION_MESSAGE)
                } else {
                    val choice = JOptionPane.showConfirmDialog(
                        this,
                        "Simulation complete.\n\nAnimation trace written to:\n$trace\n\nOpen it in the Replay tab now?",
                        "Run complete", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE
                    )
                    // Selecting the Replay tab fires the change listener, which rescans its trace/layout folders.
                    if (choice == JOptionPane.YES_OPTION) tabs.selectedComponent = replayPanel
                }
            }
            is RunResult.Failed -> JOptionPane.showMessageDialog(
                this, "Simulation failed:\n\n${result.error}", "Run failed", JOptionPane.ERROR_MESSAGE
            )
            // Cancellation is user-initiated, and batch/optimization results aren't produced by this app's
            // single-run submit() — so neither warrants a dialog here.
            is RunResult.Cancelled -> {}
            else -> {}
        }
    }

    /**
     * Run tab: the parameter editor (plus override panels when the model exposes controls / RVs), with the
     * Simulate/Cancel action bar at the top — configuring and executing a run live together on this tab.
     */
    private fun buildRunTab(): JComponent {
        val cs = controller.controlsSnapshot
        val hasControls = cs.numericControls.isNotEmpty() || cs.stringControls.isNotEmpty() || cs.jsonControls.isNotEmpty()
        val hasRVs = controller.rvSnapshot.isNotEmpty()
        val editors: JComponent = if (!hasControls && !hasRVs) parameterPanel else JTabbedPane().apply {
            addTab("Parameters", parameterPanel)
            if (hasControls) addTab("Controls", ControlOverridesPanel(controller))
            if (hasRVs) addTab("Random Variables", RVOverridesPanel(controller))
        }
        return JPanel(BorderLayout()).apply {
            add(buildRunToolbar(), BorderLayout.NORTH)
            add(editors, BorderLayout.CENTER)
        }
    }

    // ── Menu bar ──────────────────────────────────────────────────────────────

    private fun buildMenuBar(): JMenuBar = JMenuBar().apply {
        add(JMenu("File").apply {
            add(JMenuItem("New").apply { addActionListener { controller.resetConfiguration() } })
            add(JMenuItem("Open…").apply { addActionListener { handleOpenConfig() } })
            add(JMenuItem("Open Model…").apply { addActionListener { handleOpenModel() } })
            addSeparator()
            add(JMenuItem("Save").apply { addActionListener { handleSaveConfig() } })
            add(JMenuItem("Save As…").apply { addActionListener { handleSaveConfigAs() } })
            addSeparator()
            add(JMenuItem(setWorkingDirAction))
            add(RecentWorkingDirectoriesMenu(controller.settingsStore, controller.edtScope))
            addSeparator()
            add(JMenuItem("Exit").apply { addActionListener { dispose() } })
        })
        add(JMenu("Bundles").apply {
            add(JMenuItem("Load JAR…").apply { addActionListener { handleLoadJar() } })
            add(JMenuItem("Open Model…").apply { addActionListener { handleOpenModel() } })
        })
        add(JMenu("Layout").apply {
            add(JMenuItem("Auto Layout").apply { addActionListener { controller.autoLayout() } })
            add(JMenuItem("Layout from Model").apply { addActionListener { controller.scaffoldLayout() } })
            // Offered only for a model whose bundle ships one, because a layout means nothing except for the
            // model it names. Enabled state is settled when the menu opens rather than at construction: the
            // model can change under a long-lived window.
            val shipped = JMenuItem("Use Shipped Layout").apply {
                toolTipText = "Open the polished layout that ships with this model, as a new unsaved document"
                addActionListener { controller.useShippedLayout() }
            }
            add(shipped)
            addMenuListener(object : javax.swing.event.MenuListener {
                override fun menuSelected(e: javax.swing.event.MenuEvent?) {
                    shipped.isEnabled = controller.shippedLayout() != null
                }
                override fun menuDeselected(e: javax.swing.event.MenuEvent?) {}
                override fun menuCanceled(e: javax.swing.event.MenuEvent?) {}
            })
            add(JMenuItem("Open…").apply { addActionListener { handleOpenLayout() } })
            addSeparator()
            add(JMenuItem("Save").apply { addActionListener { handleSaveLayout() } })
            add(JMenuItem("Save As…").apply { addActionListener { handleSaveLayoutAs() } })
        })
        add(JMenu("View").apply { add(ThemeMenu.build(controller.edtScope)) })
    }

    // ── Config document (.toml) ───────────────────────────────────────────────

    private fun handleOpenConfig() {
        val path = chooseFile(open = true, "Open configuration (.toml)", "Configuration (*.toml)", "toml") ?: return
        when (val result = runCatching { controller.openConfiguration(path) }.getOrElse {
            showError("Failed to open: ${it.message}"); return
        }) {
            is AnimationAppController.LoadResult.Loaded ->
                result.warning?.let { JOptionPane.showMessageDialog(this, it, "Loaded with a warning", JOptionPane.WARNING_MESSAGE) }
            is AnimationAppController.LoadResult.Rejected -> showError(result.reason)
            is AnimationAppController.LoadResult.WrongMode -> showError(result.reason)
        }
    }

    private fun handleSaveConfig() {
        val target = controller.currentFile.value ?: return handleSaveConfigAs()
        runCatching { controller.saveConfiguration(target) }.onFailure { showError("Failed to save: ${it.message}") }
    }

    private fun handleSaveConfigAs() {
        val path = chooseFile(open = false, "Save configuration (.toml)", "Configuration (*.toml)", "toml") ?: return
        runCatching { controller.saveConfiguration(path) }.onFailure { showError("Failed to save: ${it.message}") }
    }

    // ── Model / bundle loading (10.2) ─────────────────────────────────────────

    /** Where Load JAR… opens when the user hasn't already loaded one this session: the app's own
     *  bundles folder, else the shared one — the same layers discovery reads. */
    private fun workspaceBundleDir(): java.nio.file.Path? {
        val activeWorkspace = controller.settingsStore.activeWorkspace()
        return WorkspaceLayout.preferredBundleDir(
            appWorkspace = AppWorkspacePaths.appWorkspaceDir(
                activeWorkspace, AnimationAppController.APP_FOLDER
            ),
            sharedWorkspace = activeWorkspace
        )
    }

    /** Bundles ▸ Load JAR…: load a bundle JAR into the library so its models become pickable. */
    private fun handleLoadJar() {
        val path = BundleJarChooser.choose(this, workspaceBundleDir()) ?: return
        val outcome = runCatching { bundleLibrary.loadJar(path) }.getOrElse {
            showError("Could not load ${path.fileName}: ${it.message}"); return
        }
        val notice = BundleLoadNotices.describe(
            outcome, path, followUp = "Use Open Model… to choose one."
        )
        // This frame reports through modal dialogs rather than a notification strip, so
        // the severity picks the dialog: anything above INFO is an attention-getter.
        if (notice.severity == NotificationSeverity.INFO) info(notice.message)
        else showError(notice.message)
    }

    /**
     * File/Bundles ▸ Open Model…: pick a (bundle, model) and re-open the app on it (10.2). Guards unsaved
     * capture/layout edits first. Because the panels read the model's inventory at construction, switching
     * builds a fresh [AnimationAppController] + frame and disposes this one (rather than mutating in place).
     */
    private fun handleOpenModel() {
        if (!confirmDiscardIfDirty()) return
        when (val outcome = BundleModelPickerDialog.show(bundleLibrary, "Open Model", showLoadJarButton = false)) {
            BundleModelPickerDialog.Result.Cancelled -> {}
            is BundleModelPickerDialog.Result.Selected -> {
                val next = runCatching {
                    AnimationAppController.fromBundle(controller.appName, bundleLibrary, outcome.bundleId, outcome.modelId)
                }.getOrElse { showError("Failed to open model: ${it.message}"); return }
                reopenWith(next)
            }
        }
    }

    /** Opens a fresh frame on [next] at the current frame's geometry (so loading a model doesn't reset the
     *  window size/position), then disposes this frame and closes the old controller.
     *
     *  [next] was built against the same bundle library, so ownership of it is handed over before the old
     *  controller closes — otherwise that close would shut the classloaders backing the model just opened. */
    private fun reopenWith(next: AnimationAppController) {
        val replacement = AnimationAppFrame(next)
        // Carry over the live window geometry so the model switch is seamless; this overrides the
        // constructor's pack()/setLocationRelativeTo(null), which is meant for first launch only.
        replacement.bounds = bounds
        replacement.extendedState = extendedState
        replacement.isVisible = true
        // Ownership must transfer BEFORE the dispose that closes this controller.
        controller.releaseBundleLibraryOwnership()
        dispose()   // windowClosed → controller.close()
    }

    /**
     * Returns true if it is safe to discard the current state: either nothing is dirty, or the user confirms.
     * Visible for testing the guard decision without a dialog by overriding [isAnythingDirty].
     */
    private fun confirmDiscardIfDirty(): Boolean {
        if (!isAnythingDirty()) return true
        val choice = JOptionPane.showConfirmDialog(
            this, "Discard unsaved changes and open a different model?", "Unsaved changes",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE
        )
        return choice == JOptionPane.OK_OPTION
    }

    /** True when the config or layout document has unsaved edits. */
    private fun isAnythingDirty(): Boolean = controller.isDirty.value || controller.layoutDirty.value

    private fun info(message: String) =
        JOptionPane.showMessageDialog(this, message, "Bundles", JOptionPane.INFORMATION_MESSAGE)

    // ── Layout document (.lay.toml / .lay.json) ───────────────────────────────

    private fun handleOpenLayout() {
        val path = chooseFile(open = true, "Open layout", "Animation layout (*.toml, *.json)", "toml", "json") ?: return
        runCatching { controller.loadLayout(path) }.onFailure { showError("Failed to open layout: ${it.message}") }
    }

    private fun handleSaveLayout() {
        if (controller.layout.value == null) return showError("There is no layout to save. Scaffold or open one first.")
        val target = controller.layoutFile.value ?: return handleSaveLayoutAs()
        runCatching { controller.saveLayout(target) }.onFailure { showError("Failed to save layout: ${it.message}") }
    }

    private fun handleSaveLayoutAs() {
        if (controller.layout.value == null) return showError("There is no layout to save. Scaffold or open one first.")
        val path = chooseFile(
            open = false, "Save layout (.lay.toml)", "Animation layout (*.toml, *.json)", "toml", "json",
            defaultDir = controller.layoutsDir, defaultName = "${controller.suggestedLayoutBaseName()}.lay.toml"
        ) ?: return
        runCatching { controller.saveLayout(path) }.onFailure { showError("Failed to save layout: ${it.message}") }
    }

    // ── Title binding ─────────────────────────────────────────────────────────

    private fun bindTitle() {
        controller.edtScope.launch {
            combine(
                controller.currentFile, controller.isDirty, controller.layoutFile, controller.layoutDirty
            ) { cfgFile, cfgDirty, layFile, layDirty ->
                val cfg = (cfgFile?.fileName?.toString() ?: "untitled") + if (cfgDirty) " *" else ""
                val lay = layFile?.fileName?.toString()?.let { it + if (layDirty) " *" else "" }
                buildString {
                    append(controller.appName).append(" — ").append(cfg)
                    if (lay != null) append("  ·  ").append(lay)
                }
            }.collect { title = it }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun chooseFile(
        open: Boolean, dialogTitle: String, filterDesc: String, vararg ext: String,
        defaultDir: Path? = null, defaultName: String? = null
    ): Path? {
        val chooser = JFileChooser().apply {
            this.dialogTitle = dialogTitle
            fileFilter = FileNameExtensionFilter(filterDesc, *ext)
            defaultDir?.let { runCatching { java.nio.file.Files.createDirectories(it) }; currentDirectory = it.toFile() }
            defaultName?.let { selectedFile = java.io.File(currentDirectory, it) }
        }
        val ok = if (open) chooser.showOpenDialog(this) else chooser.showSaveDialog(this)
        if (ok != JFileChooser.APPROVE_OPTION) return null
        var file = chooser.selectedFile
        if (!open && !file.name.contains('.')) file = java.io.File(file.parentFile, "${file.name}.${ext.first()}")
        return file.toPath()
    }

    private fun showError(message: String) =
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)

    /** Test-only: the ordered tab titles, for the display-guarded construction smoke test. */
    internal fun tabTitlesForTest(): List<String> = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }
}
