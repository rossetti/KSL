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

package ksl.app.swing.bundle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.swing.common.validation.DocumentHealthBanner
import ksl.app.swing.common.validation.WidgetPathRegistry
import ksl.app.validation.FieldError
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.BorderFactory
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
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * Top-level window for the Bundle Workbench. Drives the builders-JAR → bundle-JAR
 * flow over [BundleWorkbenchController] (a thin adapter on `BundleAuthoringSession`).
 * Recomposes the shared `KSLAppSwingCommon` infrastructure used by the other apps:
 * the workspace status bar + set/recent-working-directory actions, and the inline
 * [DocumentHealthBanner] for validation feedback. UI behaviour is verified manually,
 * per the other Swing apps; logic lives in the headless controller/session.
 */
class BundleWorkbenchFrame(
    private val controller: BundleWorkbenchController
) : JFrame(controller.appName) {

    private val openButton = JButton("Open JAR…").apply {
        toolTipText = "Open a builders JAR (ModelBuilderIfc classes), or an assembled bundle JAR to resume editing."
        addActionListener { openJarDialog() }
    }
    private val validateButton = JButton("Validate").apply {
        toolTipText = "Check the current draft: assemble it to a temporary bundle and report problems " +
            "(bundle id, model ids, supported apps, catalog, builder resolvability)."
        isEnabled = false
        addActionListener { runValidate() }
    }
    private val assembleButton = JButton("Assemble bundle JAR…").apply {
        toolTipText = "Write a NEW bundle JAR (manifest + per-model descriptor + catalog). " +
            "The input builders JAR is never modified."
        isEnabled = false
        addActionListener { assembleDialog() }
    }

    private val overview = JTextArea().apply {
        isEditable = false
        lineWrap = false
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }
    private val identityPanel = IdentityPanel(controller)
    private val modelPanel = ModelMetadataPanel(controller)
    private val catalogPanel = CatalogTablePanel(controller)

    private val healthBanner = DocumentHealthBanner(
        bus = controller.healthBus,
        registry = WidgetPathRegistry(),
        scope = controller.scope,
        // No widget registry; "Jump to source" instead navigates to the relevant tab/model.
        onMissingWidget = { jumpToFinding(it) },
    )

    private val statusLabel = JLabel(" ", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    /** Overview (always enabled) is the landing/summary; the editing tabs (1..3) unlock once a JAR is open. */
    private val tabs = JTabbedPane().apply {
        addTab("Overview", JScrollPane(overview))
        addTab("Bundle identity", identityPanel)
        addTab("Models", modelPanel)
        addTab("Catalog", catalogPanel)
    }

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        jMenuBar = buildMenuBar()
        preferredSize = Dimension(980, 720)

        contentPane.layout = BorderLayout()
        contentPane.add(buildNorth(), BorderLayout.NORTH)
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(buildSouth(), BorderLayout.SOUTH)

        wireEnablement()
        wireStatus()
        wireOverview()
        wireTitle()

        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) = handleClose()
        })
    }

    private fun buildNorth(): JComponent {
        val toolbar = JPanel().apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(openButton)
            add(validateButton)
            add(assembleButton)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(toolbar)
            add(healthBanner) // hides itself when there are no errors/warnings
        }
    }

    private fun buildSouth(): JComponent {
        val workspaceBar = WorkspaceStatusBar(
            store = controller.settingsStore,
            scope = controller.scope,
            onSetWorkingDirectory = { setWorkingDirectory() },
        )
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusLabel)
            add(JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(2, 8, 3, 8)
                add(workspaceBar, BorderLayout.CENTER)
            })
        }
    }

    private fun buildMenuBar(): JMenuBar = JMenuBar().apply {
        add(JMenu("File").apply {
            add(JMenuItem("Open JAR…").apply { addActionListener { openJarDialog() } })
            add(JMenuItem(SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this@BundleWorkbenchFrame })))
            add(RecentWorkingDirectoriesMenu(controller.settingsStore, controller.scope))
            addSeparator()
            add(JMenuItem("Validate").apply { addActionListener { runValidate() } })
            add(JMenuItem("Assemble bundle JAR…").apply { addActionListener { assembleDialog() } })
            addSeparator()
            add(JMenuItem("Exit").apply { addActionListener { handleClose() } })
        })
    }

    /** Validates, then flashes the status line so a repeat click is visibly acknowledged. */
    private fun runValidate() {
        val report = controller.validate()
        flashStatus(ok = report?.isClean == true)
    }

    /** Briefly highlights the status line (green = clean, amber = findings) as a transient
     *  "the action ran" cue, since the status text itself may be unchanged between runs. */
    private fun flashStatus(ok: Boolean) {
        val origBackground = statusLabel.background
        val origOpaque = statusLabel.isOpaque
        statusLabel.isOpaque = true
        statusLabel.background = if (ok) java.awt.Color(0xCC, 0xF2, 0xCC) else java.awt.Color(0xFF, 0xE2, 0xB3)
        statusLabel.repaint()
        javax.swing.Timer(450) {
            statusLabel.background = origBackground
            statusLabel.isOpaque = origOpaque
            statusLabel.repaint()
        }.apply { isRepeats = false; start() }
    }

    private fun openJarDialog() {
        val chooser = JFileChooser(controller.ensureAppWorkspace().toFile()).apply {
            dialogTitle = "Open JAR (builders or assembled bundle)"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("JAR files", "jar")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file: File = chooser.selectedFile
        try {
            controller.openJar(file.toPath())
            val errors = controller.discoveryErrors.value
            if (errors.isNotEmpty()) {
                JOptionPane.showMessageDialog(
                    this,
                    "Some builders failed to build and were skipped:\n" + errors.joinToString("\n"),
                    "Discovery warnings", JOptionPane.WARNING_MESSAGE
                )
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Could not open ${file.name}: ${e.message}", "Open failed", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun assembleDialog() {
        val default = controller.defaultOutputPath()
        if (default == null) {
            JOptionPane.showMessageDialog(this, "Open a builders JAR first.", "Nothing to assemble", JOptionPane.WARNING_MESSAGE)
            return
        }
        val appDir = controller.ensureAppWorkspace()
        val chooser = JFileChooser(appDir.toFile()).apply {
            dialogTitle = "Assemble bundle JAR"
            selectedFile = appDir.resolve(default.fileName).toFile()
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        try {
            controller.assemble(chooser.selectedFile.toPath(), force = true)
            controller.settingsStore.addRecentDirectory(chooser.selectedFile.toPath().parent)
            JOptionPane.showMessageDialog(
                this, "Wrote ${chooser.selectedFile.name}", "Bundle assembled", JOptionPane.INFORMATION_MESSAGE
            )
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Assemble failed: ${e.message}", "Assemble failed", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun setWorkingDirectory() {
        SetWorkingDirectoryAction(controller.settingsStore, parentSupplier = { this })
            .actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "setWorkingDirectory"))
    }

    /** Dirty-aware close: prompt to Assemble / Discard / Cancel when the draft is unassembled. */
    private fun handleClose() {
        if (confirmClose()) {
            controller.dispose()
            dispose()
        }
    }

    private fun confirmClose(): Boolean {
        if (!controller.dirty.value) return true
        val options = arrayOf<Any>("Assemble…", "Discard", "Cancel")
        val choice = JOptionPane.showOptionDialog(
            this,
            "You have unassembled changes to the bundle draft.",
            "Unassembled changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null, options, options[2],
        )
        return when (choice) {
            0 -> { assembleDialog(); !controller.dirty.value } // assembled clears dirty; a cancelled assemble keeps it
            1 -> true                                          // discard
            else -> false                                      // cancel / dialog closed
        }
    }

    private fun wireTitle() {
        controller.scope.launch(Dispatchers.Swing) {
            controller.dirty.collect { dirty -> title = controller.appName + if (dirty) " *" else "" }
        }
    }

    private fun wireEnablement() {
        controller.scope.launch(Dispatchers.Swing) {
            controller.currentJar.collect { jar ->
                val open = jar != null
                validateButton.isEnabled = open
                assembleButton.isEnabled = open
                // Guided unlock: the editing tabs (Bundle identity, Models, Catalog)
                // are available only once a builders JAR is loaded.
                for (i in 1 until tabs.tabCount) tabs.setEnabledAt(i, open)
                if (!open) tabs.selectedIndex = 0
            }
        }
    }

    private fun wireStatus() {
        controller.scope.launch(Dispatchers.Swing) {
            controller.status.collect { statusLabel.text = it }
        }
    }

    /** Overview is a bundle-level summary + guided next steps (per-model detail lives on the Models tab). */
    private fun wireOverview() {
        controller.scope.launch(Dispatchers.Swing) { controller.currentJar.collect { renderOverview() } }
        controller.scope.launch(Dispatchers.Swing) { controller.models.collect { renderOverview() } }
    }

    private fun renderOverview() {
        val jar = controller.currentJar.value
        overview.text = if (jar == null) buildString {
            appendLine("KSL Bundle Workbench")
            appendLine()
            appendLine("Open a builders JAR (compiled ModelBuilderIfc classes) to begin:")
            appendLine("  File → Open builders JAR…")
            appendLine()
            appendLine("Then: set the bundle identity, choose each model's supported apps,")
            appendLine("nominate a catalog, Validate, and Assemble a bundle JAR.")
        } else buildString {
            appendLine("Loaded: $jar")
            appendLine("Bundle: ${controller.identity.value?.bundleId ?: "(unset)"}")
            val models = controller.models.value
            appendLine("Models (${models.size}):")
            models.forEach { appendLine("  - ${it.modelId}  <-  ${it.builderClass}") }
            val errors = controller.discoveryErrors.value
            if (errors.isNotEmpty()) {
                appendLine()
                appendLine("Skipped builders (failed to build):")
                errors.forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine("Next: Bundle identity → Models (supported apps) → Catalog → Validate → Assemble.")
        }
    }

    /** Navigate-to-tab fallback for the banner's "Jump to source" (loci are bundle/model-level). */
    private fun jumpToFinding(error: FieldError) {
        val path = error.path
        when {
            "/" !in path -> tabs.selectedIndex = TAB_IDENTITY        // bundleId / bundle-level
            path.contains("catalog", ignoreCase = true) -> { selectModelFrom(path); tabs.selectedIndex = TAB_CATALOG }
            // Other model-level loci (builder, supported apps) land on the Models tab.
            else -> { selectModelFrom(path); tabs.selectedIndex = TAB_MODELS }
        }
    }

    /** A locus is `<bundleId>/<modelId> …`; select that model if present. */
    private fun selectModelFrom(path: String) {
        val modelId = path.substringAfterLast('/', "").substringBefore(' ').trim()
        if (modelId.isNotBlank()) controller.selectModel(modelId)
    }

    private companion object {
        const val TAB_IDENTITY = 1
        const val TAB_MODELS = 2
        const val TAB_CATALOG = 3
    }
}
