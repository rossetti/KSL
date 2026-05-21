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
import kotlin.time.Duration

/**
 * Override widget for a `Duration?`-valued scenario field.
 *
 * Parses with [Duration.parse], which accepts both the ISO-8601 form
 * (`PT30S`, `PT5M`) and Kotlin's extended unit-aware form (`30s`,
 * `5m`, `1d 12h`) per scenario workflow §7 OQ 4.  Blank → `null`;
 * garbage → revert + [onParseError].
 */
class DurationOverrideField(
    var modelDefault: Duration?,
    private val onValueChange: (Duration?) -> Unit = {},
    private val onParseError: () -> Unit = { Toolkit.getDefaultToolkit().beep() }
) : JPanel(BorderLayout()) {

    private val textField = JTextField()
    private val clearBtn = OverrideFieldSupport.clearButton(onClear = { value = null })

    private var myValue: Duration? = null
    private var normalForeground: Color = textField.foreground
    private var suppressDocChange: Boolean = false

    var value: Duration?
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

    fun refreshDisplay() {
        clearBtn.isVisible = myValue != null
        clearBtn.toolTipText = OverrideFieldSupport.resetButtonTooltip(modelDefault)
        textField.toolTipText = OverrideFieldSupport.defaultValueTooltip(modelDefault)
        suppressDocChange = true
        try {
            // Field left empty when not overridden; default surfaces
            // via tooltip and the row's sidecar "default: N" label.
            textField.foreground = normalForeground
            textField.text = myValue?.toString() ?: ""
        } finally {
            suppressDocChange = false
        }
    }

    internal val internalTextField: JTextField get() = textField

    private fun onTextEdited() {
        // No in-field placeholder text any more; nothing to track.
    }

    private fun commitFromText() {
        val raw = textField.text
        if (raw.isBlank()) {
            value = null
            return
        }
        val parsed = try { Duration.parse(raw.trim()) } catch (_: IllegalArgumentException) { null }
        if (parsed == null) {
            onParseError()
            refreshDisplay()
            return
        }
        value = parsed
    }
}
