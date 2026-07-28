package ksl.animation.web

import kotlinx.browser.document
import ksl.app.swing.animation.playback.PlaybackController
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The browser transport must offer what the desktop one does for *watching* a run: Play, Stop and Loop.
 *
 * The gap that prompted these was Loop. The player looped unconditionally, the exporter said nothing about
 * it, and no control existed — so a page sent to somebody restarted for ever and the only way to stop it
 * was to close the tab. Stop went with it: without a way back to the beginning, a reader who wanted to
 * watch the opening again had to drag the scrubber to exactly zero.
 *
 * These run in a real browser under Karma, so the DOM here is the DOM a reader gets.
 */
class TransportBarControlsTest {

    private class Fixture(loop: Boolean = false) {
        val container = document.createElement("div") as HTMLDivElement
        val bar = TransportBar(container)
        val controller = PlaybackController(0.0..100.0).also { it.loop = loop }

        init {
            document.body?.appendChild(container)
            bar.attachAfter(container)
            bar.bind(controller) // binding is where the control learns the page's setting
        }

        fun button(label: String): HTMLButtonElement {
            val nodes = container.querySelectorAll("button")
            for (i in 0 until nodes.length) {
                val b = nodes.item(i) as HTMLButtonElement
                if (b.textContent?.trim() == label) return b
            }
            error("no \"$label\" button in the transport bar")
        }

        fun checkbox(): HTMLInputElement {
            val nodes = container.querySelectorAll("input[type=checkbox]")
            assertTrue(nodes.length > 0, "the transport bar has no checkbox")
            return nodes.item(0) as HTMLInputElement
        }

        fun dispose() {
            (container.parentElement as? HTMLElement)?.removeChild(container)
        }
    }

    private fun <T> withBar(loop: Boolean = false, block: (Fixture) -> T): T {
        val f = Fixture(loop)
        try {
            return block(f)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun stopReturnsToTheStartAndLeavesTheRunPaused() = withBar { f ->
        f.controller.play()
        f.controller.seek(60.0)
        assertTrue(f.controller.isPlaying, "precondition: the run is going")

        f.button("Stop").click()

        assertEquals(0.0, f.controller.currentTime, "Stop means back to the beginning, not merely pause")
        assertFalse(f.controller.isPlaying, "and it stops")
    }

    @Test
    fun theLoopBoxShowsWhatThePageAskedFor() {
        // The page author's data-ksl-loop reaches the controller before the bar is bound, so the box has to
        // report the controller rather than assume anything. Both directions, because a box that happened
        // to agree with a default would pass while showing nothing.
        withBar(loop = true) { f -> assertTrue(f.checkbox().checked, "a looping page must show a ticked box") }
        withBar(loop = false) { f -> assertFalse(f.checkbox().checked, "and a non-looping one an empty box") }
    }

    @Test
    fun tickingTheLoopBoxChangesWhetherTheRunRepeats() = withBar { f ->
        val box = f.checkbox()
        box.checked = false
        box.dispatchEvent(org.w3c.dom.events.Event("change"))
        assertFalse(f.controller.loop, "unticking must actually stop it repeating")

        box.checked = true
        box.dispatchEvent(org.w3c.dom.events.Event("change"))
        assertTrue(f.controller.loop, "and ticking must put it back")
    }

    @Test
    fun theBarStillOffersPlayAndASpeedControl() = withBar { f ->
        assertNotNull(f.button("Play"), "Play is the control everything else is arranged around")
        assertTrue(f.container.querySelectorAll("select").length > 0, "the speed control survives the additions")
    }
}
