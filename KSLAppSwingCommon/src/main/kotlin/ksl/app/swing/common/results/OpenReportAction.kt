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

package ksl.app.swing.common.results

import ksl.app.config.ReportFormat
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.AbstractAction

/**
 * Opens a single report file via the user's default desktop
 * association.  Per scenario workflow §11:
 *
 *  - HTML reports use `Desktop.browse(URI)` so they open in the
 *    user's default browser.
 *  - Markdown and Text reports use `Desktop.open(file)` so the
 *    user's chosen text-or-Markdown editor handles them.
 *
 * The action invokes [onUnavailable] with the report path when
 * the desktop integration is unsupported or fails — callers can
 * chain a Markdown fallback after a failed HTML open, or surface
 * the file path so the user can open it manually.  The action
 * itself does not chain fallbacks; the caller composes them.
 *
 * Action title defaults to *"Open Report (HTML)"* / *"Open Report
 * (Markdown)"* / *"Open Report (Text)"*; override via the
 * [title] parameter when a different label is needed.
 *
 * @param format the report format this action opens.
 * @param path the file to open.
 * @param desktopOpener desktop integration; inject a fake in tests.
 * @param onUnavailable invoked when desktop integration fails or
 *   is unsupported.
 * @param title display label for the action.
 */
class OpenReportAction(
    val format: ReportFormat,
    private val path: Path,
    private val desktopOpener: DesktopOpener = DefaultDesktopOpener,
    private val onUnavailable: (Path) -> Unit = {},
    title: String = defaultTitle(format)
) : AbstractAction(title) {

    override fun actionPerformed(e: ActionEvent?) {
        val ok = when (format) {
            ReportFormat.HTML -> desktopOpener.browse(path.toUri())
            ReportFormat.MARKDOWN, ReportFormat.TEXT -> desktopOpener.open(path.toFile())
        }
        if (!ok) onUnavailable(path)
    }

    companion object {
        /** Default action label for [format]. */
        fun defaultTitle(format: ReportFormat): String = when (format) {
            ReportFormat.HTML -> "Open Report (HTML)"
            ReportFormat.MARKDOWN -> "Open Report (Markdown)"
            ReportFormat.TEXT -> "Open Report (Text)"
        }
    }
}
