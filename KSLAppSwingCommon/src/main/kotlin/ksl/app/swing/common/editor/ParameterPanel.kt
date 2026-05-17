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

package ksl.app.swing.common.editor

import kotlinx.coroutines.launch
import ksl.app.config.ExperimentRunOverrides
import ksl.app.swing.common.overridefield.BooleanTriStateOverrideField
import ksl.app.swing.common.overridefield.DoubleOverrideField
import ksl.app.swing.common.overridefield.IntegerOverrideField
import ksl.app.swing.common.overridefield.SectionHeaderWithStatus
import ksl.app.swing.common.validation.FieldErrorMarker
import ksl.app.swing.common.validation.WidgetPathRegistry
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Run-parameters editor panel, reusable across the Single app and
 * the Scenario app's per-scenario editor window.  Two groupings:
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
 * validation findings from [ConfigurationEditorState.validationBus]
 * decorate the inline field automatically — notably the
 * infinite-horizon warning on `lengthOfReplication`.  [setEnabled]
 * propagates to every wrapped field so the panel can be driven
 * read-only during a run.
 *
 * @param state the host [ConfigurationEditorState] this panel binds to.
 */
class ParameterPanel(
    private val state: ConfigurationEditorState
) : JPanel(BorderLayout()) {

    private val defaults get() = state.modelDefaults
    private val bus get() = state.validationBus
    private val scope get() = state.edtScope
    private val registry: WidgetPathRegistry = WidgetPathRegistry()
    private val managedFields: MutableList<JComponent> = mutableListOf()

    /**
     * Per-field "set value from override" updaters, keyed in the
     * order the corresponding fields appear in the panel.  Built up
     * during [commonRows]/[advancedSection] and replayed by
     * [syncFromController] each time `state.runOverrides`
     * emits a new value.  Lets external mutations (Reset to Model
     * Defaults, Open Configuration, programmatic resets) update the
     * displayed text without rebuilding the panel.
     */
    private val fieldSyncers: MutableList<(ExperimentRunOverrides) -> Unit> = mutableListOf()

    init {
        border = BorderFactory.createEmptyBorder(OUTER_PADDING, OUTER_PADDING + 8, OUTER_PADDING, OUTER_PADDING + 8)
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        body.add(commonRows())
        body.add(Box.createVerticalStrut(SECTION_GAP))
        body.add(advancedSection())
        add(body, BorderLayout.NORTH)

        // Subscribe to state.runOverrides so external state
        // changes (resetConfiguration, loadConfiguration) propagate
        // into the displayed text.  Without this, fields are
        // write-only: edits push into the controller but
        // controller-side resets don't push back.  Safe against
        // feedback: the field setters fire onValueChange whose
        // updateRunOverride callback is a no-op when the new value
        // matches the controller's current value (see
        // SingleAppController.updateRunOverride).
        scope.launch {
            state.runOverrides.collect { overrides ->
                for (sync in fieldSyncers) sync(overrides)
            }
        }
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
                onChange = { v -> state.updateRunOverride { it.copy(numberOfReplications = v) } },
                read = { it.numberOfReplications }
            )
        ))
        panel.add(labeledRow(
            "Length of replication",
            doubleField(
                modelDefault = defaults.lengthOfReplication,
                path = "scenarios[0].runOverrides.lengthOfReplication",
                onChange = { v -> state.updateRunOverride { it.copy(lengthOfReplication = v) } },
                read = { it.lengthOfReplication }
            )
        ))
        panel.add(labeledRow(
            "Length of replication warm-up",
            doubleField(
                modelDefault = defaults.lengthOfReplicationWarmUp,
                path = "scenarios[0].runOverrides.lengthOfReplicationWarmUp",
                onChange = { v -> state.updateRunOverride { it.copy(lengthOfReplicationWarmUp = v) } },
                read = { it.lengthOfReplicationWarmUp }
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
                onChange = { v -> state.updateRunOverride { it.copy(antitheticOption = v) } },
                read = { it.antitheticOption }
            )
        ))
        body.add(labeledRow(
            "Reset start-stream option",
            booleanField(
                path = "scenarios[0].runOverrides.resetStartStreamOption",
                onChange = { v -> state.updateRunOverride { it.copy(resetStartStreamOption = v) } },
                read = { it.resetStartStreamOption }
            )
        ))
        body.add(labeledRow(
            "Advance next-sub-stream option",
            booleanField(
                path = "scenarios[0].runOverrides.advanceNextSubStreamOption",
                onChange = { v -> state.updateRunOverride { it.copy(advanceNextSubStreamOption = v) } },
                read = { it.advanceNextSubStreamOption }
            )
        ))
        body.add(labeledRow(
            "Number of stream advances prior to running",
            integerField(
                modelDefault = defaults.numberOfStreamAdvancesPriorToRunning,
                path = "scenarios[0].runOverrides.numberOfStreamAdvancesPriorToRunning",
                onChange = { v -> state.updateRunOverride { it.copy(numberOfStreamAdvancesPriorToRunning = v) } },
                read = { it.numberOfStreamAdvancesPriorToRunning }
            )
        ))
        outer.add(header)
        outer.add(body)
        return outer
    }

    // ── Field helpers ──────────────────────────────────────────────────────

    private fun labeledRow(label: String, field: JComponent): JComponent {
        // Cap the field's preferred + max width so it doesn't stretch to fill
        // the full window width.  Trailing horizontal glue absorbs the rest of
        // the row.  Vertical sizing follows the field's natural preferred height.
        val fieldHeight = field.preferredSize.height.coerceAtLeast(MIN_ROW_HEIGHT)
        field.preferredSize = Dimension(FIELD_WIDTH, fieldHeight)
        field.maximumSize = Dimension(FIELD_WIDTH, fieldHeight)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(ROW_PADDING, 0, ROW_PADDING, 0)
            add(JLabel(label).apply {
                val labelHeight = preferredSize.height.coerceAtLeast(MIN_ROW_HEIGHT)
                preferredSize = Dimension(LABEL_WIDTH, labelHeight)
                maximumSize = Dimension(LABEL_WIDTH, labelHeight)
            })
            add(field)
            add(Box.createHorizontalGlue())
        }
    }

    private fun integerField(
        modelDefault: Int,
        path: String,
        onChange: (Int?) -> Unit,
        read: (ExperimentRunOverrides) -> Int?
    ): JComponent {
        val raw = IntegerOverrideField(modelDefault = modelDefault, onValueChange = onChange)
        val wrapped = FieldErrorMarker.attach(raw, path, bus, scope, registry)
        managedFields.add(wrapped)
        fieldSyncers.add { overrides -> raw.value = read(overrides) }
        return wrapped
    }

    private fun doubleField(
        modelDefault: Double,
        path: String,
        onChange: (Double?) -> Unit,
        read: (ExperimentRunOverrides) -> Double?
    ): JComponent {
        val raw = DoubleOverrideField(modelDefault = modelDefault, onValueChange = onChange)
        val wrapped = FieldErrorMarker.attach(raw, path, bus, scope, registry)
        managedFields.add(wrapped)
        fieldSyncers.add { overrides -> raw.value = read(overrides) }
        return wrapped
    }

    private fun booleanField(
        path: String,
        onChange: (Boolean?) -> Unit,
        read: (ExperimentRunOverrides) -> Boolean?
    ): JComponent {
        val raw = BooleanTriStateOverrideField(onValueChange = onChange)
        val wrapped = FieldErrorMarker.attach(raw, path, bus, scope, registry)
        managedFields.add(wrapped)
        fieldSyncers.add { overrides -> raw.value = read(overrides) }
        return wrapped
    }

    companion object {
        private const val OUTER_PADDING: Int = 4
        private const val ROW_PADDING: Int = 1
        private const val LABEL_WIDTH: Int = 220
        private const val FIELD_WIDTH: Int = 280
        private const val MIN_ROW_HEIGHT: Int = 24
        private const val SECTION_GAP: Int = 6
    }
}
