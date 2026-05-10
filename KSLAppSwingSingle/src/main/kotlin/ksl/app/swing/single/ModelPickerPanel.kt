package ksl.app.swing.single

import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Top-of-window strip showing the bundled-model dropdown.
 *
 * File-open / file-save are intentionally deferred to a Phase 6 follow-up
 * — the proof of concept lets users choose between the two bundled models
 * and edit their parameters directly.
 *
 * @param initialModelId the model id to select on construction
 * @param onModelSelected fired whenever the user changes the selection
 */
internal class ModelPickerPanel(
    initialModelId: String,
    private val onModelSelected: (String) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val combo: JComboBox<String> =
        JComboBox(DefaultComboBoxModel(BundledModels.availableModelIds.toTypedArray())).apply {
            selectedItem = initialModelId
            addActionListener {
                val id = selectedItem as? String ?: return@addActionListener
                onModelSelected(id)
            }
        }

    init {
        add(JLabel("Model:"))
        add(combo)
    }

    /** Enables/disables the dropdown — disabled while a run is in flight. */
    fun setEditingEnabled(enabled: Boolean) {
        combo.isEnabled = enabled
    }
}
