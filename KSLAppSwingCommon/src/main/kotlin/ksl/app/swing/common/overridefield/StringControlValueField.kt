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

package ksl.app.swing.common.overridefield

import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Override widget for a `String?`-valued scenario field — the
 * `@KSLStringControl` family per scenario workflow §8.
 *
 * Renders as an editable `JComboBox` when [allowedValues] is
 * non-null and non-empty, or as a plain `JTextField` otherwise.
 * Even with [allowedValues] the input remains free-form: the user
 * can type a value not in the list, and the upstream
 * `RunConfigurationValidator` surfaces it as a warning via the
 * standard `FieldErrorMarker` decoration.  This widget itself
 * performs no validation.
 *
 * Commit policy mirrors the other override fields (scenario
 * workflow §7 OQ 1): commits on blur (focus lost) and on Enter.
 * Blank text commits `null`; any other text commits as-is.  No
 * parse-failure path exists for strings.
 *
 * @param modelDefault default surfaced as muted placeholder text
 *   when [value] is `null`.  Use `null` to render
 *   *"(model defaults unavailable)"*.
 * @param allowedValues optional list of pickable values.  When
 *   non-null and non-empty, the widget renders as an editable
 *   combo box with these items in the dropdown.
 * @param onValueChange invoked when the committed value changes.
 */
class StringControlValueField(
    var modelDefault: String?,
    private val allowedValues: List<String>? = null,
    private val onValueChange: (String?) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val mode: Mode = if (!allowedValues.isNullOrEmpty()) Mode.ComboBox else Mode.TextField
    private val textField: JTextField? = if (mode == Mode.TextField) JTextField() else null
    private val comboBox: JComboBox<String>? =
        if (mode == Mode.ComboBox) JComboBox(DefaultComboBoxModel(allowedValues!!.toTypedArray())).apply {
            isEditable = true
        } else null
    private val clearBtn = OverrideFieldSupport.clearButton(onClear = { value = null })

    private var myValue: String? = null
    private val normalForeground: Color = textComponent().foreground
    private var suppressDocChange: Boolean = false

    /** Current committed override (`null` = inherit default). */
    var value: String?
        get() = myValue
        set(next) {
            if (next == myValue) {
                refreshDisplay()
                return
            }
            myValue = next
            refreshDisplay()
            onValueChange(next)
        }

    init {
        isOpaque = false
        add(activeComponent(), BorderLayout.CENTER)
        add(clearBtn, BorderLayout.EAST)
        wireListeners()
        refreshDisplay()
    }

    private fun activeComponent(): javax.swing.JComponent = textField ?: comboBox!!

    private fun textComponent(): JTextField =
        textField ?: (comboBox!!.editor.editorComponent as JTextField)

    private fun wireListeners() {
        val tc = textComponent()
        tc.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { commitFromText() }
        })
        tc.addActionListener { commitFromText() }
        tc.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextEdited()
            override fun removeUpdate(e: DocumentEvent) = onTextEdited()
            override fun changedUpdate(e: DocumentEvent) = onTextEdited()
        })
        if (comboBox != null) {
            comboBox.addActionListener {
                // Combo selection event also routes through the editor's text;
                // commit on selection too so dropdown picks behave the same as
                // typed-and-Enter.
                if (!suppressDocChange) commitFromText()
            }
        }
    }

    fun refreshDisplay() {
        clearBtn.isVisible = myValue != null
        suppressDocChange = true
        try {
            val tc = textComponent()
            if (myValue == null) {
                tc.foreground = OverrideFieldSupport.PLACEHOLDER_COLOR
                tc.text = OverrideFieldSupport.placeholderText(modelDefault, modelDefault != null)
            } else {
                tc.foreground = normalForeground
                tc.text = myValue
            }
        } finally {
            suppressDocChange = false
        }
    }

    private fun onTextEdited() {
        if (suppressDocChange) return
        val tc = textComponent()
        if (tc.foreground == OverrideFieldSupport.PLACEHOLDER_COLOR) {
            tc.foreground = normalForeground
        }
    }

    private fun commitFromText() {
        if (suppressDocChange) return
        val tc = textComponent()
        if (myValue == null && tc.foreground == OverrideFieldSupport.PLACEHOLDER_COLOR) return
        val raw = tc.text
        value = if (raw.isBlank()) null else raw
    }

    /** Test-only: the underlying editable component. */
    internal fun textComponentForTest(): JTextField = textComponent()

    /** Test-only: whether the widget is rendered as a JComboBox. */
    internal fun isComboBoxForTest(): Boolean = mode == Mode.ComboBox

    /** Test-only: the combo box's model items (when in combo mode). */
    internal fun comboItemsForTest(): List<String> {
        val cb = comboBox ?: return emptyList()
        val model = cb.model
        return (0 until model.size).map { model.getElementAt(it) }
    }

    private enum class Mode { TextField, ComboBox }
}
