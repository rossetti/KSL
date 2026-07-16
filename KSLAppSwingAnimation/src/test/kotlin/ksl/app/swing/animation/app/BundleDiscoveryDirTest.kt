package ksl.app.swing.animation.app

import ksl.app.session.AppWorkspacePaths
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression: the animation app writes its artifacts (layouts/traces) under `<workspace>/KSLAnimation/`
 * (APP_FOLDER), but bundle discovery once derived its folder from the *display* appName ("KSL Animation App"),
 * which the workspace sanitizer turns into "KSL_Animation_App" — a DIFFERENT folder. So the app looked for
 * bundles somewhere it never writes, silently loading a stale/absent JAR. Discovery must use APP_FOLDER.
 */
class BundleDiscoveryDirTest {

    @TempDir
    lateinit var ws: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model = Model("t")
    }

    @Test
    fun `bundle discovery uses the app working folder, not the sanitized display name`() {
        val controller = AnimationAppController("KSL Animation App", builder).apply { workspaceOverride = ws }
        val working = controller.appWorkspace                                   // where the app writes (and the user drops the bundle)
        val byDisplayName = AppWorkspacePaths.appWorkspaceDir(ws, "KSL Animation App") // the old (buggy) derivation
        val byAppFolder = AppWorkspacePaths.appWorkspaceDir(ws, AnimationAppController.APP_FOLDER) // the discovery derivation now

        assertEquals("KSLAnimation", working.fileName.toString(), "app works under KSLAnimation")
        assertEquals(working, byAppFolder, "discovery must resolve to the app's working folder")
        assertNotEquals(working, byDisplayName, "the display appName diverges to KSL_Animation_App (the bug)")
        assertEquals("KSL_Animation_App", byDisplayName.fileName.toString(), "documents the diverging folder")
    }
}
