package ksl.app.swing.simopt

import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/** Bundled-model dropdown.  Changing the selection resets the bundled
 *  optimization configuration shown in the summary panel — wired through
 *  [onModelSelected]. */
internal class ModelPickerPanel(
    initialModelId: String,
    private val onModelSelected: (String) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val combo: JComboBox<String> =
        JComboBox(DefaultComboBoxModel(BundledOptimizations.supportedModelIds.toTypedArray())).apply {
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
