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

import ksl.app.config.OutputConfig
import ksl.app.config.WelchResponseSpec
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.WindowConstants

/**
 * One editable row in the Welch dialog's response checklist: a response,
 * whether it is selected for capture, and its discretizing interval.
 * Pure data — no Swing — so the dialog's seeding and selection logic can
 * be unit-tested headless via [WelchDialogLogic].
 */
data class WelchRowState(
    val name: String,
    val isTimeWeighted: Boolean,
    val selected: Boolean,
    val interval: Double
)

/**
 * Pure, Swing-free logic backing [WelchConfigDialog].  Separated so every
 * behavior (seeding from config, selection collection, deletion-point
 * mapping, summary text) is unit-testable without constructing a
 * `JDialog`, which throws `HeadlessException` in a headless JVM.
 */
object WelchDialogLogic {

    /** Per-type default discretizing interval: batch size for tally
     *  responses, delta-t for time-weighted responses. */
    fun defaultInterval(isTimeWeighted: Boolean): Double = if (isTimeWeighted) 10.0 else 1.0

    /**
     * One row per probed response.  A response already present in
     * [config]'s `welchResponses` is pre-selected with its stored
     * interval; every other response is unselected with the per-type
     * default interval ([defaultInterval]).  Row order follows
     * [responses] (the probe order).
     */
    fun initialRows(config: OutputConfig, responses: List<ResponseProbe>): List<WelchRowState> {
        val byName = config.welchResponses.associateBy { it.responseName }
        return responses.map { probe ->
            val existing = byName[probe.name]
            WelchRowState(
                name = probe.name,
                isTimeWeighted = probe.isTimeWeighted,
                selected = existing != null,
                interval = existing?.interval ?: defaultInterval(probe.isTimeWeighted)
            )
        }
    }

    /** The selected rows as persisted specs, preserving row order. */
    fun selectedSpecs(rows: List<WelchRowState>): List<WelchResponseSpec> =
        rows.filter { it.selected }.map { WelchResponseSpec(it.name, it.interval) }

    /** Read-only one-liner for the Run Control summary label. */
    fun summary(config: OutputConfig): String =
        if (!config.enableWelchAnalysis || config.welchResponses.isEmpty()) {
            "off"
        } else {
            val n = config.welchResponses.size
            "$n response${if (n == 1) "" else "s"}"
        }
}

/**
 * Modal dialog for configuring Warm-Up Analysis (Welch) capture before a
 * run.  Opened from the *Output Options* section of the Run Control tab.
 *
 * OK-commit semantics: the dialog edits local widget state seeded from the
 * controller's current `OutputConfig`; **OK** pushes the whole selection in
 * one [SingleAppController.applyWelchConfig] call, **Cancel** discards.
 * Mirrors the module's `BundleModelPickerDialog` precedent.
 */
object WelchConfigDialog {

    /** Present the dialog modally.  Must be called on the Swing EDT. */
    fun show(controller: SingleAppController, owner: Window?) {
        WelchConfigDialogImpl(controller, owner).isVisible = true
    }
}

/**
 * The actual `JDialog`.  `internal` (not private) so the headless-guarded
 * smoke test can construct it, drive its widgets, and invoke [onOk]
 * without going through the blocking modal [WelchConfigDialog.show].
 */
internal class WelchConfigDialogImpl(
    private val controller: SingleAppController,
    owner: Window?
) : JDialog(owner, "Configure Warm-Up Analysis (Welch)", ModalityType.APPLICATION_MODAL) {

    private val seedConfig: OutputConfig = controller.outputConfig.value
    private val rowStates: List<WelchRowState> =
        WelchDialogLogic.initialRows(seedConfig, controller.responseSnapshot)

    internal val masterCheckBox = JCheckBox("Capture Welch warm-up data during the run").apply {
        isSelected = seedConfig.enableWelchAnalysis
        addActionListener { syncEnabled() }
    }

    internal val rowChecks: List<JCheckBox> = rowStates.map { JCheckBox(it.name).apply { isSelected = it.selected } }
    private val intervalSpinners: List<JSpinner> = rowStates.map {
        JSpinner(SpinnerNumberModel(it.interval, 0.0, 1_000_000.0, 1.0))
    }

    private val partialSumsCheck = JCheckBox("Partial-sums plot").apply {
        isSelected = seedConfig.welchIncludePartialSums
    }
    private val biasTestCheck = JCheckBox("Initialization bias test (Schruben)").apply {
        isSelected = seedConfig.welchIncludeBiasTest
    }

    internal val autoRenderCheck = JCheckBox("Auto-render Welch report after Simulate").apply {
        isSelected = seedConfig.welchAutoRender
    }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

        contentPane.layout = BorderLayout()
        contentPane.add(buildBody(), BorderLayout.CENTER)
        contentPane.add(buildButtonRow(), BorderLayout.SOUTH)

        syncEnabled()
        pack()
        size = Dimension(560, maxOf(360, size.height))
        setLocationRelativeTo(owner)
    }

    private fun buildBody(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 12, 6, 12)
        }
        body.add(leftRow(masterCheckBox))
        body.add(leftRow(JLabel(
            "<html><i>Check the box above to enable the response and report options below.</i></html>"
        ).apply { foreground = Color(0x66, 0x66, 0x66) }))
        body.add(Box.createVerticalStrut(8))

        body.add(leftRow(boldLabel("Responses to analyze")))
        val rowsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        for (i in rowStates.indices) {
            val typeLabel = if (rowStates[i].isTimeWeighted) "(time-weighted)" else "(tally)"
            rowsPanel.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                add(rowChecks[i])
                add(JLabel(typeLabel).apply { foreground = Color(0x66, 0x66, 0x66) })
                add(Box.createHorizontalStrut(8))
                add(JLabel("interval:"))
                add(intervalSpinners[i].apply { preferredSize = Dimension(90, preferredSize.height) })
            })
        }
        body.add(JScrollPane(rowsPanel).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC))
            preferredSize = Dimension(520, 120)
        })
        body.add(leftRow(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton(object : AbstractAction("Select all") {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = setAllSelected(true)
            }))
            add(JButton(object : AbstractAction("Select none") {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = setAllSelected(false)
            }))
        }))
        body.add(Box.createVerticalStrut(8))

        body.add(leftRow(boldLabel("Optional sections")))
        body.add(leftRow(partialSumsCheck))
        body.add(leftRow(biasTestCheck))
        body.add(Box.createVerticalStrut(8))
        body.add(leftRow(autoRenderCheck))
        body.add(leftRow(JLabel(
            "<html><i>Captured during the run; analyzed afterward. Adds per-observation " +
                "I/O — selecting fewer responses keeps runs fast.</i></html>"
        ).apply { foreground = Color(0x66, 0x66, 0x66) }))
        return body
    }

    private fun buildButtonRow(): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 8)).apply {
        border = BorderFactory.createEmptyBorder(0, 12, 8, 12)
        add(JButton(object : AbstractAction("Cancel") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = onCancel()
        }))
        add(JButton(object : AbstractAction("OK") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = onOk()
        }).also { this@WelchConfigDialogImpl.rootPane.defaultButton = it })
    }

    private fun setAllSelected(selected: Boolean) {
        for (cb in rowChecks) cb.isSelected = selected
    }

    /** Grey out the body when the master toggle is off (the existing idiom). */
    private fun syncEnabled() {
        val on = masterCheckBox.isSelected
        for (i in rowStates.indices) { rowChecks[i].isEnabled = on; intervalSpinners[i].isEnabled = on }
        partialSumsCheck.isEnabled = on
        biasTestCheck.isEnabled = on
        autoRenderCheck.isEnabled = on
    }

    /** Collect current widget state into the controller via one batched call. */
    internal fun onOk() {
        val rows = rowStates.indices.map { i ->
            rowStates[i].copy(
                selected = rowChecks[i].isSelected,
                interval = (intervalSpinners[i].value as Number).toDouble()
            )
        }
        controller.applyWelchConfig(
            enableWelchAnalysis = masterCheckBox.isSelected,
            welchResponses = WelchDialogLogic.selectedSpecs(rows),
            includePartialSums = partialSumsCheck.isSelected,
            includeBiasTest = biasTestCheck.isSelected,
            // Batch-means and an explicit deletion point are not exposed in
            // this dialog: the report omits the batch-means section and uses
            // the MSER-recommended deletion point (-1).
            includeBatchMeans = false,
            deletionPoint = -1,
            autoRender = autoRenderCheck.isSelected
        )
        dispose()
    }

    private fun onCancel() = dispose()

    private fun leftRow(component: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(component)
        }

    private fun boldLabel(text: String): JLabel =
        JLabel(text).apply { font = font.deriveFont(Font.BOLD) }
}
