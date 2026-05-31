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

import ksl.app.editor.BundleLibraryController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel

/**
 *  Modal bundle-model picker shown by [KSLSingleApp.launch] when the
 *  developer omits `modelBuilder(...)` from the `kslSingleApp { … }`
 *  DSL.  Drives the controller's bundle-mode launch path.
 *
 *  The dialog presents every `(bundleId, modelId)` pair across every
 *  loaded bundle in a single flat table.  The user can:
 *
 *  - Pick a row and click **Pick** to confirm — returns
 *    [Result.Selected].
 *  - Click **Load JAR…** to extend [BundleLibraryController] with a
 *    user-supplied JAR; the table refreshes on a successful add.
 *  - Click **Cancel** (or close the window) — returns [Result.Cancelled].
 *    The caller is responsible for exiting the JVM in that case.
 *
 *  Because the dialog runs before [SingleAppController] exists, it
 *  has no parent window.  It centers itself on the screen and uses
 *  the application's installed Look-and-Feel (see
 *  [ksl.app.swing.common.appearance.LookAndFeel.install]).
 *
 *  Threading: must be constructed and shown on the Swing EDT.
 *  [show] blocks the EDT until the user dismisses the dialog
 *  (standard modal semantics).
 */
object BundleModelPickerDialog {

    /**
     *  Outcome of the picker.
     *
     *  - [Selected] — the user picked a `(bundleId, modelId)` pair.
     *    The caller resolves the matching [ksl.simulation.ModelBuilderIfc]
     *    via `bundleLibrary.bundleProvider.value!!.builderFor(bundleId, modelId)`.
     *  - [Cancelled] — the user dismissed the dialog without picking.
     */
    sealed class Result {
        data class Selected(val bundleId: String, val modelId: String) : Result()
        object Cancelled : Result()
    }

    /**
     *  Present the picker modally.  Returns the user's choice.
     *  Must be called on the Swing EDT.
     *
     *  @param bundleLibrary the (already classpath-probed) library
     *  the picker reads its rows from.  The dialog calls
     *  [BundleLibraryController.loadJar] on the user's behalf when
     *  they click *Load JAR…*; passing the same library instance to
     *  the eventual [SingleAppController] preserves any JARs loaded
     *  during picker interaction.
     *  @param dialogTitle the modal's title.  Defaults to "Pick a Model".
     */
    fun show(
        bundleLibrary: BundleLibraryController,
        dialogTitle: String = "Pick a Model"
    ): Result {
        val impl = PickerDialog(bundleLibrary, dialogTitle)
        impl.isVisible = true
        return impl.result
    }
}

/**
 *  The actual `JDialog` — non-public so callers go through
 *  [BundleModelPickerDialog.show].  Lives outside the object so its
 *  state-bearing fields can be `private` without leaking through the
 *  object API.
 */
private class PickerDialog(
    private val bundleLibrary: ksl.app.editor.BundleLibraryController,
    title: String
) : JDialog(null as java.awt.Frame?, title, /* modal = */ true) {

    /** Captured choice; read by [BundleModelPickerDialog.show] after the
     *  dialog is dismissed.  Initialized to Cancelled so a window-close
     *  via the [X] button is treated as Cancel. */
    var result: BundleModelPickerDialog.Result = BundleModelPickerDialog.Result.Cancelled
        private set

    private val tableModel = BundleTableModel(buildRows())
    private val table = JTable(tableModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        autoCreateRowSorter = true
        rowHeight = (rowHeight * 1.2).toInt()
    }

    private val pickButton = JButton(object : AbstractAction("Pick") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onPick()
    }).apply { isEnabled = false }

    private val loadJarButton = JButton(object : AbstractAction("Load JAR…") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onLoadJar()
    })

    private val cancelButton = JButton(object : AbstractAction("Cancel") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onCancel()
    })

    /** Banner area for empty-state guidance + Load JAR error messages. */
    private val banner = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(8, 12, 0, 12)
        horizontalAlignment = SwingConstants.LEFT
    }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                // [X] button is equivalent to Cancel.
                onCancel()
            }
        })

        // Selection listener drives Pick-button enablement.
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                pickButton.isEnabled = table.selectedRow >= 0
            }
        }

        // Layout
        contentPane.layout = BorderLayout()
        contentPane.add(banner, BorderLayout.NORTH)
        contentPane.add(JScrollPane(table).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            preferredSize = Dimension(560, 280)
        }, BorderLayout.CENTER)
        contentPane.add(buildButtonRow(), BorderLayout.SOUTH)

        refreshBannerForEmptyState()

        pack()
        setLocationRelativeTo(null)
    }

    private fun buildButtonRow(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(loadJarButton)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(cancelButton)
            add(pickButton)
        }
        add(left, BorderLayout.WEST)
        add(right, BorderLayout.EAST)
    }

    private fun buildRows(): List<BundleRow> =
        bundleLibrary.loadedBundles.value.flatMap { lb ->
            lb.bundle.models.map { m ->
                BundleRow(
                    bundleId = lb.bundle.bundleId,
                    modelId = m.modelId,
                    displayName = m.displayName,
                    description = m.description
                )
            }
        }

    private fun refreshBannerForEmptyState() {
        if (tableModel.rowCount == 0) {
            banner.foreground = Color(0x6B, 0x6B, 0x6B)
            banner.text = "No bundles on the classpath.  Click Load JAR… to load one."
        } else {
            banner.text = " "
        }
    }

    private fun showBannerError(message: String) {
        banner.foreground = Color(0xB0, 0x00, 0x20)
        banner.text = message
    }

    private fun showBannerInfo(message: String) {
        banner.foreground = Color(0x6B, 0x6B, 0x6B)
        banner.text = message
    }

    private fun onPick() {
        val selectedView = table.selectedRow
        if (selectedView < 0) return
        // Convert view row index to model row index in case the user
        // has sorted the table.
        val selectedModelRow = table.convertRowIndexToModel(selectedView)
        val row = tableModel.rowAt(selectedModelRow)
        result = BundleModelPickerDialog.Result.Selected(row.bundleId, row.modelId)
        dispose()
    }

    private fun onCancel() {
        result = BundleModelPickerDialog.Result.Cancelled
        dispose()
    }

    private fun onLoadJar() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Load Bundle JAR"
            fileFilter = FileNameExtensionFilter("Bundle JAR (*.jar)", "jar")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path: Path = chooser.selectedFile?.toPath() ?: return
        when (val outcome = bundleLibrary.loadJar(path)) {
            is BundleLibraryController.LoadBundleResult.Loaded -> {
                tableModel.replaceRows(buildRows())
                refreshBannerForEmptyState()
                if (banner.text == " " || tableModel.rowCount > 0) {
                    showBannerInfo(
                        "Loaded ${outcome.newBundleIds.size} bundle(s): " +
                            outcome.newBundleIds.joinToString(", ")
                    )
                }
            }
            BundleLibraryController.LoadBundleResult.NoBundles -> {
                showBannerError(
                    "$path declares no KSLModelBundle service (or all of its bundles " +
                        "are already loaded)."
                )
            }
            is BundleLibraryController.LoadBundleResult.Failed -> {
                showBannerError("Could not load $path: ${outcome.reason}")
            }
        }
    }

    /** One row in the picker — one (bundle, model) pair. */
    private data class BundleRow(
        val bundleId: String,
        val modelId: String,
        val displayName: String,
        val description: String
    )

    /** Editable-by-replacement table model.  Rows are not user-editable
     *  in-place; [replaceRows] is the only mutator. */
    private class BundleTableModel(initial: List<BundleRow>) : AbstractTableModel() {

        private val columns = arrayOf("Bundle", "Model ID", "Display Name")
        private var rows: List<BundleRow> = initial

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> rows[rowIndex].bundleId
            1 -> rows[rowIndex].modelId
            2 -> rows[rowIndex].displayName
            else -> ""
        }

        fun rowAt(rowIndex: Int): BundleRow = rows[rowIndex]

        fun replaceRows(newRows: List<BundleRow>) {
            rows = newRows
            fireTableDataChanged()
        }
    }
}

