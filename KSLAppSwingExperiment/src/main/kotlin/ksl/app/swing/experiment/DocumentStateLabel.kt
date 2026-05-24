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

package ksl.app.swing.experiment

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.JLabel

/**
 *  Small inline label that mirrors a [StateFlow]<Boolean> of the
 *  document's dirty state.  Used at the bottom-right of each
 *  authoring tab (Model / Factors / Design / Simulate) so the user
 *  can see at a glance whether the document has unsaved changes
 *  without having to notice the `*` in the window title.
 *
 *  Two visual states:
 *  - dirty = true  → `● Edited`  (amber)
 *  - dirty = false → `✓ Saved`   (grey)
 *
 *  The label subscribes to the supplied dirty flow on the supplied
 *  scope; cancellation of the scope detaches the subscription
 *  cleanly (callers typically use `controller.edtScope`, which is
 *  cancelled when the controller closes).
 */
class DocumentStateLabel(
    isDirtyFlow: StateFlow<Boolean>,
    scope: kotlinx.coroutines.CoroutineScope
) : JLabel(" ") {

    init {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        scope.launch {
            isDirtyFlow.collect { dirty -> applyState(dirty) }
        }
        applyState(isDirtyFlow.value)
    }

    private fun applyState(dirty: Boolean) {
        if (dirty) {
            text = "● Edited"
            foreground = Color(0xB8, 0x86, 0x0B)   // amber
            toolTipText = "The document has unsaved changes — use File → Save."
        } else {
            text = "✓ Saved"
            foreground = Color(0x77, 0x77, 0x77)   // grey
            toolTipText = "All changes saved to the configuration file."
        }
    }
}
