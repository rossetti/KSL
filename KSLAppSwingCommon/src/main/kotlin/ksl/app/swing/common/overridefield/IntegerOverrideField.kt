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
import java.awt.Toolkit
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Override widget for an `Int?`-valued scenario field.  `null` means
 * "inherit the model default"; non-null is the user's override.
 *
 * Commit policy (scenario workflow §7 OQ 1): the field parses on
 * blur (focus lost) and on Enter.  Blank text commits `null`; a
 * valid integer commits that integer; garbage reverts to the
 * previous committed value and invokes [onParseError] (which by
 * default emits a system beep).
 *
 * Validation is **not** subscribed here — wrap the field with
 * `FieldErrorMarker.attach` to render the §4 outline / icon /
 * tooltip.  The override field is a pure value widget.
 *
 * **Default surfacing.**  Earlier revisions rendered
 * `"30 (model default)"` *inside* the text field when [value] was
 * `null` — convenient at a glance but it fought the user during data
 * entry (couldn't tell what was placeholder vs. what they had typed).
 * The field is now left truly empty when not overridden; the
 * containing row is expected to render a sidecar "default: N" label,
 * and this field surfaces the default via a tooltip on the text
 * field plus a reset-affordance tooltip on the `×` button.
 *
 * @param modelDefault the model's default value, surfaced through
 *   tooltips ("Default: 30").  `null` renders the tooltips as
 *   "Default value unavailable".  Field text is empty in both cases
 *   when [value] is `null`.
 * @param onValueChange invoked when the committed value changes.
 *   Fires on blur, Enter, and clear-button clicks.
 * @param onParseError invoked when the user's text fails to parse
 *   on commit; the field reverts to the previous value before
 *   firing.  Default: `Toolkit.getDefaultToolkit().beep`.
 */
class IntegerOverrideField(
    var modelDefault: Int?,
    private val onValueChange: (Int?) -> Unit = {},
    private val onParseError: () -> Unit = { Toolkit.getDefaultToolkit().beep() }
) : JPanel(BorderLayout()) {

    private val textField = JTextField()
    private val clearBtn = OverrideFieldSupport.clearButton(onClear = { value = null })

    private var myValue: Int? = null
    private var normalForeground: Color = textField.foreground
    private var suppressDocChange: Boolean = false

    /** Current committed override (`null` = inherit default). */
    var value: Int?
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
        add(textField, BorderLayout.CENTER)
        add(clearBtn, BorderLayout.EAST)

        textField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { commitFromText() }
        })
        textField.addActionListener { commitFromText() }
        textField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextEdited()
            override fun removeUpdate(e: DocumentEvent) = onTextEdited()
            override fun changedUpdate(e: DocumentEvent) = onTextEdited()
        })

        refreshDisplay()
    }

    /**
     * Reapplies the placeholder/normal styling.  Public so callers
     * who change [modelDefault] outside the constructor can refresh
     * the display.
     */
    fun refreshDisplay() {
        clearBtn.isVisible = myValue != null
        clearBtn.toolTipText = OverrideFieldSupport.resetButtonTooltip(modelDefault)
        textField.toolTipText = OverrideFieldSupport.defaultValueTooltip(modelDefault)
        suppressDocChange = true
        try {
            if (myValue == null) {
                // Leave the field truly empty so the user can start
                // typing without dismissing placeholder text.  The
                // default value is surfaced through tooltips and the
                // row's sidecar "default: N" label.
                textField.foreground = normalForeground
                textField.text = ""
            } else {
                textField.foreground = normalForeground
                textField.text = myValue.toString()
            }
        } finally {
            suppressDocChange = false
        }
    }

    /** Test-only accessor for the inner text field. */
    internal val internalTextField: JTextField get() = textField

    private fun onTextEdited() {
        // The field no longer carries in-field placeholder text /
        // placeholder color, so the "restore normal foreground"
        // bookkeeping the previous revision did is moot.
    }

    private fun commitFromText() {
        val raw = textField.text
        if (raw.isBlank()) {
            value = null
            return
        }
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null) {
            onParseError()
            refreshDisplay()
            return
        }
        value = parsed
    }
}
