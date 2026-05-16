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

package ksl.app.swing.single.framework.defaults

import ksl.app.config.ExperimentRunOverrides
import ksl.app.swing.common.overridefield.BooleanTriStateOverrideField
import ksl.app.swing.common.overridefield.DoubleOverrideField
import ksl.app.swing.common.overridefield.IntegerOverrideField
import ksl.app.swing.common.overridefield.SectionHeaderWithStatus
import ksl.app.swing.common.validation.FieldErrorMarker
import ksl.app.swing.common.validation.WidgetPathRegistry
import ksl.app.swing.single.framework.SingleAppController
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
 * workflow-single.md §7.  Hosts a single *Run Parameters*
 * collapsible section with three subgroups:
 *
 *  - **Common** (always visible) — number of replications, length
 *    of replication, length of replication warm-up.
 *  - **Advanced → Stream and variance reduction** (collapsed by
 *    default) — antithetic option, reset start stream, advance
 *    next sub-stream, number of stream advances prior to running.
 *  - **Advanced → Replication mechanics** (collapsed) — number of
 *    chunks, starting replication id, replication initialization
 *    option, garbage-collect-after-replication flag.
 *
 * Omits `maximumAllowedExecutionTimePerReplication` per the
 * project's working decision that the field is not used in the
 * apps.
 *
 * Each field is an override widget showing the model's default as
 * muted placeholder when the user hasn't overridden.  Edits call
 * `controller.updateRunOverride(...)`.  Every field is wrapped by
 * `FieldErrorMarker.attach` so a future
 * `RunConfigurationValidator` hookup can decorate fields without
 * panel changes.  [setEnabled] propagates to every field so the
 * panel can be driven read-only during a run.
 *
 * @param controller the owning [SingleAppController]; the panel
 *   reads model defaults from it, registers widgets in its bus,
 *   and propagates user edits to its [SingleAppController.runOverrides].
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
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val header = SectionHeaderWithStatus(
            title = "Run Parameters",
            pathPrefix = "scenarios[0].runOverrides",
            bus = bus,
            scope = scope,
            initiallyExpanded = true,
            onToggle = { expanded -> body.isVisible = expanded; revalidate(); repaint() }
        )
        body.add(commonGroup())
        body.add(Box.createVerticalStrut(8))
        body.add(advancedStreamGroup())
        body.add(Box.createVerticalStrut(8))
        body.add(advancedMechanicsGroup())

        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (field in managedFields) field.isEnabled = enabled
    }

    // ── Group: Common ──────────────────────────────────────────────────────

    private fun commonGroup(): JComponent = subgroup(
        title = "Common",
        initiallyExpanded = true,
        rows = listOf(
            "Number of replications" to integerField(
                modelDefault = defaults.numberOfReplications,
                path = "scenarios[0].runOverrides.numberOfReplications",
                onChange = { v -> controller.updateRunOverride { it.copy(numberOfReplications = v) } }
            ),
            "Length of replication" to doubleField(
                modelDefault = defaults.lengthOfReplication,
                path = "scenarios[0].runOverrides.lengthOfReplication",
                onChange = { v -> controller.updateRunOverride { it.copy(lengthOfReplication = v) } }
            ),
            "Length of replication warm-up" to doubleField(
                modelDefault = defaults.lengthOfReplicationWarmUp,
                path = "scenarios[0].runOverrides.lengthOfReplicationWarmUp",
                onChange = { v -> controller.updateRunOverride { it.copy(lengthOfReplicationWarmUp = v) } }
            )
        )
    )

    // ── Group: Advanced → Stream and variance reduction ────────────────────

    private fun advancedStreamGroup(): JComponent = subgroup(
        title = "Advanced → Stream and variance reduction",
        initiallyExpanded = false,
        rows = listOf(
            "Antithetic option" to booleanField(
                path = "scenarios[0].runOverrides.antitheticOption",
                onChange = { v -> controller.updateRunOverride { it.copy(antitheticOption = v) } }
            ),
            "Reset start-stream option" to booleanField(
                path = "scenarios[0].runOverrides.resetStartStreamOption",
                onChange = { v -> controller.updateRunOverride { it.copy(resetStartStreamOption = v) } }
            ),
            "Advance next-sub-stream option" to booleanField(
                path = "scenarios[0].runOverrides.advanceNextSubStreamOption",
                onChange = { v -> controller.updateRunOverride { it.copy(advanceNextSubStreamOption = v) } }
            ),
            "Number of stream advances prior to running" to integerField(
                modelDefault = defaults.numberOfStreamAdvancesPriorToRunning,
                path = "scenarios[0].runOverrides.numberOfStreamAdvancesPriorToRunning",
                onChange = { v -> controller.updateRunOverride { it.copy(numberOfStreamAdvancesPriorToRunning = v) } }
            )
        )
    )

    // ── Group: Advanced → Replication mechanics ────────────────────────────

    private fun advancedMechanicsGroup(): JComponent = subgroup(
        title = "Advanced → Replication mechanics",
        initiallyExpanded = false,
        rows = listOf(
            "Number of chunks" to integerField(
                modelDefault = defaults.numChunks,
                path = "scenarios[0].runOverrides.numChunks",
                onChange = { v -> controller.updateRunOverride { it.copy(numChunks = v) } }
            ),
            "Starting replication id" to integerField(
                modelDefault = defaults.startingRepId,
                path = "scenarios[0].runOverrides.startingRepId",
                onChange = { v -> controller.updateRunOverride { it.copy(startingRepId = v) } }
            ),
            "Replication initialization option" to booleanField(
                path = "scenarios[0].runOverrides.replicationInitializationOption",
                onChange = { v -> controller.updateRunOverride { it.copy(replicationInitializationOption = v) } }
            ),
            "Garbage-collect after replication" to booleanField(
                path = "scenarios[0].runOverrides.garbageCollectAfterReplicationFlag",
                onChange = { v -> controller.updateRunOverride { it.copy(garbageCollectAfterReplicationFlag = v) } }
            )
        )
    )

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun subgroup(
        title: String,
        initiallyExpanded: Boolean,
        rows: List<Pair<String, JComponent>>
    ): JComponent {
        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 0, 0)
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = initiallyExpanded
        }
        val header = SectionHeaderWithStatus(
            title = title,
            pathPrefix = "scenarios[0].runOverrides",
            bus = bus,
            scope = scope,
            initiallyExpanded = initiallyExpanded,
            onToggle = { expanded -> body.isVisible = expanded; outer.revalidate(); outer.repaint() }
        )
        for ((label, field) in rows) body.add(labeledRow(label, field))
        outer.add(header)
        outer.add(body)
        return outer
    }

    private fun labeledRow(label: String, field: JComponent): JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        add(JLabel(label).apply { preferredSize = Dimension(260, preferredSize.height) }, BorderLayout.WEST)
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
}
