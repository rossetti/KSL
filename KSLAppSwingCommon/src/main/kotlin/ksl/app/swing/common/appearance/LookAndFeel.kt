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

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 *  Bootstraps FlatLaf as the look-and-feel for every Phase-6 Swing
 *  app, plus applies a small set of global UIManager tweaks for a
 *  more IntelliJ-flavoured look.
 *
 *  Usage at app entry, before any AWT / Swing class touches the EDT:
 *
 *  ```kotlin
 *  fun main() {
 *      LookAndFeel.install(theme = AppTheme.SYSTEM, appName = "KSL Scenario")
 *      SwingUtilities.invokeLater {
 *          ScenarioAppFrame().apply { isVisible = true }
 *      }
 *  }
 *  ```
 *
 *  The current theme is exposed as a [StateFlow] so menus / widgets
 *  can subscribe to it (e.g. [ThemeMenu]'s radio-button group).  The
 *  in-process state survives only for the JVM's lifetime — persistence
 *  into `~/.ksl/settings.toml` is a separate, future commit.
 *
 *  Thread-safety: [install] and [setTheme] must be called on the EDT
 *  (or, for [install], from the bootstrap thread before any Swing
 *  component is constructed).  [currentTheme] is safe to read from
 *  any thread.
 */
object LookAndFeel {

    private val myCurrentTheme = MutableStateFlow(AppTheme.SYSTEM)

    /** The user's selected appearance preference.  Distinct from the
     *  resolved concrete theme — when this value is [AppTheme.SYSTEM],
     *  the active LAF is whichever of [AppTheme.LIGHT] / [AppTheme.DARK]
     *  the host OS reports at install time. */
    val currentTheme: StateFlow<AppTheme> = myCurrentTheme.asStateFlow()

    /**
     *  Install FlatLaf as the global look-and-feel and apply common
     *  KSL-flavoured tweaks.  Idempotent: calling a second time
     *  switches the theme and re-renders every open window via
     *  [updateAllWindows].
     *
     *  @param theme    initial appearance preference.  [AppTheme.SYSTEM]
     *    follows the host OS.
     *  @param appName  application name surfaced in the macOS
     *    application menu (the leftmost slot in the screen menu bar).
     *    `null` keeps the JVM default ("java"); apps should pass
     *    their own readable name.
     */
    fun install(
        theme: AppTheme = AppTheme.SYSTEM,
        appName: String? = null
    ) {
        // macOS bootstrap — these must be set before the first AWT
        // class loads to have any effect.  Calling install() from a
        // main() before SwingUtilities.invokeLater satisfies that.
        if (isMacOS()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true")
            System.setProperty("apple.awt.application.appearance", "system")
            if (appName != null) {
                System.setProperty("apple.awt.application.name", appName)
            }
        }
        applyTheme(theme)
        installGlobalDefaults()
        myCurrentTheme.value = theme
    }

    /**
     *  Switch the active theme at runtime.  Re-renders every open
     *  window so the change is immediately visible.  Safe to wire up
     *  to a menu item.  Call on the EDT.
     */
    fun setTheme(theme: AppTheme) {
        if (myCurrentTheme.value == theme) return
        applyTheme(theme)
        installGlobalDefaults()
        myCurrentTheme.value = theme
        updateAllWindows()
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun applyTheme(theme: AppTheme) {
        val laf = when (theme.resolve()) {
            AppTheme.LIGHT -> FlatLightLaf()
            AppTheme.DARK -> FlatDarkLaf()
            else -> FlatLightLaf()              // unreachable after resolve()
        }
        try {
            UIManager.setLookAndFeel(laf)
        } catch (t: Throwable) {
            // Fall back to system default rather than crash the app.
            System.err.println(
                "FlatLaf install failed (${t.message}); falling back to system L&F."
            )
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        }
    }

    /** Small set of theme-aware tweaks applied after every theme
     *  swap.  These are intentionally cosmetic — anything that would
     *  alter component layout (preferred sizes, insets) stays out of
     *  this helper.  Themes pick up these defaults via FlatLaf's
     *  built-in client-property / UIManager-key plumbing. */
    private fun installGlobalDefaults() {
        // Rounded corners on buttons, text fields, combo boxes.
        UIManager.put("Component.arc", 8)
        UIManager.put("Button.arc", 8)
        UIManager.put("TextComponent.arc", 6)
        // Pill-shaped scrollbar thumbs / tracks.
        UIManager.put("ScrollBar.thumbArc", 999)
        UIManager.put("ScrollBar.trackArc", 999)
        UIManager.put("ScrollBar.width", 12)
        // Subtle separators between tab headers.
        UIManager.put("TabbedPane.showTabSeparators", true)
        // Striped table backgrounds — a tiny tint over Table.background
        // so the stripe is visible in both light and dark variants.
        val tableBg = UIManager.getColor("Table.background")
        if (tableBg != null) {
            UIManager.put(
                "Table.alternateRowColor",
                tintRow(tableBg)
            )
        }
    }

    /** Returns a row-stripe color: slightly darker than [base] in
     *  light themes, slightly lighter in dark themes.  Tiny delta —
     *  the stripe is meant to be felt, not read. */
    private fun tintRow(base: java.awt.Color): java.awt.Color {
        val luma = (0.299 * base.red + 0.587 * base.green + 0.114 * base.blue) / 255.0
        val delta = if (luma > 0.5) -10 else +12       // light → darker; dark → lighter
        fun clamp(v: Int): Int = v.coerceIn(0, 255)
        return java.awt.Color(
            clamp(base.red + delta),
            clamp(base.green + delta),
            clamp(base.blue + delta)
        )
    }

    /** Re-renders every existing window after a theme change.
     *  Without this, only newly-created windows pick up the new LAF. */
    private fun updateAllWindows() {
        FlatLaf.updateUI()
        for (window in JFrame.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window)
            window.repaint()
        }
    }

    private fun isMacOS(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")
}
