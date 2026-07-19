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

package ksl.server.tray

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * The tray icon IS the status lamp: a filled dot colored by suite state (green running, amber starting,
 * gray stopped). Rendered programmatically so the module ships no image assets, and so the same three
 * colors used by the web console's header lamp appear in the menu bar. Works headless (BufferedImage).
 */
object TrayIcons {

    enum class State { RUNNING, STARTING, STOPPED }

    private fun colorFor(state: State): Color = when (state) {
        State.RUNNING -> Color(0x16, 0xA3, 0x4A)
        State.STARTING -> Color(0xD9, 0x77, 0x06)
        State.STOPPED -> Color(0x9A, 0xA0, 0xAA)
    }

    /** A transparent [size]px square with a centered filled dot in the state color plus a subtle ring. */
    fun statusImage(state: State, size: Int = 22): Image {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.composite = AlphaComposite.Clear
        g.fillRect(0, 0, size, size)
        g.composite = AlphaComposite.SrcOver
        val pad = (size * 0.18).toInt().coerceAtLeast(1)
        val d = size - 2 * pad
        g.color = colorFor(state)
        g.fillOval(pad, pad, d, d)
        g.color = Color(0, 0, 0, 60) // subtle ring for contrast on both light and dark menu bars
        g.drawOval(pad, pad, d - 1, d - 1)
        g.dispose()
        return img
    }
}
