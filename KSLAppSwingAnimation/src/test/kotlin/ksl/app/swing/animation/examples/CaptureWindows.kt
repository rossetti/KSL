package ksl.app.swing.animation.examples

import ksl.app.editor.BundleLibraryController
import ksl.app.swing.animation.app.AnimationAppController
import ksl.app.swing.animation.app.AnimationAppFrame
import ksl.app.swing.animation.app.ReplayPanel
import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/**
 * Doc-tooling helper: captures real window screenshots of the Animation app (main window + the four tabs)
 * to PNGs. Must run on a real (virtual) display, e.g. `xvfb-run` — NOT headless.
 * Properties: -Dbundle=<book-examples.jar> [-DbundleId=… -Dmodel=… -Dout=<dir> -Dw= -Dh=]
 */
fun main() {
    val bundleJar = System.getProperty("bundle") ?: error("-Dbundle=<book-examples.jar> required")
    val bundleId = System.getProperty("bundleId") ?: "edu.uark.ksl.book-examples"
    val modelId = System.getProperty("modelId") ?: "TandemQueueWithUnconstrainedMovement"
    val traceFile = System.getProperty("trace")?.let { Path.of(it) } // optional: load into Replay tab
    val outDir = File(System.getProperty("out") ?: "windows").apply { mkdirs() }
    val w = System.getProperty("w")?.toIntOrNull() ?: 1280
    val h = System.getProperty("h")?.toIntOrNull() ?: 860

    LookAndFeel.install(theme = AppTheme.LIGHT, appName = "KSL Animation App")

    val bundleDir = File(System.getProperty("java.io.tmpdir"), "cap-bundles").apply { mkdirs() }
    File(bundleJar).copyTo(File(bundleDir, "book-examples.jar"), overwrite = true)
    val lib = BundleLibraryController()
    lib.discoverFromDirectories(bundleDir.toPath())

    val controller = AnimationAppController.fromBundle("KSL Animation App", lib, bundleId, modelId)

    lateinit var frame: AnimationAppFrame
    SwingUtilities.invokeAndWait {
        frame = AnimationAppFrame(controller)
        frame.setSize(w, h)
        frame.setLocation(10, 10)
        frame.isVisible = true
    }
    Thread.sleep(1500)

    val tabs = findTabbedPane(frame) ?: error("no JTabbedPane found in frame")
    val robot = Robot().apply { autoDelay = 60 }

    fun capture(name: String) {
        Thread.sleep(800)
        var img: BufferedImage? = null
        SwingUtilities.invokeAndWait {
            val loc = frame.locationOnScreen
            img = robot.createScreenCapture(Rectangle(loc.x, loc.y, frame.width, frame.height))
        }
        ImageIO.write(img, "png", File(outDir, "$name.png"))
        println("wrote $name.png")
    }

    capture("main-window")
    val names = listOf("capture-tab", "run-tab", "layout-tab", "replay-tab")
    for (i in names.indices) {
        SwingUtilities.invokeAndWait {
            tabs.selectedIndex = i
            if (i == 2) runCatching { controller.scaffoldLayout() } // fill the Layout canvas without a run
        }
        capture(names[i])
    }

    // Signature view: load a real .atf trace into the Replay tab and advance to a mid-playback frame
    // so the screenshot shows entities in motion (not the empty "Nothing loaded yet" state).
    if (traceFile != null) {
        val replay = tabs.getComponentAt(3) as? ReplayPanel
        if (replay != null) {
            SwingUtilities.invokeAndWait {
                tabs.selectedIndex = 3
                runCatching { replay.loadTrace(traceFile) }
                    .onFailure { println("loadTrace failed: ${it.message}") }
            }
            Thread.sleep(600)
            // Seek to ~40% of the run via the (private) PlaybackController — its time listener repaints the canvas.
            SwingUtilities.invokeAndWait {
                runCatching {
                    val pcField = ReplayPanel::class.java.getDeclaredField("playbackController").apply { isAccessible = true }
                    val pc = pcField.get(replay)
                    pc.javaClass.getMethod("seekFraction", java.lang.Double.TYPE).invoke(pc, 0.4)
                }.onFailure { println("seek failed: ${it.message}") }
            }
            capture("replay-loaded")
        } else {
            println("Replay tab is not a ReplayPanel; skipping replay-loaded capture")
        }
    }

    SwingUtilities.invokeAndWait { frame.dispose() }
    println("done")
}

private fun findTabbedPane(c: Component): JTabbedPane? {
    if (c is JTabbedPane) return c
    if (c is Container) for (child in c.components) findTabbedPane(child)?.let { return it }
    return null
}
