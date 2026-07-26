/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.animation.web

import ksl.app.swing.animation.playback.PlaybackController
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * The play / pause / scrub strip beneath an animation.
 *
 * Built from plain DOM elements rather than drawn on the canvas, so it is focusable, keyboard-operable and
 * styleable by the page that embeds it — and so a reader of a book page gets native controls rather than
 * an approximation of them.
 *
 * Scrubbing pauses first. Dragging a scrubber while the clock is still advancing fights the user for
 * control of the same value, and pausing makes the gesture do what it looks like it does.
 */
internal class TransportBar(private val container: HTMLElement) {

    private val root = document.createElement("div") as HTMLDivElement
    private val playButton = document.createElement("button") as HTMLButtonElement
    private val scrubber = document.createElement("input") as HTMLInputElement
    private val speedSelect = document.createElement("select") as HTMLSelectElement
    private val timeLabel = document.createElement("span") as HTMLElement
    private val statusLabel = document.createElement("span") as HTMLElement
    private val progressFill = document.createElement("div") as HTMLDivElement

    private var controller: PlaybackController? = null
    private var baseSpeed = 1.0

    /** The vertical space the bar occupies, so the canvas can be sized to what is left. */
    val heightPx: Double get() = HEIGHT

    init {
        root.setAttribute(
            "style",
            "display:flex;align-items:center;gap:8px;height:${HEIGHT}px;" +
                "font:13px system-ui,-apple-system,sans-serif;color:#333;position:relative;"
        )

        playButton.textContent = "Play"
        playButton.setAttribute("aria-label", "Play or pause the animation")
        playButton.setAttribute(
            "style",
            "min-width:64px;padding:3px 10px;border:1px solid #ccc;border-radius:4px;background:#fff;cursor:pointer;font:inherit;"
        )
        playButton.addEventListener("click", {
            controller?.let { c ->
                c.togglePlay()
                syncPlayLabel(c)
            }
        })

        scrubber.type = "range"
        scrubber.min = "0"
        scrubber.max = SCRUB_STEPS.toString()
        scrubber.value = "0"
        scrubber.setAttribute("aria-label", "Position in the run")
        scrubber.setAttribute("style", "flex:1;min-width:80px;")
        scrubber.addEventListener("input", {
            controller?.let { c ->
                c.pause()
                syncPlayLabel(c)
                c.seekFraction(scrubber.valueAsNumber / SCRUB_STEPS)
            }
        })

        speedSelect.setAttribute("aria-label", "Playback speed")
        speedSelect.setAttribute("style", "padding:2px 4px;border:1px solid #ccc;border-radius:4px;background:#fff;font:inherit;")
        for (multiplier in SPEEDS) {
            val option = document.createElement("option") as org.w3c.dom.HTMLOptionElement
            option.value = multiplier.toString()
            option.textContent = if (multiplier == 1.0) "1x" else "${trimZero(multiplier)}x"
            speedSelect.appendChild(option)
        }
        speedSelect.value = "1.0"
        speedSelect.addEventListener("change", {
            controller?.speed = baseSpeed * (speedSelect.value.toDoubleOrNull() ?: 1.0)
        })

        timeLabel.setAttribute("style", "font-variant-numeric:tabular-nums;color:#555;min-width:96px;text-align:right;")
        statusLabel.setAttribute("style", "color:#777;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:45%;")

        // A thin progress line across the top of the bar, used only while a trace is being decoded.
        progressFill.setAttribute(
            "style",
            "position:absolute;left:0;top:0;height:2px;width:0;background:#3366cc;transition:width .1s linear;"
        )

        root.appendChild(playButton)
        root.appendChild(scrubber)
        root.appendChild(timeLabel)
        root.appendChild(speedSelect)
        root.appendChild(statusLabel)
        root.appendChild(progressFill)
    }

    fun attachAfter(sibling: HTMLElement) {
        container.appendChild(root)
    }

    fun bind(controller: PlaybackController) {
        this.controller = controller
        baseSpeed = controller.speed
        speedSelect.value = "1.0"
        syncPlayLabel(controller)
        progressFill.style.width = "0"
    }

    fun showProgress(progress: LoadProgress) {
        progressFill.style.width = "${(progress.fraction * 100).coerceIn(0.0, 100.0)}%"
        statusLabel.textContent = progress.phase + "…"
    }

    fun showStatus(text: String) {
        statusLabel.textContent = text
        progressFill.style.width = "0"
    }

    fun showTime(t: Double, controller: PlaybackController) {
        val range = controller.effectiveRange
        timeLabel.textContent = "${fixed(t, 1)} / ${fixed(range.endInclusive, 1)}"
        scrubber.valueAsNumber = controller.fraction() * SCRUB_STEPS
        syncPlayLabel(controller)
    }

    private fun syncPlayLabel(controller: PlaybackController) {
        playButton.textContent = if (controller.isPlaying) "Pause" else "Play"
    }

    private fun trimZero(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** Fixed-point formatting that does not depend on platform number formatting. */
    private fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        val scaled = kotlin.math.round(value * factor)
        val negative = scaled < 0
        val digits = kotlin.math.abs(scaled).toLong().toString().padStart(decimals + 1, '0')
        val whole = digits.dropLast(decimals).ifEmpty { "0" }
        val frac = if (decimals > 0) "." + digits.takeLast(decimals) else ""
        return (if (negative) "-" else "") + whole + frac
    }

    private companion object {
        const val HEIGHT = 34.0
        const val SCRUB_STEPS = 1000.0
        val SPEEDS = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)
    }
}
