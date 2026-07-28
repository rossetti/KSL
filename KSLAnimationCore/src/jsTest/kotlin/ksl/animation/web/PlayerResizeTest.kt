package ksl.animation.web

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canvas must follow its container, however the container came to change size.
 *
 * It used to follow only `window` resizes, which is one of several ways a box changes: a class change, a
 * collapsing sidebar, a late-arriving font, a details element opening. The canvas is sized in pixels when
 * it fits, so in every other case it kept its old dimensions inside a box that had moved — leaving either
 * a blank strip or a clipped animation, with no event to put it right.
 *
 * `ResizeObserver` is asynchronous, so these wait for a frame rather than asserting straight after the
 * mutation. Karma runs them in a real browser, which is the only place any of this is true.
 */
class PlayerResizeTest {

    private fun host(width: Int, height: Int): HTMLDivElement {
        val div = document.createElement("div") as HTMLDivElement
        div.style.width = "${width}px"
        div.style.height = "${height}px"
        document.body?.appendChild(div)
        return div
    }

    private fun dispose(el: HTMLElement) {
        (el.parentElement as? HTMLElement)?.removeChild(el)
    }

    private fun canvasOf(container: HTMLElement): HTMLCanvasElement =
        container.querySelector("canvas") as HTMLCanvasElement

    /** Waits for the observer to deliver and the player to re-fit. */
    private fun afterLayout(block: () -> Unit) {
        kotlinx.browser.window.requestAnimationFrame {
            kotlinx.browser.window.requestAnimationFrame { block() }
        }
    }

    @Test
    fun theCanvasFollowsAContainerThatChangesWithoutAWindowResize() = kotlin.js.Promise<Unit> { resolve, _ ->
        val container = host(600, 400)
        KslAnimationPlayer(container)
        val canvas = canvasOf(container)

        afterLayout {
            val before = canvas.height
            // No window resize anywhere in this: the box simply becomes taller, the way a page would do it.
            container.style.height = "700px"
            afterLayout {
                val after = canvas.height
                try {
                    assertTrue(
                        after > before,
                        "the canvas kept its old height ($before) inside a taller box; it only ever " +
                            "followed window resizes, so any other way of changing the box left it stale"
                    )
                } finally {
                    dispose(container)
                }
                resolve(Unit)
            }
        }
    }

    @Test
    fun theCanvasFollowsAContainerThatShrinks() = kotlin.js.Promise<Unit> { resolve, _ ->
        val container = host(600, 500)
        KslAnimationPlayer(container)
        val canvas = canvasOf(container)

        afterLayout {
            val before = canvas.height
            container.style.height = "260px"
            afterLayout {
                val after = canvas.height
                try {
                    assertTrue(after < before, "a canvas taller than its box is clipped, not merely untidy")
                } finally {
                    dispose(container)
                }
                resolve(Unit)
            }
        }
    }

    @Test
    fun theLegendNeverClaimsMostOfANarrowCanvas() {
        // Not a rendering test: the cap itself is the contract, and it is the whole reason a narrow page
        // drew a sliver of an animation beside a legend nearly as wide as it was.
        assertTrue(
            KslAnimationPlayer.MAX_LEGEND_SHARE > 0.0 && KslAnimationPlayer.MAX_LEGEND_SHARE <= 0.35,
            "the legend's share must be capped, and modestly: ${KslAnimationPlayer.MAX_LEGEND_SHARE}"
        )
        val narrow = 470.0
        val legendWanted = 230.0
        val reserved = minOf(legendWanted, narrow * KslAnimationPlayer.MAX_LEGEND_SHARE)
        assertTrue(
            narrow - reserved > narrow / 2,
            "the animation must keep more than half a narrow canvas; it was getting $reserved reserved away"
        )
        assertEquals(narrow * KslAnimationPlayer.MAX_LEGEND_SHARE, reserved, "the cap is what binds here")
    }
}
