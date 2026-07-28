package ksl.animation.web

import kotlinx.browser.document
import ksl.app.animation.style.RgbaColor
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Clearing a frame must wipe the **whole** canvas, not the part of it that happens to match CSS pixels.
 *
 * On a high-density display the backing store is `devicePixelRatio` times the CSS size — two times, on
 * every Retina Mac. `clear` resets the transform to the identity, which puts it in *device* pixels, and
 * then filled a rectangle measured in *CSS* pixels: on a 2× display that covered the top-left quarter and
 * left the right half and the bottom half untouched. Whatever had been drawn there in earlier frames
 * stayed, so agents left trails along the bottom of the animation while the top looked perfect.
 *
 * It went unseen for so long because a 1× display makes the two measurements equal, and the desktop
 * viewer does not use this code at all.
 */
class Canvas2dSurfaceClearTest {

    /** A canvas whose backing store is [ratio] times its CSS size, as a high-density display gives. */
    private class Fixture(cssWidth: Int, cssHeight: Int, ratio: Int) {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val ctx: CanvasRenderingContext2D
        val surface: Canvas2dSurface

        init {
            canvas.width = cssWidth * ratio
            canvas.height = cssHeight * ratio
            ctx = canvas.getContext("2d") as CanvasRenderingContext2D
            ctx.setTransform(ratio.toDouble(), 0.0, 0.0, ratio.toDouble(), 0.0, 0.0)
            surface = Canvas2dSurface(ctx, cssWidth.toDouble(), cssHeight.toDouble(), ImageCache(null))
        }

        /** Paints every device pixel a colour that is not the background, standing in for an earlier frame. */
        fun dirtyEverything() {
            ctx.save()
            ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            ctx.fillStyle = "#ff0000"
            ctx.fillRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
            ctx.restore()
        }

        fun pixelAt(x: Int, y: Int): List<Int> {
            val d = ctx.getImageData(x.toDouble(), y.toDouble(), 1.0, 1.0).data.asDynamic()
            return listOf(d[0] as Int, d[1] as Int, d[2] as Int)
        }
    }

    private val white = listOf(255, 255, 255)

    @Test
    fun clearWipesTheWholeBackingStoreOnAHighDensityDisplay() {
        val f = Fixture(cssWidth = 200, cssHeight = 100, ratio = 2)
        f.dirtyEverything()

        f.surface.clear(RgbaColor.WHITE)

        // The corner a CSS-pixel-sized fill reaches: this always passed, which is why the bug hid.
        assertEquals(white, f.pixelAt(1, 1), "the top-left must be cleared")
        // The three that it does not. Bottom-right is where a reader sees the trails.
        assertEquals(white, f.pixelAt(f.canvas.width - 2, 1), "the right half must be cleared too")
        assertEquals(white, f.pixelAt(1, f.canvas.height - 2), "and the bottom half — this is the smearing")
        assertEquals(white, f.pixelAt(f.canvas.width - 2, f.canvas.height - 2), "and the far corner")
    }

    @Test
    fun clearStillWipesEverythingAtOrdinaryDensity() {
        val f = Fixture(cssWidth = 160, cssHeight = 90, ratio = 1)
        f.dirtyEverything()

        f.surface.clear(RgbaColor.WHITE)

        assertEquals(white, f.pixelAt(1, 1))
        assertEquals(white, f.pixelAt(f.canvas.width - 2, f.canvas.height - 2), "the 1x case must not regress")
    }
}
