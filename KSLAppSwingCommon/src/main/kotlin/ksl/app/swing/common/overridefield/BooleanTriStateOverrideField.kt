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

import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * Override widget for a `Boolean?`-valued scenario field.  A
 * segmented *Default · Yes · No* control per scenario workflow §7:
 *
 *  - *Default* → [value] is `null` (inherit).
 *  - *Yes*     → [value] is `true`.
 *  - *No*      → [value] is `false`.
 *
 * Tri-state because a plain checkbox cannot represent "inherit"
 * without overloading the checked / unchecked semantics.  No
 * placeholder text needed — the *Default* button is the placeholder.
 */
class BooleanTriStateOverrideField(
    private val onValueChange: (Boolean?) -> Unit = {}
) : JPanel() {

    private val defaultBtn = JToggleButton("Default").apply { isFocusable = false }
    private val yesBtn = JToggleButton("Yes").apply { isFocusable = false }
    private val noBtn = JToggleButton("No").apply { isFocusable = false }
    private val group = ButtonGroup()

    private var myValue: Boolean? = null
    private var suppressCallback: Boolean = false

    /** Current committed override (`null` = inherit default). */
    var value: Boolean?
        get() = myValue
        set(next) {
            if (next == myValue) return
            myValue = next
            suppressCallback = true
            try {
                when (next) {
                    null -> defaultBtn.isSelected = true
                    true -> yesBtn.isSelected = true
                    false -> noBtn.isSelected = true
                }
            } finally {
                suppressCallback = false
            }
            onValueChange(next)
        }

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        group.add(defaultBtn)
        group.add(yesBtn)
        group.add(noBtn)
        defaultBtn.isSelected = true
        add(defaultBtn)
        add(yesBtn)
        add(noBtn)

        defaultBtn.addActionListener { commit(null) }
        yesBtn.addActionListener { commit(true) }
        noBtn.addActionListener { commit(false) }
    }

    private fun commit(next: Boolean?) {
        if (suppressCallback) return
        if (next == myValue) return
        myValue = next
        onValueChange(next)
    }

    /** Test-only accessor for the three buttons. */
    internal fun buttons(): Triple<JToggleButton, JToggleButton, JToggleButton> =
        Triple(defaultBtn, yesBtn, noBtn)
}
