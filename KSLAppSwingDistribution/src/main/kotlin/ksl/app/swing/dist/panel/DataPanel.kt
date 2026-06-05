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

package ksl.app.swing.dist.panel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksl.app.dist.catalog.DistributionFamilyDescriptor
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DatasetLayout
import ksl.app.dist.config.DbType
import ksl.app.dist.config.Delimiter
import ksl.app.swing.dist.DistributionAppController
import ksl.app.swing.dist.LoadStatus
import ksl.utilities.random.rvariable.RVType
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel
import javax.swing.text.JTextComponent

/** A supported delimited-file shape, in plain language. */
enum class FileFormat(val label: String) {
    WHITESPACE_SINGLE("Whitespace-separated, single column → one dataset"),
    CSV_WIDE("CSV with header → one dataset per column"),
    CSV_LONG("Long / stacked — id + value columns")
}

/** Reads the head of a file for the preview pane and guesses its format. */
internal object FilePreview {
    data class Result(val text: String, val suggested: FileFormat, val headerTokens: List<String>)

    fun read(path: String, maxLines: Int = 15): Result {
        val lines = File(path).bufferedReader().useLines { seq -> seq.take(maxLines).toList() }
        val nonEmpty = lines.filter { it.isNotBlank() }
        val hasComma = nonEmpty.any { it.contains(',') }
        val suggested = if (hasComma) FileFormat.CSV_WIDE else FileFormat.WHITESPACE_SINGLE
        val headerTokens = if (hasComma && nonEmpty.isNotEmpty()) {
            nonEmpty.first().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        return Result(lines.joinToString("\n"), suggested, headerTokens)
    }
}

/**
 * Data tab: assemble a collection of named datasets from any number of sources
 * (inline paste or delimited file). Per-dataset fit settings (kind, estimators,
 * scoring, shift) and which datasets to fit are chosen on the Fitting tab; this
 * tab is purely about building and reviewing the collection.
 *
 * The add-source sub-forms are transient view state; committing an "Add" calls
 * the controller, which imports and appends to the persistent collection. The
 * `updating` guard prevents a state→widget render from echoing back as an edit.
 */
class DataPanel(private val controller: DistributionAppController) : JPanel(BorderLayout()) {

    private val okColor = Color(0x2E, 0x7D, 0x32)
    private val errColor = Color(0xC6, 0x28, 0x28)

    private var updating = false

    // add-source selector + cards
    private val addSourceCombo =
        JComboBox(arrayOf("Inline (paste)", "Delimited File", "Generated (sample)", "SQLite database"))
    private val cards = JPanel(CardLayout())

    // database card (embedded SQLite)
    private val dbPathField = JTextField(24).apply { isEditable = false }
    private var dbFullPath: String = ""
    private val dbTableCombo = JComboBox<String>()
    private val dbLayoutCombo = JComboBox(arrayOf(DatasetLayout.WIDE, DatasetLayout.LONG))
    private val dbColumnsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val dbColumnChecks = mutableListOf<JCheckBox>()
    private val dbColumnsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    private val dbIdCombo = JComboBox<String>()
    private val dbValueCombo = JComboBox<String>()
    private val dbLongRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    private val dbPreviewArea = JTextArea(6, 48)
    private val addDbButton = JButton("Add datasets")
    private var dbTableColumns: Map<String, List<DatabaseSource.DbColumn>> = emptyMap()

    // generated card
    private val generatedFamilies: List<DistributionFamilyDescriptor> = FittingCatalog.families
        .filter { it.rvType is RVType }
        .sortedWith(compareBy({ it.kind.name }, { it.displayName }))
    private val generatedDisplayNames: Set<String> = generatedFamilies.mapTo(mutableSetOf()) { it.displayName }
    private val generatedDistCombo = JComboBox(generatedFamilies.toTypedArray())
    private val generatedParamPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    private val generatedParamFields = LinkedHashMap<String, JTextField>()
    private val generatedSizeField = JTextField("100", 6)
    private val generatedStreamField = JTextField("1", 4)
    private val generatedNameField = JTextField(16)
    private val generateButton = JButton("Generate dataset")

    // inline card
    private val inlineNameField = JTextField("inline", 16)
    private val inlineValuesArea = JTextArea(5, 40)
    private val addInlineButton = JButton("Add dataset")

    // delimited-file card
    private val filePathField = JTextField(28)
    private val formatCombo = JComboBox(FileFormat.entries.toTypedArray())
    private val idCombo = JComboBox<String>().apply { isEditable = true }
    private val valueCombo = JComboBox<String>().apply { isEditable = true }
    private val longRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
    private val longHelp = JLabel(
        "<html>One value per row: the <b>id</b> column groups rows into datasets; " +
            "the <b>value</b> column holds the numbers. e.g. <tt>serviceA, 4.2</tt></html>"
    )
    private val previewArea = JTextArea(8, 48)
    private val addFileButton = JButton("Add datasets")

    private var lastBrowseDir: File? = null

    private val statusLabel = JLabel(" ")

    // collection
    private val tableModel = DatasetTableModel()
    private val table = JTable(tableModel)
    private val removeButton = JButton("Remove")
    private val clearButton = JButton("Clear")

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(buildAddArea(), BorderLayout.NORTH)
        add(buildCollectionArea(), BorderLayout.CENTER)
        installContextMenus()
        wireListeners()
        bindState()
        updateLongVisibility()
        rebuildParamFields()
        updateDbLayoutVisibility()
    }

    // --- construction --------------------------------------------------------

    private fun buildAddArea(): Component {
        val sourceRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Add datasets from:"))
            add(addSourceCombo)
            add(disabledNote("Database source arrives later"))
        }
        cards.add(buildInlineCard(), "INLINE")
        cards.add(buildFileCard(), "FILE")
        cards.add(buildGeneratedCard(), "GENERATED")
        cards.add(buildDatabaseCard(), "DATABASE")

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(statusLabel) }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Add datasets")
            add(sourceRow, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
            add(statusRow, BorderLayout.SOUTH)
        }
    }

    private fun buildInlineCard(): Component {
        inlineValuesArea.lineWrap = true
        inlineValuesArea.wrapStyleWord = true
        val nameRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Name:"))
            add(inlineNameField)
        }
        val hint = JLabel("Values, separated by whitespace or commas").apply { foreground = disabledForeground() }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(addInlineButton) }
        return JPanel(BorderLayout(4, 4)).apply {
            add(nameRow, BorderLayout.NORTH)
            add(JScrollPane(inlineValuesArea), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(hint, BorderLayout.NORTH)
                add(buttonRow, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
    }

    private fun buildFileCard(): Component {
        formatCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, selected, focus)
                if (value is FileFormat) text = value.label
                return c
            }
        }
        previewArea.isEditable = false
        previewArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)

        val pathRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("File:"))
            add(filePathField)
            add(JButton("Browse…").apply { addActionListener { browse() } })
        }
        formatCombo.toolTipText = "How the file is laid out. The preview above auto-detects this; override if needed."
        val formatRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Format:"))
            add(formatCombo)
        }
        longRow.apply {
            add(JLabel("Id column:"))
            add(idCombo)
            add(JLabel("Value column:"))
            add(valueCombo)
        }
        longHelp.foreground = disabledForeground()
        val longHelpRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(longHelp) }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(pathRow.alignLeft())
            add(formatRow.alignLeft())
            add(longRow.alignLeft())
            add(longHelpRow.alignLeft())
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(addFileButton) }
        return JPanel(BorderLayout(4, 4)).apply {
            add(top, BorderLayout.NORTH)
            add(JScrollPane(previewArea), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }
    }

    private fun buildGeneratedCard(): Component {
        generatedDistCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, selected, focus)
                if (value is DistributionFamilyDescriptor) {
                    text = "${value.displayName} (${value.kind.name.lowercase()})"
                }
                return c
            }
        }
        val distRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Distribution:"))
            add(generatedDistCombo)
        }
        val paramRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Parameters:"))
            add(generatedParamPanel)
        }
        val sizeRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Sample size:"))
            add(generatedSizeField)
            add(JLabel("Stream:"))
            add(generatedStreamField)
            add(disabledNote("stream > 0 reproduces; 0 = independent"))
        }
        val nameRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Name:"))
            add(generatedNameField)
        }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(distRow.alignLeft())
            add(paramRow.alignLeft())
            add(sizeRow.alignLeft())
            add(nameRow.alignLeft())
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(generateButton) }
        return JPanel(BorderLayout(4, 4)).apply {
            add(top, BorderLayout.NORTH)
            add(buttonRow, BorderLayout.SOUTH)
        }
    }

    private fun buildDatabaseCard(): Component {
        dbPreviewArea.isEditable = false
        dbPreviewArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val pathRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("File:"))
            add(dbPathField)
            add(JButton("Browse…").apply { addActionListener { dbBrowse() } })
        }
        val tableRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Table:"))
            add(dbTableCombo)
            add(JLabel("Layout:"))
            add(dbLayoutCombo)
        }
        dbColumnsRow.apply {
            add(JLabel("Import columns:"))
            add(JScrollPane(dbColumnsPanel).apply { preferredSize = java.awt.Dimension(220, 92) })
            add(disabledNote("checked columns are imported"))
        }
        dbLongRow.apply {
            add(JLabel("Id column:"))
            add(dbIdCombo)
            add(JLabel("Value column:"))
            add(dbValueCombo)
        }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(pathRow.alignLeft())
            add(tableRow.alignLeft())
            add(dbColumnsRow.alignLeft())
            add(dbLongRow.alignLeft())
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply { add(addDbButton) }
        return JPanel(BorderLayout(4, 4)).apply {
            add(top, BorderLayout.NORTH)
            add(JScrollPane(dbPreviewArea), BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }
    }

    private fun buildCollectionArea(): Component {
        table.fillsViewportHeight = true
        val selectionRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(removeButton)
            add(clearButton)
        }
        return JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("Dataset collection")
            add(JScrollPane(table), BorderLayout.CENTER)
            add(selectionRow, BorderLayout.SOUTH)
        }
    }

    // --- wiring --------------------------------------------------------------

    private fun wireListeners() {
        addSourceCombo.addActionListener {
            val card = when (addSourceCombo.selectedIndex) {
                0 -> "INLINE"
                1 -> "FILE"
                2 -> "GENERATED"
                else -> "DATABASE"
            }
            (cards.layout as CardLayout).show(cards, card)
        }
        addInlineButton.addActionListener { addInline() }
        addFileButton.addActionListener { addFile() }
        generatedDistCombo.addActionListener { rebuildParamFields() }
        generateButton.addActionListener { addGenerated() }
        dbTableCombo.addActionListener { if (!updating) onDbTableSelected() }
        dbLayoutCombo.addActionListener { updateDbLayoutVisibility() }
        addDbButton.addActionListener { addDatabase() }
        formatCombo.addActionListener { updateLongVisibility() }
        filePathField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) {
                if (!updating) loadPreview(filePathField.text)
            }
        })
        removeButton.addActionListener {
            val row = table.selectedRow
            if (row >= 0) controller.removeDataset(tableModel.nameAt(row))
        }
        clearButton.addActionListener { controller.clearDatasets() }
        table.selectionModel.addListSelectionListener { removeButton.isEnabled = table.selectedRow >= 0 }
    }

    private fun bindState() {
        val scope = controller.edtScope
        scope.launch { controller.collection.collect { refreshTable() } }
        scope.launch { controller.dataLoadStatus.collect { renderStatus(it) } }
        scope.launch { controller.documentReset.collect { resetView() } }
        renderStatus(controller.dataLoadStatus.value)
        refreshTable()
    }

    // --- actions -------------------------------------------------------------

    private fun addInline() {
        val name = inlineNameField.text.ifBlank { "inline" }
        val data = parseNumbers(inlineValuesArea.text)
        controller.addFrom(DataSourceReference.Inline(mapOf(name to data)), "inline")
        if (data.isNotEmpty()) inlineValuesArea.text = ""
    }

    private fun addFile() {
        val path = filePathField.text.trim()
        if (path.isEmpty()) {
            previewArea.text = "Choose a file first."
            return
        }
        val format = formatCombo.selectedItem as? FileFormat ?: FileFormat.CSV_WIDE
        val ref = when (format) {
            FileFormat.WHITESPACE_SINGLE -> DataSourceReference.DelimitedFile(
                path = path, delimiter = Delimiter.WHITESPACE, hasHeader = false, layout = DatasetLayout.SINGLE
            )
            FileFormat.CSV_WIDE -> DataSourceReference.DelimitedFile(
                path = path, delimiter = Delimiter.COMMA, hasHeader = true, layout = DatasetLayout.WIDE
            )
            FileFormat.CSV_LONG -> DataSourceReference.DelimitedFile(
                path = path, delimiter = Delimiter.COMMA, hasHeader = true, layout = DatasetLayout.LONG,
                idColumn = (idCombo.selectedItem as? String)?.ifBlank { null },
                valueColumn = (valueCombo.selectedItem as? String)?.ifBlank { null }
            )
        }
        controller.addFrom(ref, File(path).name)
    }

    private fun addGenerated() {
        val family = generatedDistCombo.selectedItem as? DistributionFamilyDescriptor ?: return
        val params = readParamFields() ?: return
        val n = generatedSizeField.text.trim().toIntOrNull()
        if (n == null || n <= 0) {
            genError("Sample size must be a positive integer."); return
        }
        val stream = generatedStreamField.text.trim().toIntOrNull()
        if (stream == null || stream < 0) {
            genError("Stream number must be a non-negative integer."); return
        }
        val name = generatedNameField.text.ifBlank { family.displayName }
        val ref = GeneratedDataset.buildRef(family, params, n, stream, name)
        controller.addFrom(ref, origin = "generated", kind = family.kind)
    }

    /** Reads the per-parameter fields; null (with a status message) on any non-numeric value. */
    private fun readParamFields(): Map<String, Double>? {
        val out = LinkedHashMap<String, Double>()
        for ((name, field) in generatedParamFields) {
            val v = field.text.trim().toDoubleOrNull()
            if (v == null) {
                genError("Parameter '$name' must be a number."); return null
            }
            out[name] = v
        }
        return out
    }

    /** Rebuilds the per-parameter fields for the selected distribution, pre-filled with its defaults. */
    private fun rebuildParamFields() {
        val family = generatedDistCombo.selectedItem as? DistributionFamilyDescriptor ?: return
        generatedParamFields.clear()
        generatedParamPanel.removeAll()
        val rvParams = (family.rvType as RVType).rvParameters
        for (pname in rvParams.parameterNames) {
            val default = runCatching { rvParams.doubleParameter(pname) }.getOrDefault(1.0)
            val field = JTextField(formatParam(default), 8)
            installTextContextMenu(field)
            generatedParamFields[pname] = field
            generatedParamPanel.add(JLabel("$pname:"))
            generatedParamPanel.add(field)
        }
        // Reflect the selected family in the name, unless the user typed a custom one.
        val currentName = generatedNameField.text.trim()
        if (currentName.isBlank() || currentName in generatedDisplayNames) {
            generatedNameField.text = family.displayName
        }
        generatedParamPanel.revalidate()
        generatedParamPanel.repaint()
    }

    private fun genError(message: String) {
        statusLabel.foreground = errColor
        statusLabel.text = "⚠ $message"
    }

    private fun formatParam(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun dbBrowse() {
        val start = lastBrowseDir
            ?: dbFullPath.takeIf { it.isNotBlank() }?.let { File(it).parentFile }
            ?: controller.ensureAppWorkspace().toFile()
        val chooser = JFileChooser(start).apply {
            dialogTitle = "Choose SQLite Database"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter(
                "SQLite databases (*.db, *.sqlite, *.sqlite3, *.db3)", "db", "sqlite", "sqlite3", "db3"
            )
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            lastBrowseDir = file.parentFile
            dbFullPath = file.absolutePath
            withUpdating {
                dbPathField.text = file.name
                dbPathField.toolTipText = file.absolutePath
            }
            loadTables()
        }
    }

    /** Opens the selected database off the EDT and populates the table/column selectors. */
    private fun loadTables() {
        val path = dbFullPath.trim()
        if (path.isEmpty()) return
        controller.edtScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    DatabaseSource.listTablesWithColumns(DbType.SQLITE, File(path).toPath())
                }
            }
            res.onSuccess { map ->
                dbTableColumns = map
                withUpdating { dbTableCombo.model = DefaultComboBoxModel(map.keys.toTypedArray()) }
                onDbTableSelected()
                if (map.isEmpty()) {
                    statusLabel.foreground = errColor
                    statusLabel.text = "No tables found in database."
                } else {
                    statusLabel.foreground = okColor
                    statusLabel.text = "Found ${map.size} table(s)."
                }
            }.onFailure { t ->
                dbTableColumns = emptyMap()
                withUpdating { dbTableCombo.model = DefaultComboBoxModel(emptyArray<String>()) }
                dbPreviewArea.text = ""
                statusLabel.foreground = errColor
                statusLabel.text = "⚠ Cannot open database: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    /** When a table is selected, refresh the column selectors and load its preview. */
    private fun onDbTableSelected() {
        updateDbColumnSelectors()
        loadTablePreview()
    }

    /**
     * Refreshes the import-column list (numeric columns) and the id/value column
     * selectors (id: all columns; value: numeric columns) for the current table.
     */
    private fun updateDbColumnSelectors() {
        val table = dbTableCombo.selectedItem as? String
        val cols = dbTableColumns[table] ?: emptyList()
        val numeric = cols.filter { it.numeric }.map { it.name }
        val all = cols.map { it.name }
        withUpdating {
            dbColumnChecks.clear()
            dbColumnsPanel.removeAll()
            for (name in numeric) {
                val cb = JCheckBox(name, true)
                dbColumnChecks.add(cb)
                dbColumnsPanel.add(cb)
            }
            dbColumnsPanel.revalidate()
            dbColumnsPanel.repaint()
            dbIdCombo.model = DefaultComboBoxModel(all.toTypedArray())
            dbValueCombo.model = DefaultComboBoxModel(numeric.toTypedArray())
        }
    }

    /** Loads the first few rows of the selected table (with header) into the preview area, off the EDT. */
    private fun loadTablePreview() {
        val path = dbFullPath.trim()
        val table = dbTableCombo.selectedItem as? String
        if (path.isEmpty() || table.isNullOrBlank()) {
            dbPreviewArea.text = ""
            return
        }
        controller.edtScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) { DatabaseSource.previewTable(DbType.SQLITE, File(path).toPath(), table) }
            }
            res.onSuccess { text ->
                dbPreviewArea.text = text
                dbPreviewArea.caretPosition = 0
            }.onFailure { t ->
                dbPreviewArea.text = "Cannot preview table: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    private fun updateDbLayoutVisibility() {
        val isLong = dbLayoutCombo.selectedItem == DatasetLayout.LONG
        dbColumnsRow.isVisible = !isLong
        dbLongRow.isVisible = isLong
    }

    private fun addDatabase() {
        val path = dbFullPath.trim()
        if (path.isEmpty()) {
            genError("Choose a database file first."); return
        }
        val table = dbTableCombo.selectedItem as? String
        if (table.isNullOrBlank()) {
            genError("Select a table."); return
        }
        val layout = dbLayoutCombo.selectedItem as DatasetLayout
        val ref = if (layout == DatasetLayout.LONG) {
            val id = dbIdCombo.selectedItem as? String
            val value = dbValueCombo.selectedItem as? String
            if (id.isNullOrBlank() || value.isNullOrBlank()) {
                genError("Select id and value columns for the LONG layout."); return
            }
            DatabaseSource.buildRef(DbType.SQLITE, path, table, layout, null, id, value)
        } else {
            val checked = dbColumnChecks.filter { it.isSelected }.map { it.text }
            if (checked.isEmpty()) {
                genError("Select at least one column to import."); return
            }
            DatabaseSource.buildRef(DbType.SQLITE, path, table, DatasetLayout.WIDE, checked, null, null)
        }
        controller.addFrom(ref, origin = "db:${File(path).name}")
    }

    private fun installContextMenus() {
        installTextContextMenu(inlineNameField)
        installTextContextMenu(inlineValuesArea)
        installTextContextMenu(filePathField)
        installTextContextMenu(previewArea)
        (idCombo.editor.editorComponent as? JTextComponent)?.let { installTextContextMenu(it) }
        (valueCombo.editor.editorComponent as? JTextComponent)?.let { installTextContextMenu(it) }
        installTextContextMenu(generatedSizeField)
        installTextContextMenu(generatedStreamField)
        installTextContextMenu(generatedNameField)
        installTextContextMenu(dbPathField)
        installTextContextMenu(dbPreviewArea)
    }

    private fun browse() {
        val start = lastBrowseDir
            ?: filePathField.text.takeIf { it.isNotBlank() }?.let { File(it).parentFile }
            ?: controller.ensureAppWorkspace().toFile()
        val chooser = JFileChooser(start).apply {
            dialogTitle = "Choose Data File"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Data files (*.csv, *.txt, *.dat, *.tsv)", "csv", "txt", "dat", "tsv")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            lastBrowseDir = file.parentFile
            withUpdating { filePathField.text = file.absolutePath }
            loadPreview(file.absolutePath)
        }
    }

    private fun loadPreview(path: String) {
        if (path.isBlank()) {
            previewArea.text = ""
            return
        }
        controller.edtScope.launch {
            val res = runCatching { withContext(Dispatchers.IO) { FilePreview.read(path) } }
            res.onSuccess { r ->
                previewArea.text = r.text
                previewArea.caretPosition = 0
                withUpdating {
                    formatCombo.selectedItem = r.suggested
                    idCombo.model = DefaultComboBoxModel(r.headerTokens.toTypedArray())
                    valueCombo.model = DefaultComboBoxModel(r.headerTokens.toTypedArray())
                }
                updateLongVisibility()
            }.onFailure { t ->
                previewArea.text = "Cannot read file: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    /** Clears the transient add-form view when the document is reset (New/Open). */
    private fun resetView() {
        withUpdating {
            addSourceCombo.selectedIndex = 0
            (cards.layout as CardLayout).show(cards, "INLINE")
            inlineNameField.text = "inline"
            inlineValuesArea.text = ""
            filePathField.text = ""
            previewArea.text = ""
            formatCombo.selectedItem = FileFormat.CSV_WIDE
            idCombo.model = DefaultComboBoxModel(emptyArray<String>())
            valueCombo.model = DefaultComboBoxModel(emptyArray<String>())
            generatedDistCombo.selectedIndex = 0
            generatedSizeField.text = "100"
            generatedStreamField.text = "1"
            generatedNameField.text = ""
            dbPathField.text = ""
            dbPathField.toolTipText = null
            dbTableCombo.model = DefaultComboBoxModel(emptyArray<String>())
            dbLayoutCombo.selectedItem = DatasetLayout.WIDE
            dbColumnChecks.clear()
            dbColumnsPanel.removeAll()
            dbIdCombo.model = DefaultComboBoxModel(emptyArray<String>())
            dbValueCombo.model = DefaultComboBoxModel(emptyArray<String>())
            dbPreviewArea.text = ""
        }
        dbFullPath = ""
        dbTableColumns = emptyMap()
        lastBrowseDir = null
        updateLongVisibility()
        rebuildParamFields()
        updateDbLayoutVisibility()
    }

    // --- rendering -----------------------------------------------------------

    private fun updateLongVisibility() {
        val isLong = formatCombo.selectedItem == FileFormat.CSV_LONG
        longRow.isVisible = isLong
        longHelp.isVisible = isLong
    }

    private fun refreshTable() {
        val rows = controller.collection.value.map { e ->
            val data = e.data
            DatasetRow(
                name = e.name,
                source = e.origin,
                n = data.size,
                min = data.minOrNull() ?: 0.0,
                max = data.maxOrNull() ?: 0.0,
                average = if (data.isEmpty()) 0.0 else data.average()
            )
        }
        withUpdating { tableModel.setRows(rows) }
        removeButton.isEnabled = table.selectedRow >= 0
    }

    private fun renderStatus(status: LoadStatus) {
        when (status) {
            LoadStatus.Idle -> statusLabel.text = " "
            LoadStatus.Loading -> {
                statusLabel.text = "Loading…"
                statusLabel.foreground = disabledForeground()
            }
            is LoadStatus.Loaded -> {
                statusLabel.text = "Added ${status.count} dataset(s)"
                statusLabel.foreground = okColor
            }
            is LoadStatus.Failed -> {
                statusLabel.text = "⚠ ${status.message}"
                statusLabel.foreground = errColor
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun parseNumbers(text: String): DoubleArray =
        text.split(Regex("[\\s,]+")).mapNotNull { it.trim().toDoubleOrNull() }.toDoubleArray()

    private fun disabledForeground(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

    private fun disabledNote(text: String): JLabel = JLabel(text).apply { foreground = disabledForeground() }

    private fun JPanel.alignLeft(): JPanel = apply { alignmentX = Component.LEFT_ALIGNMENT }

    private inline fun withUpdating(block: () -> Unit) {
        val prev = updating
        updating = true
        try {
            block()
        } finally {
            updating = prev
        }
    }

    private data class DatasetRow(
        val name: String,
        val source: String,
        val n: Int,
        val min: Double,
        val max: Double,
        val average: Double
    )

    private inner class DatasetTableModel : AbstractTableModel() {
        private val columns = arrayOf("Dataset", "source", "n", "min", "max", "average")
        private var rows: List<DatasetRow> = emptyList()

        fun setRows(newRows: List<DatasetRow>) {
            rows = newRows
            fireTableDataChanged()
        }

        fun nameAt(row: Int): String = rows[row].name

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun isCellEditable(row: Int, column: Int): Boolean = false
        override fun getColumnClass(column: Int): Class<*> = java.lang.String::class.java

        override fun getValueAt(row: Int, column: Int): Any {
            val r = rows[row]
            return when (column) {
                0 -> r.name
                1 -> r.source
                2 -> r.n.toString()
                3 -> fmt(r.min)
                4 -> fmt(r.max)
                5 -> fmt(r.average)
                else -> ""
            }
        }

        private fun fmt(v: Double): String = String.format("%.4g", v)
    }
}
