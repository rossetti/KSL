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

package ksl.app.swing.common.appearance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.swing.ButtonGroup
import javax.swing.JMenu
import javax.swing.JRadioButtonMenuItem

/**
 *  Builds a `View → Appearance` menu wired to [LookAndFeel] for any
 *  app that wants to expose the theme toggle.  The three radio items
 *  cover [AppTheme.SYSTEM], [AppTheme.LIGHT], and [AppTheme.DARK];
 *  exactly one is checked at a time, reflecting the current state of
 *  [LookAndFeel.currentTheme].
 *
 *  Subscribes to [LookAndFeel.currentTheme] via the supplied
 *  [coroutineScope] (typically the host frame's EDT scope) so the
 *  radio state stays in sync when the theme is changed
 *  programmatically (e.g. by a future settings-load step).
 */
object ThemeMenu {

    /**
     *  Construct a fresh `Appearance` menu.  The menu is independent
     *  per call; the caller decides whether to embed it inside a
     *  parent menu (e.g. `View`) or attach it directly to a
     *  `JMenuBar`.
     *
     *  @param coroutineScope EDT-bound scope used to observe
     *    [LookAndFeel.currentTheme].  Typically the host frame's
     *    coroutine scope.
     *  @param menuName       menu label.  Defaults to `"Appearance"`.
     */
    fun build(
        coroutineScope: CoroutineScope,
        menuName: String = "Appearance"
    ): JMenu {
        val menu = JMenu(menuName)
        val group = ButtonGroup()

        val systemItem = radio("Follow System", AppTheme.SYSTEM)
        val lightItem = radio("Light", AppTheme.LIGHT)
        val darkItem = radio("Dark", AppTheme.DARK)

        menu.add(systemItem)
        menu.add(lightItem)
        menu.add(darkItem)
        group.add(systemItem)
        group.add(lightItem)
        group.add(darkItem)

        val syncSelection: (AppTheme) -> Unit = { current ->
            systemItem.isSelected = current == AppTheme.SYSTEM
            lightItem.isSelected = current == AppTheme.LIGHT
            darkItem.isSelected = current == AppTheme.DARK
        }
        // Initial state — without this the menu opens with no radio
        // checked until the user clicks one.
        syncSelection(LookAndFeel.currentTheme.value)

        // Keep the menu's checked item in sync with programmatic
        // theme changes.  Hot StateFlow, so we drop the first emit
        // (it's the value we already initialized from).
        coroutineScope.launch {
            LookAndFeel.currentTheme.collect { syncSelection(it) }
        }
        return menu
    }

    private fun radio(label: String, theme: AppTheme): JRadioButtonMenuItem =
        JRadioButtonMenuItem(label).apply {
            addActionListener { LookAndFeel.setTheme(theme) }
        }
}
