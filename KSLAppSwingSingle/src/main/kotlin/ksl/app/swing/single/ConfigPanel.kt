package ksl.app.swing.single

import ksl.controls.experiments.ExperimentRunParameters
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Form panel for editing the experiment-run parameters of the selected model.
 *
 * Edits are propagated to the view model via [onChanged].  The panel is
 * driven by [renderRunParameters] (called when the model selection changes
 * or after a successful run), so the panel itself does not own the source of
 * truth — the view model does.
 *
 * Validation of bound values is enforced by [ExperimentRunParameters]'s
 * `init` block, which throws on invalid combinations; this panel catches the
 * exception and reports an error message rather than propagating an updated
 * snapshot, leaving the previously-good value in place.
 */
internal class ConfigPanel(
    initial: ExperimentRunParameters,
    private val onChanged: (ExperimentRunParameters) -> Unit
) : JPanel(GridBagLayout()) {

    private val experimentNameField = JTextField(initial.experimentName, 24)
    private val numberOfReplicationsField = JTextField(initial.numberOfReplications.toString(), 6)
    private val lengthOfReplicationField = JTextField(initial.lengthOfReplication.toString(), 8)
    private val lengthOfReplicationWarmUpField = JTextField(initial.lengthOfReplicationWarmUp.toString(), 8)
    private val errorLabel = JLabel(" ").apply { foreground = java.awt.Color.RED }

    private var current: ExperimentRunParameters = initial
    private var suppressEvents: Boolean = false

    init {
        border = BorderFactory.createTitledBorder("Run parameters")

        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 0.0
        }

        addRow(gbc, 0, "Experiment name:", experimentNameField)
        addRow(gbc, 1, "Number of replications:", numberOfReplicationsField)
        addRow(gbc, 2, "Length of replication:", lengthOfReplicationField)
        addRow(gbc, 3, "Warm-up length:", lengthOfReplicationWarmUpField)

        // Error message row.
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        add(errorLabel, gbc)

        listOf(
            experimentNameField,
            numberOfReplicationsField,
            lengthOfReplicationField,
            lengthOfReplicationWarmUpField
        ).forEach { field ->
            field.document.addDocumentListener(SimpleDocumentListener { handleFieldChanged() })
        }
    }

    /** Re-renders the form from the supplied parameters (e.g., after the user
     *  selects a different model). */
    fun renderRunParameters(params: ExperimentRunParameters) {
        suppressEvents = true
        try {
            experimentNameField.text = params.experimentName
            numberOfReplicationsField.text = params.numberOfReplications.toString()
            lengthOfReplicationField.text = params.lengthOfReplication.toString()
            lengthOfReplicationWarmUpField.text = params.lengthOfReplicationWarmUp.toString()
            current = params
            errorLabel.text = " "
        } finally {
            suppressEvents = false
        }
    }

    /** Enables/disables form editing — disabled while a run is in flight. */
    fun setEditingEnabled(enabled: Boolean) {
        listOf(
            experimentNameField,
            numberOfReplicationsField,
            lengthOfReplicationField,
            lengthOfReplicationWarmUpField
        ).forEach { it.isEnabled = enabled }
    }

    private fun handleFieldChanged() {
        if (suppressEvents) return
        val nReps = numberOfReplicationsField.text.toIntOrNull()
        val lenRep = lengthOfReplicationField.text.toDoubleOrNull()
        val warmUp = lengthOfReplicationWarmUpField.text.toDoubleOrNull()
        if (nReps == null || lenRep == null || warmUp == null) {
            errorLabel.text = "All numeric fields must parse as numbers."
            return
        }
        val updated = try {
            current.copy(
                experimentName = experimentNameField.text,
                numberOfReplications = nReps,
                lengthOfReplication = lenRep,
                lengthOfReplicationWarmUp = warmUp
            )
        } catch (e: IllegalArgumentException) {
            // ExperimentRunParameters' init block enforces > 0 / >= 0 invariants.
            errorLabel.text = e.message ?: "Invalid run parameters."
            return
        }
        errorLabel.text = " "
        current = updated
        onChanged(updated)
    }

    private fun addRow(gbc: GridBagConstraints, row: Int, labelText: String, field: JTextField) {
        gbc.gridy = row
        gbc.gridx = 0
        gbc.weightx = 0.0
        gbc.gridwidth = 1
        add(JLabel(labelText), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        add(field, gbc)
    }
}

private class SimpleDocumentListener(private val onChange: () -> Unit) : javax.swing.event.DocumentListener {
    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
}
