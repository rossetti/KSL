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

package ksl.app.swing.experiment

import kotlinx.coroutines.launch
import ksl.app.config.ReportFormat
import ksl.app.config.sanitizeAnalysisName
import ksl.app.notification.NotificationSink
import ksl.controls.experiments.LinearModel
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.regressionDiagnostics
import ksl.utilities.io.report.extensions.regressionParameters
import ksl.utilities.io.report.extensions.regressionSummary
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.statistic.RegressionResultsIfc
import org.jetbrains.kotlinx.dataframe.io.writeCsv
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 *  Regression tab — Phase E9.
 *
 *  Browser-first analysis launcher.  The panel itself is a thin form
 *  over the substrate's `DesignedExperimentIfc.regressionResults(...)`
 *  + `RegressionResultsIfc.toReport(...)` pipeline.
 *
 *  Two distinct disk-side actions, each with explicit semantics:
 *
 *  - **Open** writes the HTML to an OS temporary file
 *    ([Files.createTempFile]) and launches the system browser.  It
 *    does **not** touch the reports directory, does **not** alter
 *    the record's [RegressionFitRecord.savedPaths], and does not
 *    require the user to name anything.  Iteration is cheap.
 *
 *  - **Save** opens [SaveRegressionReportDialog], where the user
 *    names the file, picks formats (HTML / Markdown / Text),
 *    picks which report sections to include, and optionally
 *    requests CSV exports for the coefficient table and the
 *    residual table.  Files land under
 *    `<workspace>/output/<analysisName>/reports/` and the record's
 *    [RegressionFitRecord.savedPaths] grows by the written paths.
 *
 *  - **Save all unsaved** (header button) keeps the original
 *    auto-naming behaviour: timestamp-based stem, formats from
 *    `OutputConfig.reports`, default [RegressionResultsIfc.toReport]
 *    sections, no CSV.  No per-row prompting — meant for
 *    unattended pre-Simulate rescue.
 *
 *  Empty-state cards keep the populated card off-screen until the
 *  user has a real model + factors + run results to fit against.
 */
class RegressionTabPanel(
    private val controller: ExperimentAppController,
    private val notifier: NotificationSink = NotificationSink.NOOP
) : JPanel(BorderLayout()) {

    // ── Empty-state cards ──────────────────────────────────────────────────

    private val cards = CardLayout()
    private val cardsPanel = JPanel(cards)
    private val noModelCard = makeMessageCard(
        "Select a model on the Model tab before fitting a regression."
    )
    private val noFactorsCard = makeMessageCard(
        "Add at least one factor on the Factors tab before fitting a regression."
    )
    private val noResultsCard = makeMessageCard(
        "Run the experiment (Simulate tab) to produce results — then return here to fit."
    )
    private val populatedCard = JPanel(BorderLayout())

    // ── Form widgets ───────────────────────────────────────────────────────

    private val responseCombo = JComboBox<String>().apply {
        toolTipText = "Response variable from the model.  Pre-populated from " +
            "the loaded model's responseNames; refined to the experiment's " +
            "actual response set once a run completes."
    }
    private val codedRadio = JRadioButton("Coded (−1, +1)", true).apply {
        toolTipText = "Fit against coded factor levels.  Recommended for two-level designs " +
            "and central-composite designs."
    }
    private val naturalRadio = JRadioButton("Natural").apply {
        toolTipText = "Fit against the factor's natural-unit levels (as authored on the Factors tab)."
    }
    private val levelsGroup = ButtonGroup().apply {
        add(codedRadio); add(naturalRadio)
    }
    private val confidenceCombo = JComboBox(arrayOf(0.90, 0.95, 0.99)).apply {
        selectedItem = 0.95
        toolTipText = "Confidence level for parameter CIs and significance codes in the report."
    }
    private val firstOrderRadio = JRadioButton("Main effects (1st order)", true).apply {
        toolTipText = "y = β₀ + Σ βᵢ xᵢ.  No interactions."
    }
    private val secondOrderRadio = JRadioButton("Main + 2-way interactions").apply {
        toolTipText = "Main effects plus all 2-way interactions.  " +
            "Equivalent to LinearModel.Type.FirstAndSecond."
    }
    private val allTermsRadio = JRadioButton("All k-way interactions").apply {
        toolTipText = "Main effects plus every k-way interaction up to k = number of factors.  " +
            "Equivalent to LinearModel.Type.AllTerms."
    }
    private val customRadio = JRadioButton("Custom:").apply {
        toolTipText = "Specify additional terms beyond the main effects.  " +
            "Syntax: space-separated terms with '*' for interactions, e.g. 'A*B A*A B*C'.  " +
            "Main effects are always included — write only the extra terms here."
    }
    private val modelGroup = ButtonGroup().apply {
        add(firstOrderRadio); add(secondOrderRadio); add(allTermsRadio); add(customRadio)
    }
    private val customField = JTextField(28).apply {
        toolTipText = "Space-separated additional terms.  '*' is the interaction operator " +
            "(e.g. 'A*B' = two-way A·B; 'A*A' = quadratic in A; 'A*B*C' = three-way A·B·C)."
        isEnabled = false
    }
    private val expressionLabel = JLabel(" ").apply {
        font = font.deriveFont(font.size * 0.92f)
        foreground = Color(0x44, 0x44, 0x44)
        toolTipText = "Effective model the Fit button will submit, as understood by LinearModel.asString()."
    }
    private val fitButton = JButton("Fit Regression")

    // ── Status + last-fit actions ──────────────────────────────────────────

    private val statusLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }
    private val openLastFitButton = JButton("Open report in browser").apply {
        isVisible = false
        toolTipText = "Render the most-recent fit's HTML report to an OS temporary file " +
            "and open it in your system browser.  Does not write to the reports directory."
    }
    private val saveLastFitButton = JButton("Save report to disk…").apply {
        isVisible = false
        toolTipText = "Open the Save dialog: name the file, pick formats (HTML / Markdown / Text), " +
            "pick report sections, and optionally export coefficient / residual CSVs to the " +
            "workspace reports directory."
    }

    // ── Recent fits ────────────────────────────────────────────────────────

    private val recentTableModel = RecentFitsTableModel()
    private val recentTable = JTable(recentTableModel).apply {
        rowHeight = 22
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        tableHeader.reorderingAllowed = false
    }
    private val recentCountLabel = JLabel(" ").apply {
        font = font.deriveFont(font.size * 0.95f)
    }
    private val saveAllUnsavedButton = JButton("Save all unsaved").apply {
        toolTipText = "Materialise every unsaved fit using auto-generated timestamp names " +
            "and the document's configured report formats.  No per-row prompting — meant for " +
            "a quick pre-Simulate rescue."
        isEnabled = false
    }
    private val clearAllButton = JButton("Clear all").apply {
        toolTipText = "Discard every record from the recent-fits list.  " +
            "Files already saved to disk are NOT removed."
        isEnabled = false
    }
    private val openRowButton = JButton("Open").apply {
        toolTipText = "Render the selected row's report to an OS temp file and open it in the browser.  " +
            "Does not write to the reports directory."
        isEnabled = false
    }
    private val saveRowButton = JButton("Save…").apply {
        toolTipText = "Open the Save dialog for the selected row (name, formats, sections, CSV exports)."
        isEnabled = false
    }
    private val removeRowButton = JButton("Remove").apply {
        toolTipText = "Drop the selected row from the recent-fits list.  Files on disk are not deleted."
        isEnabled = false
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        cardsPanel.add(noModelCard, CARD_NO_MODEL)
        cardsPanel.add(noFactorsCard, CARD_NO_FACTORS)
        cardsPanel.add(noResultsCard, CARD_NO_RESULTS)
        cardsPanel.add(buildPopulated(), CARD_POPULATED)
        add(cardsPanel, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)

        wireFormBehaviour()
        wireRecentTableBehaviour()
        wireControllerSubscribers()
        refreshExpression()
        refreshCard()
    }

    // ── Layout helpers ─────────────────────────────────────────────────────

    private fun makeMessageCard(text: String): JPanel = JPanel(BorderLayout()).apply {
        add(
            JLabel(text, SwingConstants.CENTER).apply { foreground = Color(0x88, 0x88, 0x88) },
            BorderLayout.CENTER
        )
    }

    private fun buildSouth(): JPanel = JPanel(BorderLayout()).apply {
        add(DocumentStateLabel(controller.isDirty, controller.edtScope), BorderLayout.EAST)
    }

    private fun buildPopulated(): JPanel {
        val outer = JPanel()
        outer.layout = BoxLayout(outer, BoxLayout.Y_AXIS)
        outer.add(buildSpecPanel())
        outer.add(Box.createVerticalStrut(6))
        outer.add(buildStatusRow())
        outer.add(Box.createVerticalStrut(8))
        outer.add(buildRecentPanel())
        outer.add(Box.createVerticalGlue())
        return outer
    }

    private fun buildSpecPanel(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Model Specification"),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val gbc = GridBagConstraints().apply {
            insets = Insets(3, 4, 3, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        gbc.gridx = 0; gbc.gridy = 0; panel.add(JLabel("Response:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(responseCombo, gbc)
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridx = 0; gbc.gridy = 1; panel.add(JLabel("Levels:"), gbc)
        val levelsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(codedRadio); add(Box.createHorizontalStrut(8)); add(naturalRadio)
        }
        gbc.gridx = 1; panel.add(levelsRow, gbc)
        gbc.gridx = 2; panel.add(JLabel("Confidence:"), gbc)
        gbc.gridx = 3; panel.add(confidenceCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 2; panel.add(JLabel("Model:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3; panel.add(firstOrderRadio, gbc)
        gbc.gridy = 3; gbc.gridx = 1; panel.add(secondOrderRadio, gbc)
        gbc.gridy = 4; panel.add(allTermsRadio, gbc)
        gbc.gridy = 5
        val customRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(customRadio); add(Box.createHorizontalStrut(4)); add(customField)
        }
        panel.add(customRow, gbc)
        gbc.gridwidth = 1

        gbc.gridx = 0; gbc.gridy = 6; panel.add(JLabel("Expression:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(expressionLabel, gbc)
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridx = 1; gbc.gridy = 7
        gbc.insets = Insets(8, 4, 3, 4)
        panel.add(fitButton, gbc)

        return panel
    }

    private fun buildStatusRow(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        alignmentX = Component.LEFT_ALIGNMENT
        add(statusLabel)
        add(Box.createHorizontalStrut(12))
        add(openLastFitButton)
        add(Box.createHorizontalStrut(6))
        add(saveLastFitButton)
        add(Box.createHorizontalGlue())
    }

    private fun buildRecentPanel(): JPanel {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(recentCountLabel)
            add(Box.createHorizontalGlue())
            add(saveAllUnsavedButton)
            add(Box.createHorizontalStrut(6))
            add(clearAllButton)
        }
        val rowActions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentY = Component.TOP_ALIGNMENT
            add(openRowButton)
            add(Box.createVerticalStrut(4))
            add(saveRowButton)
            add(Box.createVerticalStrut(4))
            add(removeRowButton)
            add(Box.createVerticalGlue())
            border = BorderFactory.createEmptyBorder(4, 8, 4, 0)
            preferredSize = Dimension(110, 0)
        }
        for (b in listOf(openRowButton, saveRowButton, removeRowButton, saveAllUnsavedButton, clearAllButton)) {
            b.alignmentX = Component.LEFT_ALIGNMENT
            b.maximumSize = Dimension(110, b.preferredSize.height)
        }
        val scroll = JScrollPane(recentTable).apply {
            preferredSize = Dimension(0, 180)
        }
        recentTable.columnModel.getColumn(COL_STATUS).cellRenderer =
            object : DefaultTableCellRenderer() {
                init { horizontalAlignment = SwingConstants.CENTER }
            }
        recentTable.columnModel.getColumn(COL_TIME).preferredWidth = 60
        recentTable.columnModel.getColumn(COL_RESPONSE).preferredWidth = 110
        recentTable.columnModel.getColumn(COL_TERMS).preferredWidth = 260
        recentTable.columnModel.getColumn(COL_LEVELS).preferredWidth = 60
        recentTable.columnModel.getColumn(COL_STATUS).preferredWidth = 70

        val body = JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
            add(rowActions, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Recent fits"),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    // ── Behaviour wiring ───────────────────────────────────────────────────

    private fun wireFormBehaviour() {
        val updateCustomEnabled = {
            customField.isEnabled = customRadio.isSelected
            refreshExpression()
        }
        firstOrderRadio.addActionListener { updateCustomEnabled() }
        secondOrderRadio.addActionListener { updateCustomEnabled() }
        allTermsRadio.addActionListener { updateCustomEnabled() }
        customRadio.addActionListener { updateCustomEnabled() }
        customField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshExpression()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshExpression()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshExpression()
        })
        responseCombo.addActionListener { refreshFitEnablement() }
        fitButton.addActionListener { handleFit() }
        openLastFitButton.addActionListener { handleOpenLastFit() }
        saveLastFitButton.addActionListener { handleSaveLastFit() }
    }

    private fun wireRecentTableBehaviour() {
        recentTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) refreshRowActionEnablement()
        }
        openRowButton.addActionListener { withSelectedRecord { _, r -> openRecord(r) } }
        saveRowButton.addActionListener { withSelectedRecord { i, r -> promptAndSave(i, r) } }
        removeRowButton.addActionListener {
            val sel = recentTable.selectedRow
            if (sel >= 0) controller.removeRegressionFit(sel)
        }
        saveAllUnsavedButton.addActionListener { saveAllUnsavedRegressionFits() }
        clearAllButton.addActionListener { handleClearAll() }
    }

    private fun wireControllerSubscribers() {
        controller.edtScope.launch { controller.modelReference.collect { refreshCard() } }
        controller.edtScope.launch {
            controller.currentModelDescriptor.collect { refreshResponseCombo(); refreshCard() }
        }
        controller.edtScope.launch {
            controller.factors.collect { refreshExpression(); refreshFitEnablement(); refreshCard() }
        }
        controller.edtScope.launch {
            controller.experimentInstance.collect {
                refreshResponseCombo(); refreshFitEnablement(); refreshCard()
            }
        }
        controller.edtScope.launch { controller.lastResult.collect { refreshCard() } }
        controller.edtScope.launch { controller.runningFlow.collect { refreshFitEnablement() } }
        controller.edtScope.launch { controller.editedSinceLastSim.collect { refreshStatus() } }
        controller.edtScope.launch {
            controller.recentRegressionFits.collect { list ->
                recentTableModel.replace(list)
                refreshRecentHeader()
                refreshRowActionEnablement()
                // Status follows the head-of-list — refreshing here
                // means Clear All / Remove on the most-recent row /
                // FIFO eviction all flip the chip correctly without a
                // dedicated subscriber.
                refreshStatus()
            }
        }
    }

    // ── State refresh ──────────────────────────────────────────────────────

    private fun refreshCard() {
        val card = when {
            controller.modelReference.value == null -> CARD_NO_MODEL
            controller.factors.value.isEmpty() -> CARD_NO_FACTORS
            controller.experimentInstance.value == null -> CARD_NO_RESULTS
            else -> CARD_POPULATED
        }
        cards.show(cardsPanel, card)
    }

    private fun refreshResponseCombo() {
        val previous = responseCombo.selectedItem as? String
        val names = (controller.experimentInstance.value?.responseNames
            ?: controller.currentModelDescriptor.value?.responseNames?.toList()
            ?: emptyList()).sorted()
        val model = DefaultComboBoxModel(names.toTypedArray())
        responseCombo.model = model
        if (previous != null && previous in names) {
            responseCombo.selectedItem = previous
        }
        refreshFitEnablement()
    }

    private fun refreshExpression() {
        val expr = buildLinearModelOrNull()?.asString()
        expressionLabel.text = when {
            controller.factors.value.isEmpty() -> "— (no factors)"
            expr == null -> "— (invalid custom terms)"
            expr.isBlank() -> "— (empty model)"
            else -> expr
        }
        refreshFitEnablement()
    }

    private fun refreshFitEnablement() {
        val gated = controller.modelReference.value != null &&
            controller.factors.value.isNotEmpty() &&
            controller.experimentInstance.value != null &&
            !controller.runningFlow.value &&
            responseCombo.selectedItem != null &&
            buildLinearModelOrNull() != null
        fitButton.isEnabled = gated
    }

    /**
     *  Status chip drives off the head of the recent-fits list, NOT
     *  a separate `lastRegressionFit` field.  This way Clear All /
     *  Remove on the head row / FIFO eviction all collapse the chip
     *  to its idle state without separate plumbing (the fix for
     *  Issue 3 in the round of feedback after the first cut).
     */
    private fun refreshStatus() {
        val mostRecent = controller.recentRegressionFits.value.firstOrNull()
        val stale = controller.editedSinceLastSim.value && mostRecent != null
        when {
            mostRecent == null -> {
                statusLabel.text = " "
                statusLabel.foreground = STATUS_GREY
                openLastFitButton.isVisible = false
                saveLastFitButton.isVisible = false
            }
            stale -> {
                statusLabel.text = "⚠ Last fit was against the previous run — re-fit recommended."
                statusLabel.foreground = STATUS_AMBER
                openLastFitButton.isVisible = true
                saveLastFitButton.isVisible = true
            }
            else -> {
                val units = if (mostRecent.coded) "coded" else "natural"
                val alpha = "%.2f".format(1.0 - mostRecent.confidenceLevel)
                statusLabel.text = "✓ Fit succeeded — ${mostRecent.response}, $units, α = $alpha"
                statusLabel.foreground = STATUS_GREEN
                openLastFitButton.isVisible = true
                saveLastFitButton.isVisible = true
            }
        }
    }

    private fun refreshRecentHeader() {
        val list = controller.recentRegressionFits.value
        recentCountLabel.text = "Recent fits (${list.size}/${ExperimentAppController.MAX_RECENT_FITS})"
        val unsavedCount = list.count { it.savedPaths.isEmpty() }
        saveAllUnsavedButton.isEnabled = unsavedCount > 0
        saveAllUnsavedButton.toolTipText = if (unsavedCount > 0) {
            "Materialise the $unsavedCount unsaved fit(s) to the reports directory using " +
                "auto-generated timestamp names and the document's configured formats."
        } else {
            "All fits already saved to disk."
        }
        clearAllButton.isEnabled = list.isNotEmpty()
    }

    private fun refreshRowActionEnablement() {
        val sel = recentTable.selectedRow
        val record = controller.recentRegressionFits.value.getOrNull(sel)
        openRowButton.isEnabled = record != null
        removeRowButton.isEnabled = record != null
        saveRowButton.isEnabled = record != null
    }

    // ── LinearModel construction ───────────────────────────────────────────

    private fun buildLinearModelOrNull(): LinearModel? {
        val factorNames = controller.factors.value.map { it.name }.toSet()
        if (factorNames.isEmpty()) return null
        return try {
            when {
                firstOrderRadio.isSelected ->
                    LinearModel(factorNames, LinearModel.Type.FirstOrder)
                secondOrderRadio.isSelected ->
                    LinearModel(factorNames, LinearModel.Type.FirstAndSecond)
                allTermsRadio.isSelected ->
                    LinearModel(factorNames, LinearModel.Type.AllTerms)
                customRadio.isSelected -> {
                    val base = LinearModel(factorNames, LinearModel.Type.FirstOrder)
                    val text = customField.text.trim()
                    if (text.isEmpty()) base else base.parseFromString(text)
                }
                else -> LinearModel(factorNames, LinearModel.Type.FirstOrder)
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ── Fit ────────────────────────────────────────────────────────────────

    private fun handleFit() {
        val response = responseCombo.selectedItem as? String ?: run {
            notifier.warn("Pick a response before fitting."); return
        }
        val model = buildLinearModelOrNull() ?: run {
            notifier.warn(
                "Model is invalid — check the custom-terms expression."
            )
            return
        }
        val coded = codedRadio.isSelected
        val level = (confidenceCombo.selectedItem as? Double) ?: 0.95
        try {
            val fit = controller.fitRegression(response, model, coded, level)
            if (fit == null) {
                notifier.warn(
                    "No experiment results retained — run the experiment first."
                )
            }
        } catch (t: Throwable) {
            notifier.error(
                "Regression failed: ${t.message ?: t::class.simpleName}"
            )
        }
    }

    // ── Open ───────────────────────────────────────────────────────────────

    private fun handleOpenLastFit() {
        val record = controller.recentRegressionFits.value.firstOrNull() ?: return
        openRecord(record)
    }

    /**
     *  Renders the report to an **OS temp file** (not the reports
     *  directory) and opens it in the system browser.  Pure preview;
     *  does not modify [RegressionFitRecord.savedPaths].
     *
     *  Using a per-call temp file means iterative fitting doesn't
     *  pollute the workspace, and the user is never prompted to name
     *  something they're not committing to.
     */
    private fun openRecord(record: RegressionFitRecord) {
        try {
            val tempDir = Files.createTempDirectory("ksl-regression-")
            val ctx = RenderContext(
                outputDir = tempDir,
                confidenceLevel = record.confidenceLevel
            )
            record.fit
                .toReportWithSections(
                    title = defaultTitle(record),
                    confidenceLevel = record.confidenceLevel,
                    sections = ReportSections.ALL
                )
                .showInBrowser(ctx = ctx)
        } catch (t: Throwable) {
            notifier.error(
                "Could not open report: ${t.message ?: t::class.simpleName}"
            )
        }
    }

    // ── Save (per-row / most-recent — prompts the user) ───────────────────

    private fun handleSaveLastFit() {
        val list = controller.recentRegressionFits.value
        val record = list.firstOrNull() ?: return
        promptAndSave(0, record)
    }

    private fun promptAndSave(index: Int, record: RegressionFitRecord) {
        val dir = ensureReportsDir() ?: return
        val initialFormats = controller.outputConfig.value.reports
            .ifEmpty { setOf(ReportFormat.HTML) }
        val options = SaveRegressionReportDialog.prompt(
            owner = SwingUtilities.getWindowAncestor(this),
            defaultStem = defaultStem(record),
            folder = dir,
            initialFormats = initialFormats
        ) ?: return
        executeSave(index, record, dir, options, suppressToast = false)
    }

    // ── Save all unsaved (app naming, no dialog) ──────────────────────────

    /**
     *  Materialise every unsaved record in
     *  [ExperimentAppController.recentRegressionFits] to the reports
     *  directory using auto-generated names and the document's
     *  configured formats.  Called by both the in-tab Save-all
     *  button and the frame's Simulate-prompt "Save all and simulate"
     *  branch so both paths produce identical files and surface a
     *  single summary toast.
     */
    fun saveAllUnsavedRegressionFits() {
        val list = controller.recentRegressionFits.value
        val dir = ensureReportsDir() ?: return
        val formats = controller.outputConfig.value.reports
            .ifEmpty { setOf(ReportFormat.HTML) }
        var saved = 0
        for ((i, record) in list.withIndex()) {
            if (record.savedPaths.isNotEmpty()) continue
            val options = SaveOptions(
                stem = defaultStem(record),
                formats = formats,
                sections = ReportSections.ALL,
                includeCoefficientCsv = false,
                includeResidualsCsv = false,
                overwriteExisting = true   // unattended; the user opted in by clicking Save All
            )
            if (executeSave(i, record, dir, options, suppressToast = true)) saved++
        }
        if (saved > 0) {
            notifier.info("Saved $saved regression report(s).")
        } else {
            notifier.info("Nothing to save — all fits already on disk.")
        }
    }

    // ── Save executor (shared by promptAndSave + saveAllUnsavedRegressionFits) ──

    /**
     *  Write the report (and optional CSV exports) under [dir] using
     *  the [options] supplied by the dialog (interactive path) or by
     *  [saveAllUnsavedRegressionFits] (batch path).  Per-file
     *  overwrite confirmation runs only on the interactive path
     *  (`options.overwriteExisting == false`); batch saves silently
     *  overwrite — the user already confirmed by clicking "Save all".
     *
     *  Returns `true` when at least one file was written; the toast
     *  is emitted only when `suppressToast == false`.
     */
    private fun executeSave(
        index: Int,
        record: RegressionFitRecord,
        dir: Path,
        options: SaveOptions,
        suppressToast: Boolean
    ): Boolean {
        val doc = record.fit.toReportWithSections(
            title = defaultTitle(record),
            confidenceLevel = record.confidenceLevel,
            sections = options.sections
        )
        val ctx = RenderContext(outputDir = dir, confidenceLevel = record.confidenceLevel)
        val written = mutableListOf<Path>()
        try {
            for (fmt in options.formats) {
                val ext = when (fmt) {
                    ReportFormat.HTML -> "html"
                    ReportFormat.MARKDOWN -> "md"
                    ReportFormat.TEXT -> "txt"
                }
                val path = dir.resolve("${options.stem}.$ext")
                if (!confirmOverwriteIfNeeded(path, options.overwriteExisting)) continue
                val file = when (fmt) {
                    ReportFormat.HTML -> doc.writeHtml(path = path, ctx = ctx)
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path, ctx = ctx)
                    ReportFormat.TEXT -> doc.writeText(path = path, ctx = ctx)
                }
                written.add(file.toPath())
            }
            if (options.includeCoefficientCsv) {
                val csv = dir.resolve("${options.stem}-coefficients.csv")
                if (confirmOverwriteIfNeeded(csv, options.overwriteExisting)) {
                    record.fit.parameterResults(record.confidenceLevel).writeCsv(csv)
                    written.add(csv)
                }
            }
            if (options.includeResidualsCsv) {
                val csv = dir.resolve("${options.stem}-residuals.csv")
                if (confirmOverwriteIfNeeded(csv, options.overwriteExisting)) {
                    record.fit.residualsAsDataFrame().writeCsv(csv)
                    written.add(csv)
                }
            }
        } catch (t: Throwable) {
            notifier.error(
                "Could not save report: ${t.message ?: t::class.simpleName}"
            )
            return false
        }
        if (written.isNotEmpty()) {
            controller.markRegressionFitSaved(index, written)
            if (!suppressToast) {
                notifier.info(
                    "Saved ${written.size} file(s) to $dir"
                )
            }
            return true
        }
        return false
    }

    /** Returns `true` when the caller may proceed with the write —
     *  either the file doesn't exist, [silentlyOverwrite] is true, or
     *  the user confirmed.  Returns `false` only when the user
     *  declined a confirmation prompt. */
    private fun confirmOverwriteIfNeeded(path: Path, silentlyOverwrite: Boolean): Boolean {
        if (!Files.exists(path)) return true
        if (silentlyOverwrite) return true
        val choice = JOptionPane.showConfirmDialog(
            this,
            "${path.fileName} already exists.\nOverwrite?",
            "File Exists",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return choice == JOptionPane.YES_OPTION
    }

    // ── Clear-all ──────────────────────────────────────────────────────────

    private fun handleClearAll() {
        val list = controller.recentRegressionFits.value
        if (list.isEmpty()) return
        val unsaved = list.count { it.savedPaths.isEmpty() }
        if (unsaved > 0) {
            val choice = JOptionPane.showConfirmDialog(
                this,
                "$unsaved unsaved fit(s) will be lost.  Continue?",
                "Clear Recent Fits",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (choice != JOptionPane.YES_OPTION) return
        }
        controller.clearRegressionFits()
    }

    // ── Path / naming helpers ──────────────────────────────────────────────

    private fun reportsDir(): Path = controller.appWorkspace
        .resolve("output")
        .resolve(sanitizeAnalysisName(controller.outputConfig.value.analysisName))
        .resolve("reports")

    private fun ensureReportsDir(): Path? = try {
        reportsDir().also { Files.createDirectories(it) }
    } catch (t: Throwable) {
        notifier.error(
            "Could not create reports directory: ${t.message ?: t::class.simpleName}"
        )
        null
    }

    private fun defaultStem(record: RegressionFitRecord): String =
        "regression-${sanitizeForFilename(record.response)}-" +
            TS_FORMATTER.format(record.timestamp)

    private fun defaultTitle(record: RegressionFitRecord): String =
        "Regression Analysis — ${record.response}"

    private fun withSelectedRecord(action: (Int, RegressionFitRecord) -> Unit) {
        val sel = recentTable.selectedRow
        val record = controller.recentRegressionFits.value.getOrNull(sel) ?: return
        SwingUtilities.invokeLater { action(sel, record) }
    }

    // ── toReport with section selection ────────────────────────────────────

    /** Compose a [ReportNode.Document] including only the sections
     *  the user asked for.  Delegates to the existing
     *  `ksl.utilities.io.report.extensions` DSL — no new KSLCore
     *  surface required.  An empty section set falls back to
     *  summary-only (rather than an empty document) for robustness;
     *  the dialog disables Save when nothing is selected, so this
     *  branch is defensive. */
    private fun RegressionResultsIfc.toReportWithSections(
        title: String,
        confidenceLevel: Double,
        sections: ReportSections
    ): ReportNode.Document {
        val rr = this
        val block: ReportBuilder.() -> Unit = {
            if (sections.summary) regressionSummary(rr, confidenceLevel = confidenceLevel)
            if (sections.parameters) regressionParameters(rr, confidenceLevel = confidenceLevel)
            if (sections.diagnostics) regressionDiagnostics(rr)
            if (!sections.summary && !sections.parameters && !sections.diagnostics) {
                regressionSummary(rr, confidenceLevel = confidenceLevel)
            }
        }
        return report(title, block)
    }

    // ── Recent-fits table model ────────────────────────────────────────────

    private inner class RecentFitsTableModel : AbstractTableModel() {
        private var rows: List<RegressionFitRecord> = emptyList()
        fun replace(newRows: List<RegressionFitRecord>) {
            rows = newRows
            fireTableDataChanged()
        }
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 5
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getColumnName(column: Int): String = when (column) {
            COL_TIME -> "Time"
            COL_RESPONSE -> "Response"
            COL_TERMS -> "Terms"
            COL_LEVELS -> "Levels"
            COL_STATUS -> "Status"
            else -> ""
        }
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                COL_TIME -> TIME_FORMATTER.format(r.timestamp)
                COL_RESPONSE -> r.response
                COL_TERMS -> r.modelExpression
                COL_LEVELS -> if (r.coded) "coded" else "natural"
                COL_STATUS -> if (r.savedPaths.isEmpty()) "unsaved" else "✓ saved"
                else -> ""
            }
        }
    }

    // ── Save options ───────────────────────────────────────────────────────

    /** Section-selection flags for [toReportWithSections].  Defaults
     *  to all-on, matching `RegressionResultsIfc.toReport()`'s default. */
    internal data class ReportSections(
        val summary: Boolean,
        val parameters: Boolean,
        val diagnostics: Boolean
    ) {
        val anySelected: Boolean
            get() = summary || parameters || diagnostics

        companion object {
            val ALL = ReportSections(summary = true, parameters = true, diagnostics = true)
        }
    }

    /** Result of [SaveRegressionReportDialog.prompt] (interactive
     *  path) or constructed directly by [saveAllUnsavedRegressionFits]
     *  (batch path). */
    internal data class SaveOptions(
        val stem: String,
        val formats: Set<ReportFormat>,
        val sections: ReportSections,
        val includeCoefficientCsv: Boolean,
        val includeResidualsCsv: Boolean,
        /** When true, [executeSave] skips per-file overwrite prompts.
         *  Set by the Save-all batch path; the interactive dialog
         *  always leaves this false so the user sees one prompt per
         *  collision. */
        val overwriteExisting: Boolean
    )

    companion object {
        private const val CARD_NO_MODEL = "no-model"
        private const val CARD_NO_FACTORS = "no-factors"
        private const val CARD_NO_RESULTS = "no-results"
        private const val CARD_POPULATED = "populated"

        private const val COL_TIME = 0
        private const val COL_RESPONSE = 1
        private const val COL_TERMS = 2
        private const val COL_LEVELS = 3
        private const val COL_STATUS = 4

        private val STATUS_GREY = Color(0x77, 0x77, 0x77)
        private val STATUS_AMBER = Color(0xB8, 0x86, 0x0B)
        private val STATUS_GREEN = Color(0x2E, 0x7D, 0x32)

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val TS_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        private fun sanitizeForFilename(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "response" }
    }
}

/**
 *  Modal dialog for the Regression tab's interactive Save action.
 *  Collects:
 *  - a filename stem (extensions appended per selected format),
 *  - one or more report formats (HTML / Markdown / Text),
 *  - which report sections to include (Summary / Parameters /
 *    Diagnostics) — mapped 1:1 to the existing
 *    `RegressionReportExtensions` DSL,
 *  - whether to also export the coefficient table and the residual
 *    table as CSV files.
 *
 *  Save is disabled until at least one format is selected and at
 *  least one section is selected.  The Cancel and close buttons
 *  return `null`; Save returns the populated
 *  [RegressionTabPanel.SaveOptions].
 */
internal object SaveRegressionReportDialog {

    fun prompt(
        owner: java.awt.Window?,
        defaultStem: String,
        folder: Path,
        initialFormats: Set<ReportFormat>
    ): RegressionTabPanel.SaveOptions? {
        val dialog = JDialog(owner, "Save Regression Report", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        val stemField = JTextField(defaultStem, 28)
        val htmlBox = JCheckBox("HTML", ReportFormat.HTML in initialFormats)
        val markdownBox = JCheckBox("Markdown", ReportFormat.MARKDOWN in initialFormats)
        val textBox = JCheckBox("Text", ReportFormat.TEXT in initialFormats)
        val summaryBox = JCheckBox("Summary", true)
        val parametersBox = JCheckBox("Parameters", true)
        val diagnosticsBox = JCheckBox("Diagnostics", true)
        val coefficientsCsvBox = JCheckBox("Coefficients (CSV)", false).apply {
            toolTipText = "Write RegressionResultsIfc.parameterResults(level) as a CSV alongside the report."
        }
        val residualsCsvBox = JCheckBox("Residuals (CSV)", false).apply {
            toolTipText = "Write RegressionResultsIfc.residualsAsDataFrame() (response, predicted, " +
                "residuals, standardized, studentized, hat-diagonal, Cook's distance) as a CSV " +
                "alongside the report."
        }
        val saveButton = JButton("Save")
        val cancelButton = JButton("Cancel")

        var result: RegressionTabPanel.SaveOptions? = null

        val refreshSaveEnabled = {
            val anyFormat = htmlBox.isSelected || markdownBox.isSelected || textBox.isSelected
            val anySection = summaryBox.isSelected || parametersBox.isSelected || diagnosticsBox.isSelected
            val stemOk = stemField.text.trim().isNotEmpty()
            saveButton.isEnabled = anyFormat && anySection && stemOk
        }
        for (b in listOf(htmlBox, markdownBox, textBox, summaryBox, parametersBox, diagnosticsBox)) {
            b.addActionListener { refreshSaveEnabled() }
        }
        stemField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshSaveEnabled()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshSaveEnabled()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshSaveEnabled()
        })

        saveButton.addActionListener {
            val stem = stemField.text.trim()
            val formats = buildSet {
                if (htmlBox.isSelected) add(ReportFormat.HTML)
                if (markdownBox.isSelected) add(ReportFormat.MARKDOWN)
                if (textBox.isSelected) add(ReportFormat.TEXT)
            }
            val sections = RegressionTabPanel.ReportSections(
                summary = summaryBox.isSelected,
                parameters = parametersBox.isSelected,
                diagnostics = diagnosticsBox.isSelected
            )
            result = RegressionTabPanel.SaveOptions(
                stem = stem,
                formats = formats,
                sections = sections,
                includeCoefficientCsv = coefficientsCsvBox.isSelected,
                includeResidualsCsv = residualsCsvBox.isSelected,
                overwriteExisting = false
            )
            dialog.dispose()
        }
        cancelButton.addActionListener { dialog.dispose() }

        val body = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        gbc.gridx = 0; gbc.gridy = 0; body.add(JLabel("Filename stem:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        body.add(stemField, gbc)
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.gridx = 1; gbc.gridy = 1
        gbc.gridwidth = 3
        body.add(
            JLabel("Extensions appended per selected format.").apply {
                foreground = Color(0x88, 0x88, 0x88)
                font = font.deriveFont(font.size * 0.9f)
            },
            gbc
        )
        gbc.gridwidth = 1

        gbc.gridx = 0; gbc.gridy = 2; body.add(JLabel("Folder:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        body.add(
            JLabel(folder.toString()).apply {
                foreground = Color(0x55, 0x55, 0x55)
                font = font.deriveFont(font.size * 0.95f)
            },
            gbc
        )
        gbc.gridwidth = 1

        gbc.gridx = 0; gbc.gridy = 3; body.add(JLabel("Report formats:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        val formatsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(htmlBox); add(Box.createHorizontalStrut(8))
            add(markdownBox); add(Box.createHorizontalStrut(8))
            add(textBox)
        }
        body.add(formatsRow, gbc)
        gbc.gridwidth = 1

        gbc.gridx = 0; gbc.gridy = 4; body.add(JLabel("Report sections:"), gbc)
        gbc.gridx = 1; gbc.gridy = 4; body.add(summaryBox, gbc)
        gbc.gridx = 1; gbc.gridy = 5; body.add(parametersBox, gbc)
        gbc.gridx = 1; gbc.gridy = 6; body.add(diagnosticsBox, gbc)

        gbc.gridx = 0; gbc.gridy = 7; body.add(JLabel("Data exports:"), gbc)
        gbc.gridx = 1; gbc.gridy = 7; body.add(coefficientsCsvBox, gbc)
        gbc.gridx = 1; gbc.gridy = 8; body.add(residualsCsvBox, gbc)

        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(8, 12, 12, 12)
            add(Box.createHorizontalGlue())
            add(cancelButton)
            add(Box.createHorizontalStrut(8))
            add(saveButton)
        }

        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(body, BorderLayout.CENTER)
        dialog.contentPane.add(buttonRow, BorderLayout.SOUTH)
        dialog.getRootPane().defaultButton = saveButton
        refreshSaveEnabled()
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        return result
    }
}
