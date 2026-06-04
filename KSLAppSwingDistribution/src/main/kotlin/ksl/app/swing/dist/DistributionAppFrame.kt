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

package ksl.app.swing.dist

import kotlinx.coroutines.launch
import ksl.app.dist.config.DistributionKind
import ksl.app.swing.common.appearance.ThemeMenu
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import ksl.app.swing.dist.panel.DataPanel
import ksl.app.validation.ValidationResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JRadioButton
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * Top-level window. Hosts a persistent header (analysis name, distribution
 * kind, Fit/Cancel, run mode + progress), the five-stage workflow as a
 * [JTabbedPane], the menu bar, and a validation/status bar.
 *
 * The view is a thin binder: it renders [DistributionAppController] state
 * flows into widgets and pushes edits back through the controller's mutators.
 * The `updating` guard prevents a state→widget render from echoing back as a
 * widget→mutator edit.
 *
 * This step builds the shell with placeholder tabs; each tab's contents and
 * the Fit/Cancel run wiring arrive in subsequent steps.
 */
class DistributionAppFrame(private val controller: DistributionAppController) : JFrame() {

    private val okColor = Color(0x2E, 0x7D, 0x32)
    private val errColor = Color(0xC6, 0x28, 0x28)

    private var updating = false

    private val analysisNameField = JTextField(18)
    private val kindGroup = ButtonGroup()
    private val continuousButton = JRadioButton("Continuous", true)
    private val discreteButton = JRadioButton("Discrete")
    private val fitButton = JButton("▶ Fit")
    private val cancelButton = JButton("■ Cancel")
    private val modeLabel = JLabel()
    private val progressBar = JProgressBar()

    private val tabs = JTabbedPane()

    private val healthLabel = JLabel()
    private val fileLabel = JLabel()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(720, 520)
        jMenuBar = buildMenuBar()
        layout = BorderLayout()
        add(buildHeader(), BorderLayout.NORTH)
        add(buildTabs(), BorderLayout.CENTER)
        add(buildBottom(), BorderLayout.SOUTH)
        wireListeners()
        bindState()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                controller.dispose()
            }
        })
    }

    // --- construction --------------------------------------------------------

    private fun buildMenuBar(): JMenuBar {
        val bar = JMenuBar()

        val file = JMenu("File")
        file.add(JMenuItem("New").apply { addActionListener { controller.newDocument() } })
        file.add(JMenuItem("Open…").apply {
            isEnabled = false
            toolTipText = "Available once configuration persistence lands (step 7)"
        })
        file.add(JMenuItem("Save").apply {
            isEnabled = false
            toolTipText = "Available once configuration persistence lands (step 7)"
        })
        file.add(JMenuItem("Save As…").apply {
            isEnabled = false
            toolTipText = "Available once configuration persistence lands (step 7)"
        })
        file.addSeparator()
        file.add(JMenuItem(SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })))
        file.add(RecentWorkingDirectoriesMenu(controller.settingsStore, controller.edtScope))
        file.addSeparator()
        file.add(JMenuItem("Exit").apply {
            addActionListener {
                controller.dispose()
                dispose()
                exitProcess(0)
            }
        })
        bar.add(file)

        bar.add(ThemeMenu.build(controller.edtScope, "Appearance"))

        val help = JMenu("Help")
        help.add(JMenuItem("About").apply { addActionListener { showAbout() } })
        bar.add(help)

        return bar
    }

    private fun buildHeader(): JComponent {
        kindGroup.add(continuousButton)
        kindGroup.add(discreteButton)
        analysisNameField.toolTipText =
            "Names this analysis; used as the window title, the report heading, and the default save file name."

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Analysis name:"))
            add(analysisNameField)
            add(Box.createHorizontalStrut(16))
            add(JLabel("Distribution:"))
            add(continuousButton)
            add(discreteButton)
        }

        progressBar.isVisible = false
        progressBar.preferredSize = Dimension(180, 18)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(fitButton)
            add(cancelButton)
            add(Box.createHorizontalStrut(16))
            add(modeLabel)
            add(Box.createHorizontalStrut(16))
            add(progressBar)
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(row1)
            add(row2)
        }

        return JPanel(BorderLayout()).apply {
            add(stack, BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }
    }

    private fun buildTabs(): JComponent {
        tabs.preferredSize = Dimension(940, 560)
        tabs.addTab("Data", DataPanel(controller))
        tabs.addTab("Estimators", placeholder("Estimator selection — arrives in step 4"))
        tabs.addTab("Scoring", placeholder("Scoring models — arrives in step 5"))
        tabs.addTab("Reports", placeholder("Fit reports — arrives in step 6"))
        tabs.addTab("Bootstrap", placeholder("Bootstrap diagnostics — arrives in step 8"))
        return tabs
    }

    private fun placeholder(text: String): JComponent = JPanel(GridBagLayout()).apply {
        val label = JLabel(text)
        label.foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        add(label)
    }

    private fun buildBottom(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(buildStatusBar())
        add(buildWorkspaceBar())
    }

    private fun buildStatusBar(): JComponent {
        val separator = UIManager.getColor("Separator.foreground") ?: Color.LIGHT_GRAY
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, separator),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)
            )
            add(healthLabel, BorderLayout.WEST)
            add(fileLabel, BorderLayout.EAST)
        }
    }

    private fun buildWorkspaceBar(): JComponent {
        val workspaceBar = WorkspaceStatusBar(
            store = controller.settingsStore,
            scope = controller.edtScope,
            onSetWorkingDirectory = { setWorkingDirectory() }
        )
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(2, 8, 3, 8)
            add(workspaceBar, BorderLayout.CENTER)
        }
    }

    private fun setWorkingDirectory() {
        SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
            .actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "setWorkingDirectory"))
    }

    // --- wiring --------------------------------------------------------------

    private fun wireListeners() {
        analysisNameField.addActionListener { pushName() }
        analysisNameField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = pushName()
        })
        continuousButton.addActionListener {
            if (!updating) controller.setKind(DistributionKind.CONTINUOUS)
        }
        discreteButton.addActionListener {
            if (!updating) controller.setKind(DistributionKind.DISCRETE)
        }
        // Fit/Cancel action listeners are wired with the run logic in step 6.
    }

    private fun bindState() {
        val scope = controller.edtScope
        scope.launch {
            controller.analysisName.collect { name ->
                withUpdating {
                    if (!analysisNameField.hasFocus() && analysisNameField.text != name) {
                        analysisNameField.text = name
                    }
                }
                updateTitle()
            }
        }
        scope.launch {
            controller.config.collect { cfg ->
                withUpdating {
                    when (cfg.kind) {
                        DistributionKind.CONTINUOUS -> continuousButton.isSelected = true
                        DistributionKind.DISCRETE -> discreteButton.isSelected = true
                    }
                }
            }
        }
        scope.launch {
            controller.validation.collect { renderHealth(it); refreshFitEnabled() }
        }
        scope.launch {
            controller.mode.collect { renderMode(it); refreshFitEnabled() }
        }
        scope.launch {
            controller.runState.collect { renderRunState(it); refreshFitEnabled() }
        }
        scope.launch {
            controller.currentFile.collect { updateTitle(); renderFile() }
        }
        scope.launch {
            controller.isDirty.collect { updateTitle(); renderFile() }
        }

        // Initial synchronous render so nothing flashes empty before collection.
        renderHealth(controller.validation.value)
        renderMode(controller.mode.value)
        renderRunState(controller.runState.value)
        renderFile()
        updateTitle()
        refreshFitEnabled()
    }

    private fun pushName() {
        if (updating) return
        controller.setAnalysisName(analysisNameField.text)
    }

    // --- rendering -----------------------------------------------------------

    private fun renderHealth(result: ValidationResult) {
        if (result.isValid) {
            healthLabel.text = "✓ configuration valid"
            healthLabel.foreground = okColor
        } else {
            val first = result.errors.firstOrNull()?.let { " — ${it.message}" } ?: ""
            healthLabel.text = "⚠ ${result.errors.size} error(s)$first"
            healthLabel.foreground = errColor
        }
    }

    private fun renderMode(mode: RunMode) {
        modeLabel.text = when (mode) {
            RunMode.NoData -> "no data loaded"
            RunMode.Single -> "mode: single fit"
            is RunMode.Batch -> "mode: batch — ${mode.count} datasets"
        }
    }

    private fun renderRunState(state: RunState) {
        when (state) {
            RunState.Idle -> {
                progressBar.isVisible = false
                progressBar.isIndeterminate = false
                progressBar.isStringPainted = false
            }
            is RunState.Running -> {
                progressBar.isVisible = true
                progressBar.isStringPainted = true
                progressBar.string = state.label
                if (state.total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.maximum = state.total
                    progressBar.value = state.current
                } else {
                    progressBar.isIndeterminate = true
                }
            }
        }
    }

    private fun renderFile() {
        val name = controller.currentFile.value?.fileName?.toString() ?: "untitled"
        val dirty = if (controller.isDirty.value) "  ●" else ""
        fileLabel.text = "file: $name$dirty"
    }

    private fun refreshFitEnabled() {
        val idle = controller.runState.value is RunState.Idle
        fitButton.isEnabled = idle && controller.validation.value.isValid &&
            controller.mode.value != RunMode.NoData
        cancelButton.isEnabled = !idle
    }

    private fun updateTitle() {
        title = composeTitle(controller.currentFile.value, controller.isDirty.value)
    }

    private fun composeTitle(file: Path?, dirty: Boolean): String {
        val name = file?.fileName?.toString() ?: controller.analysisName.value.ifBlank { "untitled" }
        return "${controller.appName} — $name${if (dirty) " *" else ""}"
    }

    private fun showAbout() {
        JOptionPane.showMessageDialog(
            this,
            "${controller.appName}\n\nA front-end for the KSL distribution-fitting engine.",
            "About",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    private inline fun withUpdating(block: () -> Unit) {
        val prev = updating
        updating = true
        try {
            block()
        } finally {
            updating = prev
        }
    }
}
