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

import java.awt.Color
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JButton

/**
 * Internal helpers shared by every concrete override field
 * (`IntegerOverrideField`, `DoubleOverrideField`,
 * `BooleanTriStateOverrideField`, `DurationOverrideField`).
 *
 * The override-field family models three states per scenario
 * workflow §7:
 *
 *  - **Inherits default** — underlying value is `null`; the field
 *    displays the model's default in muted color with no clear button.
 *  - **Overridden** — underlying value is non-null; field displays
 *    the user's value in normal color; a small `×` clear button is
 *    visible.
 *  - **Overridden + invalid** — non-null but fails validation; the
 *    standard `FieldErrorMarker` is applied externally by the
 *    caller; the `×` still clears.
 */
object OverrideFieldSupport {

    /** Muted gray used to render the model-default placeholder text. */
    val PLACEHOLDER_COLOR: Color = Color(0x88, 0x88, 0x88)

    /**
     * Builds the placeholder text shown when the user has not
     * overridden the field.  When [available] is true, the formatted
     * [modelDefault] is shown alongside the *(model default)* tag;
     * when false, a fallback string is shown.
     */
    fun placeholderText(modelDefault: Any?, available: Boolean): String =
        if (!available) "(model defaults unavailable)"
        else "${modelDefault ?: ""} (model default)".trim()

    /**
     * Creates the standard `×` clear button used by every override
     * field.  The button's visibility is left to the caller — it
     * does not subscribe to anything; rebuild [setVisible] as the
     * field's value changes.
     *
     * @param onClear invoked on click.
     * @param tooltip tooltip shown on hover.
     */
    fun clearButton(onClear: () -> Unit, tooltip: String = "Clear override"): JButton =
        JButton("×").apply {
            toolTipText = tooltip
            isFocusable = false
            isFocusPainted = false
            margin = java.awt.Insets(0, 4, 0, 4)
            border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
            preferredSize = Dimension(20, preferredSize.height)
            addActionListener { onClear() }
        }
}
