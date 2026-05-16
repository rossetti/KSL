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

package ksl.app.swing.single.defaults

import ksl.app.swing.common.overridefield.BooleanTriStateOverrideField
import ksl.app.swing.common.overridefield.DoubleOverrideField
import ksl.app.swing.common.overridefield.IntegerOverrideField
import ksl.app.swing.common.overridefield.SectionHeaderWithStatus
import ksl.app.swing.common.validation.FieldErrorMarker
import ksl.app.swing.common.validation.WidgetPathRegistry
import ksl.app.swing.single.SingleAppController
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Default parameter panel for `kslSingleApp(...)` per
 * workflow-single.md §7.  Two groupings:
 *
 *  - **Common** (always visible) — `numberOfReplications`,
 *    `lengthOfReplication`, `lengthOfReplicationWarmUp`.
 *  - **Advanced** (collapsed by default) — `antitheticOption`,
 *    `resetStartStreamOption`, `advanceNextSubStreamOption`,
 *    `numberOfStreamAdvancesPriorToRunning`.  Four stream and
 *    variance-reduction toggles only; the rest of
 *    `ExperimentRunOverrides` is intentionally not surfaced
 *    (`numChunks`, `startingRepId`,
 *    `garbageCollectAfterReplicationFlag`,
 *    `replicationInitializationOption`,
 *    `maximumAllowedExecutionTimePerReplication`) because no
 *    interaction-layer workflow consumes them as analyst-facing
 *    options.  Substrate fields remain so saved configurations
 *    from other tools still round-trip.
 *
 * Each field is wrapped via `FieldErrorMarker.attach` so
 * validation findings from
 * [SingleAppController.validationBus] decorate the inline
 * field automatically — notably the infinite-horizon warning
 * on `lengthOfReplication`.  [setEnabled] propagates to every
 * wrapped field so the panel can be driven read-only during a
 * run.
 *
 * @param controller the owning [SingleAppController].
 */
class DefaultParameterPanel(
    private val controller: SingleAppController
) : JPanel(BorderLayout()) {

    private val defaults get() = controller.modelDefaults
    private val bus get() = controller.validationBus
    private val scope get() = controller.edtScope
    private val registry: WidgetPathRegistry = WidgetPathRegistry()
    private val managedFields: MutableList<JComponent> = mutableListOf()

    init {
        border = BorderFactory.createEmptyBorder(OUTER_PADDING, OUTER_PADDING + 8, OUTER_PADDING, OUTER_PADDING + 8)
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val header = SectionHeaderWithStatus(
            title = "Run Parameters",
            pathPrefix = "scenarios[0].runOverrides",
            bus = bus,
            scope = scope,
            initiallyExpanded = true,
            onToggle = { expanded -> body.isVisible = expanded; revalidate(); repaint() }
        )
        body.add(commonRows())
        body.add(Box.createVerticalStrut(SECTION_GAP))
        body.add(advancedSection())

        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (field in managedFields) field.isEnabled = enabled
    }

    // ── Common rows ────────────────────────────────────────────────────────

    private fun commonRows(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(labeledRow(
            "Number of replications",
            integerField(
                modelDefault = defaults.numberOfReplications,
                path = "scenarios[0].runOverrides.numberOfReplications",
                onChange = { v -> controller.updateRunOverride { it.copy(numberOfReplications = v) } }
            )
        ))
        panel.add(labeledRow(
            "Length of replication",
            doubleField(
                modelDefault = defaults.lengthOfReplication,
                path = "scenarios[0].runOverrides.lengthOfReplication",
                onChange = { v -> controller.updateRunOverride { it.copy(lengthOfReplication = v) } }
            )
        ))
        panel.add(labeledRow(
            "Length of replication warm-up",
            doubleField(
                modelDefault = defaults.lengthOfReplicationWarmUp,
                path = "scenarios[0].runOverrides.lengthOfReplicationWarmUp",
                onChange = { v -> controller.updateRunOverride { it.copy(lengthOfReplicationWarmUp = v) } }
            )
        ))
        return panel
    }

    // ── Advanced section (flat — no subgroups) ─────────────────────────────

    private fun advancedSection(): JComponent {
        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 0, 0)
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
        }
        val header = SectionHeaderWithStatus(
            title = "Advanced",
            pathPrefix = "scenarios[0].runOverrides",
            bus = bus,
            scope = scope,
            initiallyExpanded = false,
            onToggle = { expanded -> body.isVisible = expanded; outer.revalidate(); outer.repaint() }
        )
        body.add(labeledRow(
            "Antithetic option",
            booleanField(
                path = "scenarios[0].runOverrides.antitheticOption",
                onChange = { v -> controller.updateRunOverride { it.copy(antitheticOption = v) } }
            )
        ))
        body.add(labeledRow(
            "Reset start-stream option",
            booleanField(
                path = "scenarios[0].runOverrides.resetStartStreamOption",
                onChange = { v -> controller.updateRunOverride { it.copy(resetStartStreamOption = v) } }
            )
        ))
        body.add(labeledRow(
            "Advance next-sub-stream option",
            booleanField(
                path = "scenarios[0].runOverrides.advanceNextSubStreamOption",
                onChange = { v -> controller.updateRunOverride { it.copy(advanceNextSubStreamOption = v) } }
            )
        ))
        body.add(labeledRow(
            "Number of stream advances prior to running",
            integerField(
                modelDefault = defaults.numberOfStreamAdvancesPriorToRunning,
                path = "scenarios[0].runOverrides.numberOfStreamAdvancesPriorToRunning",
                onChange = { v -> controller.updateRunOverride { it.copy(numberOfStreamAdvancesPriorToRunning = v) } }
            )
        ))
        outer.add(header)
        outer.add(body)
        return outer
    }

    // ── Field helpers ──────────────────────────────────────────────────────

    private fun labeledRow(label: String, field: JComponent): JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(ROW_PADDING, 0, ROW_PADDING, 0)
        add(JLabel(label).apply { preferredSize = Dimension(LABEL_WIDTH, preferredSize.height) }, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
    }

    private fun integerField(modelDefault: Int, path: String, onChange: (Int?) -> Unit): JComponent {
        val raw = IntegerOverrideField(modelDefault = modelDefault, onValueChange = onChange)
        val wrapped = FieldErrorMarker.attach(raw, path, bus, scope, registry)
        managedFields.add(wrapped)
        return wrapped
    }

    private fun doubleField(modelDefault: Double, path: String, onChange: (Double?) -> Unit): JComponent {
        val raw = DoubleOverrideField(modelDefault = modelDefault, onValueChange = onChange)
        val wrapped = FieldErrorMarker.attach(raw, path, bus, scope, registry)
        managedFields.add(wrapped)
        return wrapped
    }

    private fun booleanField(path: String, onChange: (Boolean?) -> Unit): JComponent {
        val raw = BooleanTriStateOverrideField(onValueChange = onChange)
        val wrapped = FieldErrorMarker.attach(raw, path, bus, scope, registry)
        managedFields.add(wrapped)
        return wrapped
    }

    companion object {
        private const val OUTER_PADDING: Int = 4
        private const val ROW_PADDING: Int = 1
        private const val LABEL_WIDTH: Int = 180
        private const val SECTION_GAP: Int = 6
    }
}
