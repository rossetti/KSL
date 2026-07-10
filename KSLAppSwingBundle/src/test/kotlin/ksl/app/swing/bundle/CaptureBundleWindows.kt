package ksl.app.swing.bundle

import ksl.app.bundle.KSLAppKind
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
 * Doc-tooling helper: captures real window screenshots of the Bundle Workbench (Overview · Bundle
 * identity · Models · Catalog) to PNGs, and prints what the workbench discovered from the JAR.
 * Must run on a real (virtual) display, e.g. `xvfb-run` — NOT headless.
 * Properties: -Dbuilders=<book-builders.jar> [-Dout=<dir> -Dw= -Dh=]
 */
fun main() {
    val buildersJar = System.getProperty("builders") ?: error("-Dbuilders=<book-builders.jar> required")
    val outDir = File(System.getProperty("out") ?: "windows").apply { mkdirs() }
    val w = System.getProperty("w")?.toIntOrNull() ?: 1040
    val h = System.getProperty("h")?.toIntOrNull() ?: 760

    LookAndFeel.install(theme = AppTheme.LIGHT, appName = "KSL Bundle Workbench")

    val controller = BundleWorkbenchController("KSL Bundle Workbench")
    controller.openJar(Path.of(buildersJar))

    // ── Probe: what did the workbench discover? ──────────────────────────────
    val models = controller.models.value
    println("=== discovered ${models.size} model(s) from $buildersJar ===")
    models.forEach { println("  - ${it.modelId}  <-  ${it.builderClass}") }
    controller.discoveryErrors.value.takeIf { it.isNotEmpty() }?.let {
        println("skipped builders:"); it.forEach { e -> println("  ! $e") }
    }
    // Per-model catalog richness, to choose a good Catalog-tab screenshot.
    val richest = models.maxByOrNull { m ->
        controller.selectModel(m.modelId)
        (controller.catalogDraft.value?.inputs?.size ?: 0) + (controller.catalogDraft.value?.outputs?.size ?: 0)
    }?.modelId
    println("richest catalog model = $richest")

    lateinit var frame: BundleWorkbenchFrame
    SwingUtilities.invokeAndWait {
        frame = BundleWorkbenchFrame(controller)
        frame.setSize(w, h); frame.setLocation(10, 10); frame.isVisible = true
    }
    Thread.sleep(1500)
    val tabs = findTabbedPane(frame) ?: error("no JTabbedPane found in frame")
    val robot = Robot().apply { autoDelay = 60 }
    fun capture(name: String) {
        Thread.sleep(700)
        var img: BufferedImage? = null
        SwingUtilities.invokeAndWait {
            val loc = frame.locationOnScreen
            img = robot.createScreenCapture(Rectangle(loc.x, loc.y, frame.width, frame.height))
        }
        ImageIO.write(img, "png", File(outDir, "$name.png")); println("wrote $name.png")
    }

    // 1) Overview right after opening — the health banner flags the required bundle id.
    SwingUtilities.invokeAndWait { tabs.selectedIndex = 0 }
    capture("overview-initial")

    // 2) Author the bundle identity.
    controller.updateIdentity {
        it.copy(
            bundleId = "edu.uark.ksl.book-examples",
            displayName = "KSL Book Examples",
            description = "Worked models from the KSL book.",
            version = "1.0.0",
        )
    }
    SwingUtilities.invokeAndWait { tabs.selectedIndex = 1 }
    capture("identity")

    // 3) Models tab — give every model a representative supported-app set so the columns show.
    models.forEach { m ->
        controller.updateModel(m.modelId) {
            it.copy(supportedApps = setOf(KSLAppKind.SINGLE, KSLAppKind.SCENARIO, KSLAppKind.EXPERIMENT, KSLAppKind.SIMOPT))
        }
    }
    SwingUtilities.invokeAndWait { tabs.selectedIndex = 2 }
    capture("models")

    // 4) Catalog tab — feature the richest model's inputs/outputs so the family tables populate.
    richest?.let { controller.selectModel(it) }
    controller.updateDraft { it.nominateAll() }
    SwingUtilities.invokeAndWait { tabs.selectedIndex = 3 }
    capture("catalog")

    // 5) Overview after authoring + validate — the summary + guided next step.
    controller.validate()
    SwingUtilities.invokeAndWait { tabs.selectedIndex = 0 }
    capture("overview-ready")

    SwingUtilities.invokeAndWait { frame.dispose() }
    println("done")
}

private fun findTabbedPane(c: Component): JTabbedPane? {
    if (c is JTabbedPane) return c
    if (c is Container) for (child in c.components) findTabbedPane(child)?.let { return it }
    return null
}
