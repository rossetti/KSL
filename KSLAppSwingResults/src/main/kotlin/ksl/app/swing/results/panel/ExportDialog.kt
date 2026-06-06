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

package ksl.app.swing.results.panel

import ksl.app.notification.NotificationSink
import ksl.app.swing.results.ExportFormat
import ksl.app.swing.results.ExportScope
import ksl.app.swing.results.ResultsExportController
import java.awt.BorderLayout
import java.awt.Window
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel

/**
 *  Modal dialog for the full database-export matrix — format × scope ×
 *  selection × output directory — backed by [ResultsExportController].
 *
 *  Only the combinations the underlying `DatabaseIOIfc` methods support
 *  are offered: the scope choices are filtered per format (e.g. Excel
 *  covers tables only; SQL inserts cover tables only), and the
 *  table/view checklist is enabled only for the "Selected …" scopes.
 *
 *  The dialog writes files and reports the outcome through [notifier];
 *  it closes on a successful export.
 */
class ExportDialog(
    owner: Window?,
    private val exportController: ResultsExportController,
    private val notifier: NotificationSink,
    defaultDir: Path
) : JDialog(owner, "Export Database", ModalityType.APPLICATION_MODAL) {

    private val formatCombo = JComboBox(FORMAT_LABELS.keys.toTypedArray())
    private val scopeCombo = JComboBox<String>()
    private val nameList = JList<String>().apply { selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION }
    private val dirField = JTextField(defaultDir.toString(), 28)

    init {
        layout = BorderLayout()
        add(buildForm(), BorderLayout.NORTH)
        add(JScrollPane(nameList).apply { border = BorderFactory.createTitledBorder("Tables / Views (for \"Selected …\")") }, BorderLayout.CENTER)
        add(buildButtons(), BorderLayout.SOUTH)

        formatCombo.addActionListener { onFormatChanged() }
        scopeCombo.addActionListener { onScopeChanged() }
        onFormatChanged()

        pack()
        setLocationRelativeTo(owner)
    }

    private fun buildForm(): JPanel = JPanel().apply {
        border = BorderFactory.createEmptyBorder(10, 10, 6, 10)
        add(JLabel("Format:")); add(formatCombo)
        add(JLabel("Scope:")); add(scopeCombo)
        add(JLabel("Output:")); add(dirField)
        add(JButton("Browse…").apply { addActionListener { browse() } })
    }

    private fun buildButtons(): JPanel = JPanel().apply {
        border = BorderFactory.createEmptyBorder(6, 10, 10, 10)
        add(JButton("Export").apply { addActionListener { doExport() } })
        add(JButton("Cancel").apply { addActionListener { dispose() } })
    }

    private fun selectedFormat(): ExportFormat = FORMAT_LABELS.getValue(formatCombo.selectedItem as String)

    private fun selectedScope(): ExportScope? =
        (scopeCombo.selectedItem as? String)?.let { SCOPE_LABELS[it] }

    private fun onFormatChanged() {
        val validLabels = VALID_SCOPES.getValue(selectedFormat()).map { SCOPE_LABEL_BY_ENUM.getValue(it) }
        scopeCombo.model = DefaultComboBoxModel(validLabels.toTypedArray())
        onScopeChanged()
    }

    private fun onScopeChanged() {
        val scope = selectedScope() ?: return
        val tablesKind = scope == ExportScope.ALL_TABLES || scope == ExportScope.SELECTED_TABLES
        nameList.setListData((if (tablesKind) exportController.tableNames else exportController.viewNames).toTypedArray())
        val selectable = scope == ExportScope.SELECTED_TABLES || scope == ExportScope.SELECTED_VIEWS
        nameList.isEnabled = selectable
        if (!selectable) nameList.clearSelection()
    }

    private fun browse() {
        val chooser = JFileChooser(dirField.text).apply {
            dialogTitle = "Export Output Directory"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dirField.text = chooser.selectedFile.absolutePath
        }
    }

    private fun doExport() {
        val scope = selectedScope() ?: return
        val dir = dirField.text.trim()
        if (dir.isEmpty()) {
            notifier.warn("Choose an output directory.")
            return
        }
        val outcome = exportController.export(
            format = selectedFormat(),
            scope = scope,
            selected = nameList.selectedValuesList,
            dir = Path.of(dir)
        )
        if (outcome.ok) {
            notifier.info(outcome.message)
            dispose()
        } else {
            notifier.error(outcome.message)
        }
    }

    private companion object {
        val FORMAT_LABELS = linkedMapOf(
            "Excel" to ExportFormat.EXCEL,
            "CSV" to ExportFormat.CSV,
            "Markdown" to ExportFormat.MARKDOWN,
            "Text" to ExportFormat.TEXT,
            "SQL inserts" to ExportFormat.SQL
        )
        val SCOPE_LABELS = linkedMapOf(
            "All tables" to ExportScope.ALL_TABLES,
            "All views" to ExportScope.ALL_VIEWS,
            "Selected tables" to ExportScope.SELECTED_TABLES,
            "Selected views" to ExportScope.SELECTED_VIEWS
        )
        val SCOPE_LABEL_BY_ENUM = SCOPE_LABELS.entries.associate { (k, v) -> v to k }
        val VALID_SCOPES = mapOf(
            ExportFormat.EXCEL to listOf(ExportScope.ALL_TABLES, ExportScope.SELECTED_TABLES),
            ExportFormat.CSV to listOf(ExportScope.ALL_TABLES, ExportScope.ALL_VIEWS, ExportScope.SELECTED_TABLES, ExportScope.SELECTED_VIEWS),
            ExportFormat.MARKDOWN to listOf(ExportScope.ALL_TABLES, ExportScope.ALL_VIEWS, ExportScope.SELECTED_TABLES, ExportScope.SELECTED_VIEWS),
            ExportFormat.TEXT to listOf(ExportScope.ALL_TABLES, ExportScope.SELECTED_TABLES, ExportScope.SELECTED_VIEWS),
            ExportFormat.SQL to listOf(ExportScope.ALL_TABLES, ExportScope.SELECTED_TABLES)
        )
    }
}
