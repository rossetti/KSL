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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Override widget for a `@KSLJsonControl` value, holding a raw
 * JSON `String?` per scenario workflow §8.  Null means inherit
 * the model default; non-null is the user's authored JSON text.
 *
 * Layout: multi-line monospace `JTextArea` inside a `JScrollPane`
 * in the centre, plus a small button strip below with *Format*
 * and the standard `×` clear button.
 *
 * The *Format* action parses the current text via
 * `kotlinx.serialization.json.Json { prettyPrint = true }` and
 * replaces the text with the round-tripped pretty form.  If the
 * text is not valid JSON, [onParseError] fires and the text is
 * left untouched.
 *
 * Light syntax highlighting (per §8) is **deferred to Phase 6E** —
 * no library has been chosen.  The widget renders with a
 * monospace font, which is the v1 affordance.
 *
 * Commit policy mirrors the other override fields (scenario
 * workflow §7 OQ 1): commits on blur (focus lost).  Blank text
 * commits `null`; any other text commits as-is — the upstream
 * validator decides whether it is well-formed JSON.
 *
 * @param modelDefault default surfaced as muted placeholder text
 *   when [value] is `null`.  Use `null` to render
 *   *"(model defaults unavailable)"*.
 * @param onValueChange invoked when the committed value changes.
 * @param onParseError invoked when *Format* fails to parse the
 *   current text.  Default: system beep.
 */
class JsonControlValueField(
    var modelDefault: String?,
    private val onValueChange: (String?) -> Unit = {},
    private val onParseError: () -> Unit = { Toolkit.getDefaultToolkit().beep() }
) : JPanel(BorderLayout()) {

    private val textArea: JTextArea = JTextArea(MIN_ROWS, MIN_COLS).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        lineWrap = false
        tabSize = 2
    }
    private val scrollPane: JScrollPane = JScrollPane(textArea).apply {
        preferredSize = Dimension(0, MIN_PREFERRED_HEIGHT)
    }
    private val formatBtn: JButton = JButton("Format").apply {
        isFocusable = false
        addActionListener { formatCurrent() }
    }
    private val clearBtn: JButton = OverrideFieldSupport.clearButton(onClear = { value = null })

    private val json: Json = Json {
        prettyPrint = true
        allowSpecialFloatingPointValues = true
    }

    private var myValue: String? = null
    private val normalForeground: Color = textArea.foreground
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
        add(scrollPane, BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            isOpaque = false
            add(formatBtn)
            add(clearBtn)
        }
        add(buttons, BorderLayout.SOUTH)

        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { commitFromText() }
        })
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextEdited()
            override fun removeUpdate(e: DocumentEvent) = onTextEdited()
            override fun changedUpdate(e: DocumentEvent) = onTextEdited()
        })

        refreshDisplay()
    }

    fun refreshDisplay() {
        clearBtn.isVisible = myValue != null
        formatBtn.isEnabled = myValue != null
        suppressDocChange = true
        try {
            if (myValue == null) {
                textArea.foreground = OverrideFieldSupport.PLACEHOLDER_COLOR
                textArea.text = OverrideFieldSupport.placeholderText(modelDefault, modelDefault != null)
            } else {
                textArea.foreground = normalForeground
                textArea.text = myValue
            }
        } finally {
            suppressDocChange = false
        }
    }

    private fun onTextEdited() {
        if (suppressDocChange) return
        if (textArea.foreground == OverrideFieldSupport.PLACEHOLDER_COLOR) {
            textArea.foreground = normalForeground
        }
    }

    private fun commitFromText() {
        if (myValue == null && textArea.foreground == OverrideFieldSupport.PLACEHOLDER_COLOR) return
        val raw = textArea.text
        value = if (raw.isBlank()) null else raw
    }

    private fun formatCurrent() {
        // First commit whatever the user has typed so we operate on the live value.
        commitFromText()
        val current = myValue ?: return
        val element: JsonElement = try {
            json.parseToJsonElement(current)
        } catch (_: Throwable) {
            onParseError()
            return
        }
        val pretty = json.encodeToString(JsonElement.serializer(), element)
        value = pretty
    }

    /** Test-only accessor for the inner text area. */
    internal val internalTextAreaForTest: JTextArea get() = textArea

    /** Test-only: simulate a click on Format. */
    internal fun simulateFormat() = formatBtn.doClick()

    /** Test-only: simulate a click on the clear (×) button. */
    internal fun simulateClear() = clearBtn.doClick()

    /** Test-only: whether the Format button is currently enabled. */
    internal val isFormatEnabledForTest: Boolean get() = formatBtn.isEnabled

    companion object {
        private const val MIN_ROWS = 5
        private const val MIN_COLS = 40
        private const val MIN_PREFERRED_HEIGHT = 120
    }
}
