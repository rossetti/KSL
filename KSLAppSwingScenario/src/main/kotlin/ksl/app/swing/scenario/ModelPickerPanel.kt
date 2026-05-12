package ksl.app.swing.scenario

import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Bundled-model dropdown.  Changing the selection both updates the view
 * model's selected id and resets the scenario list to that model's bundled
 * sweep — wired through [onModelSelected].
 */
internal class ModelPickerPanel(
    availableModelIds: List<String>,
    initialModelId: String,
    private val onModelSelected: (String) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val combo: JComboBox<String> =
        JComboBox(DefaultComboBoxModel(availableModelIds.toTypedArray())).apply {
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

    fun setEditingEnabled(enabled: Boolean) {
        combo.isEnabled = enabled
    }
}
