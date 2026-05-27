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

package ksl.app.swing.simopt

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.swing.common.notification.Notifications
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.common.runcontrol.ConsoleDrawer
import ksl.app.swing.common.runcontrol.ConsoleLogPanel
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import ksl.app.swing.simopt.stepper.Step
import ksl.app.swing.simopt.stepper.StepFooterPanel
import ksl.app.swing.simopt.stepper.StepperPanel
import ksl.app.swing.simopt.steps.AlgorithmStepPanel
import ksl.app.swing.simopt.steps.ConstraintsStepPanel
import ksl.app.swing.simopt.steps.ExecuteStepPanel
import ksl.app.swing.simopt.steps.ModelStepPanel
import ksl.app.swing.simopt.steps.ProblemStepPanel
import ksl.app.swing.simopt.steps.ResultsStepPanel
import ksl.app.swing.simopt.steps.RunSetupStepPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Top-level `JFrame` for the SimOpt App.
 *
 * Layout (top → bottom):
 *
 *  1. **Menu bar** — File / Workspace / Theme / Help.  File menu wires
 *     New / Open / Save / Save As; Workspace wires Set Working
 *     Directory + Recent.
 *  2. **Toolbar** — single text field for the analysis name.  No
 *     database widgets (the optimization runtime doesn't write to a
 *     KSLDatabase).
 *  3. **Step rail** — clickable horizontal pills, one per
 *     [Step].  Locked / unlocked / active / complete states.
 *  4. **Active step body** — `CardLayout` swapping among the six step
 *     panels keyed off [SimoptAppController.activeStep].
 *  5. **Step footer** — Back / Edited-Saved badge / Next.
 *  6. **Console drawer** — collapsible event log fed by
 *     [SimoptAppController.eventFlow].
 *  7. **Workspace status bar** — current workspace path.
 *
 *  Closing the window closes the controller (cancels its scope).
 */
class SimoptAppFrame(
    private val controller: SimoptAppController
) : JFrame(controller.appName) {

    private val notifications: Notifications = Notifications(rootPane.layeredPane)

    // ── Toolbar widgets ────────────────────────────────────────────────────

    private val analysisNameField = JTextField(controller.output.value.analysisName, 24).apply {
        toolTipText = "Identity for this analysis.  Names the output subdirectory " +
            "<workspace>/output/<analysisName>/ where every run artifact lands."
    }

    // ── Console + drawer ──────────────────────────────────────────────────

    private val consolePanel = ConsoleLogPanel(
        eventFlow = controller.eventFlow,
        scope = controller.edtScope
    )
    private val consoleDrawer = ConsoleDrawer(console = consolePanel, showCaptureToggle = false)

    // ── Step panels keyed by Step (CardLayout body) ───────────────────────

    private val stepBodyLayout = CardLayout()
    private val stepBody = JPanel(stepBodyLayout)
    private val stepPanels: Map<Step, JPanel> = mapOf(
        Step.MODEL to ModelStepPanel(controller),
        Step.PROBLEM to ProblemStepPanel(controller),
        Step.CONSTRAINTS to ConstraintsStepPanel(controller) { msg, sev ->
            notifications.show(msg, sev)
        },
        Step.ALGORITHM to AlgorithmStepPanel(controller),
        Step.RUN_SETUP to RunSetupStepPanel(controller) { msg, sev ->
            notifications.show(msg, sev)
        },
        Step.EXECUTE to ExecuteStepPanel(controller),
        Step.RESULTS to ResultsStepPanel(controller)
    )

    // ── File-menu items ───────────────────────────────────────────────────

    private lateinit var saveItem: JMenuItem

    /** Tracks "user has acknowledged the analysis-name-vs-filename
     *  mismatch for this currently-bound file" so the prompt fires
     *  at most once per file binding.  Reset whenever the binding
     *  changes (load, save-as, new doc). */
    @Volatile private var mismatchAckedForBinding: Path? = null

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(1000, 720)

        jMenuBar = buildMenuBar()
        contentPane.layout = BorderLayout()

        // ── Top stack: toolbar + step rail ─────────────────────────────────
        val topStack = JPanel().apply {
            layout = java.awt.GridBagLayout()
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                weightx = 1.0
                anchor = java.awt.GridBagConstraints.NORTHWEST
                gridx = 0
                gridy = 0
            }
            add(buildToolbar(), gbc)
            gbc.gridy = 1
            add(StepperPanel(controller), gbc)
        }
        contentPane.add(topStack, BorderLayout.NORTH)

        // ── Center: CardLayout body wrapping the six step panels ──────────
        for ((step, panel) in stepPanels) stepBody.add(panel, step.name)
        contentPane.add(stepBody, BorderLayout.CENTER)

        // ── South stack: step footer + console drawer + workspace bar ────
        val southStack = JPanel(BorderLayout()).apply {
            add(StepFooterPanel(controller), BorderLayout.NORTH)
            add(consoleDrawer, BorderLayout.CENTER)
            add(
                WorkspaceStatusBar(controller.settingsStore, controller.edtScope),
                BorderLayout.SOUTH
            )
        }
        contentPane.add(southStack, BorderLayout.SOUTH)

        // ── Live wiring ───────────────────────────────────────────────────
        wireActiveStepToCardLayout()
        wireAnalysisNameField()
        wireDirtyMarkerInTitle()
        wireMismatchAckReset()

        // Window-close → close the controller (cancels coroutine scope).
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                controller.close()
            }
        })

        // Initial card show.
        stepBodyLayout.show(stepBody, controller.activeStep.value.name)
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    private fun buildToolbar(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, java.awt.Color(0xE6, 0xE6, 0xE6)),
            BorderFactory.createEmptyBorder(2, 12, 2, 12)
        )

        analysisNameField.addActionListener {
            controller.setAnalysisName(analysisNameField.text)
        }
        analysisNameField.addFocusListener(object : java.awt.event.FocusListener {
            override fun focusGained(e: java.awt.event.FocusEvent) { /* no-op */ }
            override fun focusLost(e: java.awt.event.FocusEvent) {
                controller.setAnalysisName(analysisNameField.text)
            }
        })
        add(JLabel("Analysis name:"))
        add(Box.createHorizontalStrut(4))
        add(analysisNameField)
        add(Box.createHorizontalGlue())
    }

    private fun wireAnalysisNameField() {
        controller.output.onEach { cfg ->
            if (analysisNameField.text != cfg.analysisName && !analysisNameField.hasFocus()) {
                analysisNameField.text = cfg.analysisName
            }
        }.launchIn(controller.edtScope)
    }

    private fun wireActiveStepToCardLayout() {
        controller.activeStep.onEach { step ->
            stepBodyLayout.show(stepBody, step.name)
        }.launchIn(controller.edtScope)
    }

    private fun wireDirtyMarkerInTitle() {
        controller.isDirty.onEach { dirty ->
            title = if (dirty) "${controller.appName} *" else controller.appName
        }.launchIn(controller.edtScope)
    }

    /** Reset the mismatch-ack when the binding changes — load, save-as
     *  to a new path, new-document.  Without this the user would carry
     *  a stale ack across files. */
    private fun wireMismatchAckReset() {
        controller.currentFile.onEach { newPath ->
            if (newPath != mismatchAckedForBinding) mismatchAckedForBinding = null
        }.launchIn(controller.edtScope)
    }

    // ── Menu bar ──────────────────────────────────────────────────────────

    private fun buildMenuBar(): JMenuBar {
        val menuShortcut = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

        val newItem = JMenuItem(object : AbstractAction("New Optimization") {
            override fun actionPerformed(e: ActionEvent?) = handleNew()
        }).apply {
            accelerator = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, menuShortcut)
        }
        val openItem = JMenuItem(object : AbstractAction("Open Optimization…") {
            override fun actionPerformed(e: ActionEvent?) = handleOpen()
        }).apply {
            accelerator = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, menuShortcut)
            toolTipText = CONFIG_TOOLTIP
        }
        saveItem = JMenuItem(object : AbstractAction(SAVE_BASE_TEXT) {
            override fun actionPerformed(e: ActionEvent?) = handleSave()
        }).apply {
            accelerator = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, menuShortcut)
            toolTipText = CONFIG_TOOLTIP
        }
        val saveAsItem = JMenuItem(object : AbstractAction("Save Optimization As…") {
            override fun actionPerformed(e: ActionEvent?) = handleSaveAs()
        }).apply {
            accelerator = KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_S,
                menuShortcut or java.awt.event.InputEvent.SHIFT_DOWN_MASK
            )
            toolTipText = CONFIG_TOOLTIP
        }

        // Update the Save item text + enablement on dirty / current-file changes.
        controller.isDirty.onEach { dirty ->
            saveItem.text = if (dirty) SAVE_DIRTY_TEXT else SAVE_BASE_TEXT
        }.launchIn(controller.edtScope)

        val setWdAction = SetWorkingDirectoryAction(
            store = controller.settingsStore,
            parentSupplier = { this }
        )
        val recentMenu = RecentWorkingDirectoriesMenu(
            store = controller.settingsStore,
            scope = controller.edtScope
        )

        val loadBundleItem = JMenuItem(object : AbstractAction("Load Bundle JAR…") {
            override fun actionPerformed(e: ActionEvent?) = handleLoadBundleJar()
        })

        return JMenuBar().apply {
            add(JMenu("File").apply {
                add(newItem)
                add(openItem)
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
            add(JMenu("Help").apply {
                add(JMenuItem("About KSL SimOpt").apply {
                    addActionListener {
                        JOptionPane.showMessageDialog(
                            this@SimoptAppFrame,
                            "KSL Simulation-Optimization App\n\n" +
                                "Document editor for OptimizationRunConfiguration.\n\n" +
                                "Reference: https://rossetti.github.io/KSLBook/",
                            "About",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                })
            })
        }
    }

    private fun handleLoadBundleJar() {
        // Bundle JARs are not workspace-local — use the platform default
        // (last-used directory or home), matching the Experiment app.
        val chooser = JFileChooser().apply {
            dialogTitle = "Load Bundle JAR"
            fileFilter = FileNameExtensionFilter("JAR files (*.jar)", "jar")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path = chooser.selectedFile.toPath()
        when (val result = controller.loadBundleJar(path)) {
            is SimoptAppController.LoadBundleResult.Loaded ->
                notifications.show(
                    "Loaded ${result.newBundleIds.size} bundle(s) from ${path.fileName}: " +
                        result.newBundleIds.joinToString(", "),
                    NotificationSeverity.INFO
                )
            SimoptAppController.LoadBundleResult.NoBundles ->
                notifications.show(
                    "No new bundles found in ${path.fileName}.  (Already loaded, or no " +
                        "KSLModelBundle SPI entries.)",
                    NotificationSeverity.WARNING
                )
            is SimoptAppController.LoadBundleResult.Failed ->
                notifications.show(
                    "Could not load ${path.fileName}: ${result.reason}",
                    NotificationSeverity.ERROR
                )
        }
    }

    // ── File-menu handlers ────────────────────────────────────────────────

    private fun handleNew() {
        if (!confirmDiscardIfDirty()) return
        controller.newDocument()
        notifications.show("New optimization started.", NotificationSeverity.INFO)
    }

    private fun handleOpen() {
        if (!confirmDiscardIfDirty()) return
        // Land the chooser in <workspace>/<appNameSanitized>/configs/ —
        // matches the convention used by the Experiment / Scenario /
        // Single apps.  configsDir() creates the directory on first
        // access so the path is always present.
        val startDir = WorkspaceLayout.configsDir(
            controller.appWorkspace, createIfMissing = true
        ).toFile()
        val chooser = JFileChooser(startDir).apply {
            dialogTitle = "Open Optimization"
            fileFilter = FileNameExtensionFilter("TOML files (*.toml)", "toml")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path = chooser.selectedFile.toPath()
        when (val result = controller.loadConfiguration(path)) {
            is SimoptAppController.LoadResult.Success ->
                notifications.show(
                    "Loaded ${path.fileName}.",
                    NotificationSeverity.INFO
                )
            is SimoptAppController.LoadResult.Failed ->
                notifications.show(
                    "Could not load ${path.fileName}: ${result.reason}",
                    NotificationSeverity.ERROR
                )
        }
    }

    private fun handleSave() {
        flushPendingAnalysisName()
        val current = controller.currentFile.value
        if (current == null) {
            handleSaveAs()
            return
        }
        if (controller.currentConfiguration() == null) {
            notifications.show(
                "Cannot save: no model selected.  Pick a model on the Model step before saving.",
                NotificationSeverity.WARNING
            )
            return
        }
        // Mismatch check: warn the user once per binding when the
        // analysis name has drifted from the bound file's stem.  This
        // happens when the user types a new analysis name and clicks
        // Save without realizing the file on disk has a different name.
        // The user can choose to keep saving to the bound file (no rename)
        // or route through Save As to write the analysis-name-derived
        // filename instead.
        val acked = mismatchAckedForBinding == current
        val expectedStem = ksl.app.config.sanitizeAnalysisName(
            controller.output.value.analysisName
        )
        val actualStem = current.fileName.toString().substringBeforeLast('.')
        if (!acked && expectedStem.isNotBlank() &&
            !expectedStem.equals(actualStem, ignoreCase = true)
        ) {
            val proposedName = "$expectedStem.toml"
            val choice = JOptionPane.showOptionDialog(
                this,
                "<html>The analysis name <b>${controller.output.value.analysisName}</b> " +
                    "differs from the bound file <b>${current.fileName}</b>.<br>" +
                    "Save to the bound file anyway, or save as <b>$proposedName</b>?</html>",
                "Analysis name differs from file name",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                arrayOf<Any>("Save to ${current.fileName}", "Save As '$proposedName'…", "Cancel"),
                "Save As '$proposedName'…"
            )
            when (choice) {
                0 -> {
                    // Save to the bound file; remember the user's
                    // decision so we don't re-prompt for this binding.
                    mismatchAckedForBinding = current
                }
                1 -> {
                    handleSaveAs()
                    return
                }
                else -> return
            }
        }
        try {
            controller.saveConfiguration(current)
            notifications.show("Saved ${current.fileName}.", NotificationSeverity.INFO)
        } catch (e: Exception) {
            notifications.show("Save failed: ${e.message}", NotificationSeverity.ERROR)
        }
    }

    private fun handleSaveAs() {
        flushPendingAnalysisName()
        if (controller.currentConfiguration() == null) {
            notifications.show(
                "Cannot save: no model selected.  Pick a model on the Model step before saving.",
                NotificationSeverity.WARNING
            )
            return
        }
        // Save dialogs land in <workspace>/<appNameSanitized>/configs/
        // — the canonical TOML location per the Experiment / Scenario /
        // Single apps' convention.
        val configsDir = WorkspaceLayout.configsDir(
            controller.appWorkspace, createIfMissing = true
        )
        val chooser = JFileChooser(configsDir.toFile()).apply {
            dialogTitle = "Save Optimization As"
            fileFilter = FileNameExtensionFilter("TOML files (*.toml)", "toml")
            selectedFile = configsDir.resolve(defaultSaveAsName()).toFile()
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        var path: Path = chooser.selectedFile.toPath()
        if (path.toString().endsWith(".toml", ignoreCase = true).not()) {
            path = path.resolveSibling("${path.fileName}.toml")
        }
        // Overwrite confirmation — except when the user picked the
        // same file they currently have open (re-saving a loaded
        // document is the normal flow and shouldn't prompt).
        if (java.nio.file.Files.exists(path) && path != controller.currentFile.value) {
            val confirm = JOptionPane.showConfirmDialog(
                this,
                "${path.fileName} already exists.\nReplace it?",
                "Replace File",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (confirm != JOptionPane.YES_OPTION) return
        }
        try {
            controller.saveConfiguration(path)
            notifications.show("Saved ${path.fileName}.", NotificationSeverity.INFO)
        } catch (e: Exception) {
            notifications.show("Save failed: ${e.message}", NotificationSeverity.ERROR)
        }
    }

    /** Push the toolbar text-field value into the controller before
     *  derived calls (file-name defaults, save) read it.  The field
     *  only commits on Enter / focus-lost; this is the standard
     *  flush-pending-value pattern (mirrors `ExperimentAppFrame.E7.5`). */
    private fun flushPendingAnalysisName() {
        controller.setAnalysisName(analysisNameField.text)
    }

    private fun defaultSaveAsName(): String {
        val analysis = controller.output.value.analysisName
        val stem = when {
            analysis.isNotBlank() && analysis != "Untitled" -> analysis
            controller.currentFile.value != null ->
                controller.currentFile.value!!.fileName.toString().substringBeforeLast('.')
            else -> controller.appNameSanitized
        }
        return "$stem.toml"
    }

    private fun confirmDiscardIfDirty(): Boolean {
        if (!controller.isDirty.value) return true
        val choice = JOptionPane.showConfirmDialog(
            this,
            "You have unsaved changes.  Discard them?",
            "Discard Changes?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return choice == JOptionPane.YES_OPTION
    }

    private companion object {
        const val SAVE_BASE_TEXT: String = "Save Optimization"
        const val SAVE_DIRTY_TEXT: String = "Save Optimization *"
        const val CONFIG_TOOLTIP: String =
            "Save / open the optimization document (output, model, problem, " +
                "solver, evaluation, tracking) as a TOML file under " +
                "<workspace>/configs/.  In-progress drafts (no problem / no " +
                "solver) are valid save targets."
    }
}
