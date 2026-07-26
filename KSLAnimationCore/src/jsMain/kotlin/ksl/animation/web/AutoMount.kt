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

import ksl.animation.style.RgbaColor
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.get

/**
 * Turns a marked-up element into a running animation, so a page needs no JavaScript of its own:
 *
 * ```html
 * <div data-ksl-trace="traces/pharmacy.atf.gz"
 *      data-ksl-layout="traces/pharmacy.lay.json"
 *      style="width:100%;height:520px"></div>
 * <script src="ksl-animation.js"></script>
 * ```
 *
 * This is what makes a figure in the KSL book — or a page in a course site — a live, scrubbable animation
 * instead of a screenshot. The only required attribute is the trace; a trace without a layout still
 * renders, because the trace itself carries enough to draw the spaces and the things moving in them.
 *
 * Attributes, all optional except the trace:
 *
 *  - `data-ksl-trace`     the `.atf` or `.atf.gz` URL (required)
 *  - `data-ksl-layout`    the `.lay.json` URL
 *  - `data-ksl-autoplay`  `true` to start immediately
 *  - `data-ksl-loop`      `false` to stop at the end instead of repeating
 *  - `data-ksl-speed`     simulated time units per real second
 *  - `data-ksl-fit`       seconds of real time the whole run should take (default 20)
 *  - `data-ksl-transport` `false` to hide the controls (a decorative, autoplaying loop)
 *  - `data-ksl-legend`    `false` to hide the legend
 *  - `data-ksl-assets`    URL prefix for the layout's relative image references
 *  - `data-ksl-background` a hex page colour behind the animation
 */
fun main() {
    if (document.readyState.toString() == "loading") {
        document.addEventListener("DOMContentLoaded", { mountAll() })
    } else {
        mountAll()
    }
}

/** Mounts a player into every element carrying a `data-ksl-trace` attribute. */
internal fun mountAll() {
    val nodes = document.querySelectorAll("[data-ksl-trace]")
    for (i in 0 until nodes.length) {
        val element = nodes[i] as? HTMLElement ?: continue
        if (element.getAttribute(MOUNTED) != null) continue // idempotent: never mount the same element twice
        element.setAttribute(MOUNTED, "true")
        mount(element)
    }
}

private fun mount(element: HTMLElement) {
    val trace = element.getAttribute("data-ksl-trace") ?: return
    val layout = element.getAttribute("data-ksl-layout")

    // A container with no height would render a zero-pixel canvas, which looks like a broken player
    // rather than a missing style, so give it a usable default.
    if (element.style.height.isEmpty() && element.clientHeight <= 1) {
        element.style.height = DEFAULT_HEIGHT
    }
    if (element.style.position.isEmpty()) element.style.position = "relative"

    val options = PlayerOptions(
        autoPlay = element.flag("data-ksl-autoplay", default = false),
        showTransport = element.flag("data-ksl-transport", default = true),
        showLegend = element.flag("data-ksl-legend", default = true),
        loop = element.flag("data-ksl-loop", default = true),
        speed = element.getAttribute("data-ksl-speed")?.toDoubleOrNull(),
        fitSeconds = element.getAttribute("data-ksl-fit")?.toDoubleOrNull() ?: 20.0,
        assetBase = element.getAttribute("data-ksl-assets"),
        background = element.getAttribute("data-ksl-background")?.let { RgbaColor.parse(it) } ?: RgbaColor.WHITE
    )

    val player = KslAnimationPlayer(element, options)
    val inlineTrace = element.getAttribute("data-ksl-inline")
    if (inlineTrace != null) {
        // Self-contained page: the payloads are already in the document.
        val payload = document.getElementById(inlineTrace)?.textContent ?: ""
        val layoutPayload = element.getAttribute("data-ksl-inline-layout")
            ?.let { document.getElementById(it)?.textContent }
        player.loadInline(payload, layoutPayload)
    } else {
        player.load(trace, layout)
    }
}

private fun HTMLElement.flag(name: String, default: Boolean): Boolean =
    when (getAttribute(name)?.lowercase()) {
        null -> default
        "false", "0", "no" -> false
        else -> true
    }

private const val MOUNTED = "data-ksl-mounted"
private const val DEFAULT_HEIGHT = "480px"
