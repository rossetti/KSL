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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Desktop
import java.io.File
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Thin injectable wrapper around `java.awt.Desktop` for the
 * results-pane *Open …* actions.  Centralising the Desktop calls
 * here lets each action stay testable headless: production wiring
 * uses [DefaultDesktopOpener] (real Desktop calls); tests pass a
 * fake that records invocations.
 *
 * Both methods return `false` on failure rather than throwing, so
 * the calling action can chain fallback paths without exception
 * handling.
 */
interface DesktopOpener {

    /**
     * Opens [uri] in the user's default browser (or whatever
     * `Desktop.Action.BROWSE` is wired to).  Returns `true` on
     * success, `false` when the desktop integration is unsupported
     * or the call threw.
     */
    fun browse(uri: URI): Boolean

    /**
     * Reveals or opens [file] using the user's default association
     * (`Desktop.Action.OPEN`).  Returns `true` on success.
     */
    fun open(file: File): Boolean
}

/**
 * Production [DesktopOpener] backed by `java.awt.Desktop`.
 * Failures (unsupported action, missing browser, headless JVM) are
 * logged and reported as `false` rather than rethrown.
 */
object DefaultDesktopOpener : DesktopOpener {

    override fun browse(uri: URI): Boolean {
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
                true
            } else {
                logger.info { "Desktop.Action.BROWSE unsupported; cannot open $uri." }
                false
            }
        } catch (t: Throwable) {
            logger.warn(t) { "Desktop.browse($uri) failed." }
            false
        }
    }

    override fun open(file: File): Boolean {
        return try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
                true
            } else {
                logger.info { "Desktop.Action.OPEN unsupported; cannot open ${file.path}." }
                false
            }
        } catch (t: Throwable) {
            logger.warn(t) { "Desktop.open($file) failed." }
            false
        }
    }
}
