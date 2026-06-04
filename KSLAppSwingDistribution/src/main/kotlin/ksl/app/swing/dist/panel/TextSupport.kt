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

package ksl.app.swing.dist.panel

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.JTextComponent

/**
 * Installs a standard Cut / Copy / Paste / Select All right-click context menu
 * on [component], with items enabled per selection, editability, and clipboard
 * contents. Read-only components get a working Copy / Select All; Cut and Paste
 * stay disabled. Complements the platform paste shortcut (Cmd/Ctrl+V).
 */
fun installTextContextMenu(component: JTextComponent) {
    val popup = JPopupMenu()
    val cut = JMenuItem("Cut").apply { addActionListener { component.cut() } }
    val copy = JMenuItem("Copy").apply { addActionListener { component.copy() } }
    val paste = JMenuItem("Paste").apply { addActionListener { component.paste() } }
    val selectAll = JMenuItem("Select All").apply { addActionListener { component.selectAll() } }
    popup.add(cut)
    popup.add(copy)
    popup.add(paste)
    popup.addSeparator()
    popup.add(selectAll)

    popup.addPopupMenuListener(object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
            val hasSelection = !component.selectedText.isNullOrEmpty()
            val editable = component.isEditable && component.isEnabled
            val hasText = component.document.length > 0
            val clipboardHasText = runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
            }.getOrDefault(false)
            cut.isEnabled = editable && hasSelection
            copy.isEnabled = hasSelection
            paste.isEnabled = editable && clipboardHasText
            selectAll.isEnabled = hasText
        }

        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
        override fun popupMenuCanceled(e: PopupMenuEvent) {}
    })

    component.componentPopupMenu = popup
}
