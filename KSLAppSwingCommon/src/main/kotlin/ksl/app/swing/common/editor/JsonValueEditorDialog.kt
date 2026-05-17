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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * Modal editor for a single JSON control override.  Opened on
 * double-click of a Value cell in the JSON control-overrides tab.
 *
 * Layout: type-hint header → multi-line monospace JTextArea →
 * footer with `Format`, `Cancel`, and `OK` buttons.  `Format`
 * parses the current text via
 * `kotlinx.serialization.json.Json { prettyPrint = true }` and
 * replaces the text with the round-tripped pretty form; an
 * unparseable text surfaces a warning dialog and leaves the text
 * unchanged.  `OK` returns the (committed) text; `Cancel` and the
 * window-close path return null.
 */
object JsonValueEditorDialog {

    private val prettyJson: Json = Json {
        prettyPrint = true
        allowSpecialFloatingPointValues = true
    }

    /**
     * Opens the modal and blocks the EDT until the user dismisses it.
     *
     * @param owner the window to centre the dialog on (typically the
     *   `SingleAppFrame`).  Null centres on the screen.
     * @param keyName the control's key, shown in the title bar.
     * @param typeHint the control's typeHint, shown beneath the title.
     * @param initialJson the JSON text to seed the editor with.
     * @return the new JSON text on OK; null on Cancel or close.
     */
    fun show(
        owner: Window?,
        keyName: String,
        typeHint: String,
        initialJson: String
    ): String? {
        val dialog = JDialog(owner, "Edit JSON: $keyName", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.preferredSize = Dimension(560, 380)

        val textArea = JTextArea(initialJson).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            tabSize = 2
        }
        val typeLabel = JLabel("Type: $typeHint", SwingConstants.LEFT).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }
        val scroll = JScrollPane(textArea)

        var result: String? = null

        val formatBtn = JButton("Format").apply {
            addActionListener {
                val current = textArea.text
                val element: JsonElement = try {
                    prettyJson.parseToJsonElement(current)
                } catch (t: Throwable) {
                    JOptionPane.showMessageDialog(
                        dialog,
                        "JSON is not parseable:\n${t.message ?: t::class.simpleName}",
                        "Invalid JSON",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return@addActionListener
                }
                textArea.text = prettyJson.encodeToString(JsonElement.serializer(), element)
            }
        }
        val cancelBtn = JButton("Cancel").apply {
            addActionListener { dialog.dispose() }
        }
        val okBtn = JButton("OK").apply {
            addActionListener {
                result = textArea.text
                dialog.dispose()
            }
        }
        val footer = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(formatBtn) }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { add(cancelBtn); add(okBtn) }, BorderLayout.EAST)
        }
        dialog.contentPane = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(typeLabel, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
        dialog.rootPane.defaultButton = okBtn
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        return result
    }
}
