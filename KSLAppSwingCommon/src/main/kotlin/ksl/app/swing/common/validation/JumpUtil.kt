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

package ksl.app.swing.common.validation

import java.awt.Rectangle
import javax.swing.JComponent

/**
 * Shared focus-and-reveal logic used by `JumpToErrorAction`
 * (keyboard navigation) and `DocumentHealthBanner` (per-entry Jump
 * button).  Looks up the widget registered at [path] in [registry]
 * and asks it to take focus + scroll itself into view.
 *
 * Returns true when a widget was found and asked to focus.
 */
object JumpUtil {

    fun jumpTo(registry: WidgetPathRegistry, path: String): Boolean {
        val widget = registry.findOne(path) ?: return false
        focusAndReveal(widget)
        return true
    }

    fun focusAndReveal(widget: JComponent) {
        widget.scrollRectToVisible(Rectangle(0, 0, widget.width, widget.height))
        widget.requestFocusInWindow()
    }
}
