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
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.controls.experiments.LinearModel
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.showInBrowser
import ksl.utilities.io.report.writeHtml
import ksl.utilities.io.report.writeMarkdown
import ksl.utilities.io.report.writeText
import ksl.utilities.statistic.RegressionResultsIfc
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
import javax.swing.JComboBox
import javax.swing.JLabel
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
 *  + `RegressionResultsIfc.toReport(...)` pipeline; the user reads
 *  every actual result in their system browser, not in Swing.
 *
 *  Lifecycle:
 *  - Empty-state cards for "no model", "no experiment instance"
 *    (no run yet) and "no results in current run" (factor changes
 *    invalidated the prior result).
 *  - When a run has produced results, the populated card shows:
 *    a model spec form, a fit button, a status chip, last-fit Open
 *    + Save buttons, and a Recent Fits table backed by
 *    [ExperimentAppController.recentRegressionFits].
 *  - R1 lifecycle clears the recent-fits list on Simulate; the
 *    unsaved-fits prompt in
 *    `ExperimentAppFrame.handleSimulate` warns the user beforehand.
 *
 *  Reports are written under
 *  `<workspace>/output/<analysisName>/reports/`, in whichever formats
 *  are enabled in `controller.outputConfig.value.reports`.  Same
 *  directory the Reports tab scans, so saved regression reports
 *  appear alongside the experiment summaries automatically.
 */
class RegressionTabPanel(
    private val controller: ExperimentAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
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
        toolTipText = "Regenerate the HTML report from the most recent fit and open it in your " +
            "system browser.  Works whether or not the fit has been saved to disk."
    }
    private val saveLastFitButton = JButton("Save report to disk…").apply {
        isVisible = false
        toolTipText = "Materialise the most recent fit to <workspace>/output/<analysisName>/reports/, " +
            "using the formats enabled in the document's output options."
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
        toolTipText = "Materialise every unsaved fit in the list to the reports directory."
        isEnabled = false
    }
    private val clearAllButton = JButton("Clear all").apply {
        toolTipText = "Discard every record from the recent-fits list.  " +
            "Files already saved to disk are NOT removed."
        isEnabled = false
    }
    private val openRowButton = JButton("Open").apply {
        toolTipText = "Open the selected row's regression report in your system browser."
        isEnabled = false
    }
    private val saveRowButton = JButton("Save").apply { isEnabled = false }
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

        // Row 0: response
        gbc.gridx = 0; gbc.gridy = 0; panel.add(JLabel("Response:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(responseCombo, gbc)
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        // Row 1: levels (coded/natural) + confidence
        gbc.gridx = 0; gbc.gridy = 1; panel.add(JLabel("Levels:"), gbc)
        val levelsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(codedRadio); add(Box.createHorizontalStrut(8)); add(naturalRadio)
        }
        gbc.gridx = 1; panel.add(levelsRow, gbc)
        gbc.gridx = 2; panel.add(JLabel("Confidence:"), gbc)
        gbc.gridx = 3; panel.add(confidenceCombo, gbc)

        // Row 2-5: model presets
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

        // Row 6: expression preview
        gbc.gridx = 0; gbc.gridy = 6; panel.add(JLabel("Expression:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 3
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(expressionLabel, gbc)
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        // Row 7: fit button
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
        // Status column gets a centered renderer
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
        openRowButton.addActionListener { withSelectedRecord { i, r -> openRecord(r, i) } }
        saveRowButton.addActionListener { withSelectedRecord { i, r -> saveRecord(i, r) } }
        removeRowButton.addActionListener {
            val sel = recentTable.selectedRow
            if (sel >= 0) controller.removeRegressionFit(sel)
        }
        saveAllUnsavedButton.addActionListener { saveAllUnsavedRegressionFits() }
        clearAllButton.addActionListener { handleClearAll() }
    }

    private fun wireControllerSubscribers() {
        controller.edtScope.launch {
            controller.modelReference.collect { refreshCard() }
        }
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
        controller.edtScope.launch {
            controller.lastResult.collect { refreshCard() }
        }
        controller.edtScope.launch {
            controller.runningFlow.collect { refreshFitEnablement() }
        }
        controller.edtScope.launch {
            controller.editedSinceLastSim.collect { refreshStatus() }
        }
        controller.edtScope.launch {
            controller.lastRegressionFit.collect { refreshStatus() }
        }
        controller.edtScope.launch {
            controller.recentRegressionFits.collect { list ->
                recentTableModel.replace(list)
                refreshRecentHeader()
                refreshRowActionEnablement()
            }
        }
    }

    // ── State refresh ──────────────────────────────────────────────────────

    private fun refreshCard() {
        val card = when {
            controller.modelReference.value == null -> CARD_NO_MODEL
            controller.factors.value.isEmpty() -> CARD_NO_FACTORS
            // Allow the user to author + edit the spec form even before a
            // run completes — but Fit is gated on experimentInstance,
            // so route through the "no results" card when there's no
            // run yet.  Once a run completes, switch to populated.
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

    private fun refreshStatus() {
        val fit = controller.lastRegressionFit.value
        val stale = controller.editedSinceLastSim.value && fit != null
        when {
            fit == null -> {
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
                val mostRecent = controller.recentRegressionFits.value.firstOrNull()
                val summary = if (mostRecent != null) {
                    val units = if (mostRecent.coded) "coded" else "natural"
                    val alpha = "%.2f".format(1.0 - mostRecent.confidenceLevel)
                    "✓ Fit succeeded — ${mostRecent.response}, $units, α = $alpha"
                } else {
                    "✓ Fit succeeded"
                }
                statusLabel.text = summary
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
            "Materialise the $unsavedCount unsaved fit(s) to the reports directory."
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
        saveRowButton.text = if (record?.savedPaths?.isNotEmpty() == true) "Save again…" else "Save"
        saveRowButton.toolTipText = when {
            record == null -> null
            record.savedPaths.isEmpty() -> "Materialise this fit to the reports directory."
            else -> "Save again to a new timestamped file (previous saves are kept)."
        }
    }

    // ── LinearModel construction ───────────────────────────────────────────

    /** Build the LinearModel implied by the current form state, or
     *  `null` when invalid (no factors, or Custom selected with
     *  unparseable / invalid terms).  Read by both the Expression
     *  preview and the Fit handler so they agree on what would be
     *  submitted. */
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

    // ── Actions ────────────────────────────────────────────────────────────

    private fun handleFit() {
        val response = responseCombo.selectedItem as? String ?: run {
            onMessage("Pick a response before fitting.", NotificationSeverity.WARNING); return
        }
        val model = buildLinearModelOrNull() ?: run {
            onMessage(
                "Model is invalid — check the custom-terms expression.",
                NotificationSeverity.WARNING
            )
            return
        }
        val coded = codedRadio.isSelected
        val level = (confidenceCombo.selectedItem as? Double) ?: 0.95
        try {
            val fit = controller.fitRegression(response, model, coded, level)
            if (fit == null) {
                onMessage(
                    "No experiment results retained — run the experiment first.",
                    NotificationSeverity.WARNING
                )
            }
            // refreshStatus + recent-list collector handle the visual update.
        } catch (t: Throwable) {
            onMessage(
                "Regression failed: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
        }
    }

    private fun handleOpenLastFit() {
        val record = controller.recentRegressionFits.value.firstOrNull() ?: return
        openRecord(record, 0)
    }

    private fun handleSaveLastFit() {
        val record = controller.recentRegressionFits.value.firstOrNull() ?: return
        saveRecord(0, record)
    }

    /**
     *  Materialise every unsaved record in [ExperimentAppController.recentRegressionFits]
     *  to the reports directory.  Called by both the in-tab
     *  "Save all unsaved" button and the frame's Simulate-prompt
     *  "Save all and simulate" choice, so the two paths produce
     *  identical files and surface identical toasts.
     */
    fun saveAllUnsavedRegressionFits() {
        val list = controller.recentRegressionFits.value
        var saved = 0
        for ((i, record) in list.withIndex()) {
            if (record.savedPaths.isNotEmpty()) continue
            if (saveRecord(i, record, suppressToast = true)) saved++
        }
        if (saved > 0) {
            onMessage("Saved $saved regression report(s).", NotificationSeverity.INFO)
        } else {
            onMessage("Nothing to save — all fits already on disk.", NotificationSeverity.INFO)
        }
    }

    private fun handleClearAll() {
        val list = controller.recentRegressionFits.value
        if (list.isEmpty()) return
        val unsaved = list.count { it.savedPaths.isEmpty() }
        if (unsaved > 0) {
            val choice = javax.swing.JOptionPane.showConfirmDialog(
                this,
                "$unsaved unsaved fit(s) will be lost.  Continue?",
                "Clear Recent Fits",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE
            )
            if (choice != javax.swing.JOptionPane.YES_OPTION) return
        }
        controller.clearRegressionFits()
    }

    private fun openRecord(record: RegressionFitRecord, indexForTitle: Int) {
        try {
            val title = "Regression Analysis — ${record.response}"
            val ctx = RenderContext(
                outputDir = reportsDirOrTemp(),
                confidenceLevel = record.confidenceLevel
            )
            record.fit
                .toReport(title = title, confidenceLevel = record.confidenceLevel)
                .showInBrowser(ctx = ctx)
        } catch (t: Throwable) {
            onMessage(
                "Could not open report: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
        }
    }

    /**
     *  Write the fit's report into the reports directory in every
     *  format the document has enabled.  Returns true when at least
     *  one file was written successfully.  Surfaces a toast on
     *  success unless [suppressToast] (used by the batch
     *  Save-All-Unsaved path so the user gets one summary toast
     *  instead of N per-row toasts).
     */
    private fun saveRecord(
        index: Int,
        record: RegressionFitRecord,
        suppressToast: Boolean = false
    ): Boolean {
        val dir = ensureReportsDir() ?: return false
        val formats = controller.outputConfig.value.reports.ifEmpty { setOf(ReportFormat.HTML) }
        val stem = "regression-${sanitizeForFilename(record.response)}-${TS_FORMATTER.format(record.timestamp)}"
        val doc = record.fit.toReport(
            title = "Regression Analysis — ${record.response}",
            confidenceLevel = record.confidenceLevel
        )
        val ctx = RenderContext(outputDir = dir, confidenceLevel = record.confidenceLevel)
        val written = mutableListOf<Path>()
        try {
            for (fmt in formats) {
                val ext = when (fmt) {
                    ReportFormat.HTML -> "html"
                    ReportFormat.MARKDOWN -> "md"
                    ReportFormat.TEXT -> "txt"
                }
                val path = dir.resolve("$stem.$ext")
                val file = when (fmt) {
                    ReportFormat.HTML -> doc.writeHtml(path = path, ctx = ctx)
                    ReportFormat.MARKDOWN -> doc.writeMarkdown(path = path, ctx = ctx)
                    ReportFormat.TEXT -> doc.writeText(path = path, ctx = ctx)
                }
                written.add(file.toPath())
            }
        } catch (t: Throwable) {
            onMessage(
                "Could not save report: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
            return false
        }
        if (written.isNotEmpty()) {
            controller.markRegressionFitSaved(index, written)
            if (!suppressToast) {
                onMessage("Saved ${written.size} report file(s) to $dir", NotificationSeverity.INFO)
            }
            return true
        }
        return false
    }

    // ── Path helpers ───────────────────────────────────────────────────────

    private fun reportsDir(): Path = controller.appWorkspace
        .resolve("output")
        .resolve(sanitizeAnalysisName(controller.outputConfig.value.analysisName))
        .resolve("reports")

    private fun ensureReportsDir(): Path? = try {
        reportsDir().also { Files.createDirectories(it) }
    } catch (t: Throwable) {
        onMessage(
            "Could not create reports directory: ${t.message ?: t::class.simpleName}",
            NotificationSeverity.ERROR
        )
        null
    }

    /** Output directory for the in-browser preview path.  Falls back
     *  to the user's temp dir if the reports directory can't be
     *  created — we don't want a transient path failure to block
     *  Open (the user often wants the preview *because* they
     *  haven't saved yet). */
    private fun reportsDirOrTemp(): Path =
        try {
            reportsDir().also { Files.createDirectories(it) }
        } catch (_: Throwable) {
            System.getProperty("java.io.tmpdir")?.let { java.nio.file.Paths.get(it) }
                ?: reportsDir()
        }

    private fun withSelectedRecord(action: (Int, RegressionFitRecord) -> Unit) {
        val sel = recentTable.selectedRow
        val record = controller.recentRegressionFits.value.getOrNull(sel) ?: return
        SwingUtilities.invokeLater { action(sel, record) }
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
