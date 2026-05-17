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

package ksl.app.swing.scenario

import java.awt.Component
import javax.swing.JOptionPane

/**
 *  Modal name-prompt for *Add Scenario*.  Phase E ships only the name
 *  field; the bundle/model picker is added in Phase I when bundles
 *  are loaded into the controller.  The dialog rejects blank names
 *  and names that collide with existing scenarios, looping until the
 *  user enters a unique name or cancels.
 */
object AddScenarioDialog {

    /**
     *  Show the dialog over [parent], rejecting any name in
     *  [existingNames].  Returns the user-entered name, or `null` if
     *  the user cancelled.  The returned string is trimmed.
     */
    fun prompt(parent: Component, existingNames: Set<String>): String? {
        val suggested = nextDefaultName(existingNames)
        var seed: String = suggested
        while (true) {
            val raw = JOptionPane.showInputDialog(
                parent,
                "Scenario name:",
                "Add Scenario",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                seed
            ) as String? ?: return null  // user cancelled
            val name = raw.trim()
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(
                    parent, "Name must be non-blank.",
                    "Invalid Name", JOptionPane.WARNING_MESSAGE
                )
                seed = suggested
                continue
            }
            if (name in existingNames) {
                JOptionPane.showMessageDialog(
                    parent,
                    "A scenario named '$name' already exists.\nPick a different name.",
                    "Duplicate Name", JOptionPane.WARNING_MESSAGE
                )
                seed = name
                continue
            }
            return name
        }
    }

    private fun nextDefaultName(existing: Set<String>): String {
        var n = existing.size + 1
        while (true) {
            val candidate = "Scenario $n"
            if (candidate !in existing) return candidate
            n++
        }
    }
}
