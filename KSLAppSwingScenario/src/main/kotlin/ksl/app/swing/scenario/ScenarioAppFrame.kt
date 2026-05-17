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
import ksl.app.settings.WorkspaceLayout
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.common.notification.Notifications
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
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
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
        contentPane.add(buildPlaceholder(), BorderLayout.CENTER)

        wireWindowTitle()
        wireDirtyIndicators()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                controller.close()
            }
        })
    }

    private fun buildPlaceholder(): JPanel {
        val label = JLabel(
            "Scenarios tab lands in Phase E.",
            SwingConstants.CENTER
        )
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(40, 40, 40, 40)
            add(label, BorderLayout.CENTER)
        }
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
}
