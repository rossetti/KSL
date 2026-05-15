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
 * @param modelDefault default surfaced as muted placeholder text
 *   when [value] is `null`.  Use `null` to render
 *   *"(model defaults unavailable)"*.
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
        suppressDocChange = true
        try {
            if (myValue == null) {
                textField.foreground = OverrideFieldSupport.PLACEHOLDER_COLOR
                textField.text = OverrideFieldSupport.placeholderText(modelDefault, modelDefault != null)
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
        if (suppressDocChange) return
        // While the user is typing, keep the foreground normal so the placeholder
        // styling doesn't reappear mid-edit.
        if (textField.foreground == OverrideFieldSupport.PLACEHOLDER_COLOR) {
            textField.foreground = normalForeground
        }
    }

    private fun commitFromText() {
        if (myValue == null && textField.foreground == OverrideFieldSupport.PLACEHOLDER_COLOR) {
            // Field still shows the placeholder; nothing was typed.
            return
        }
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
