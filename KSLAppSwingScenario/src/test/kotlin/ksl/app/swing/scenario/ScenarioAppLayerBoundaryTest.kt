package ksl.app.swing.scenario

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Layer-boundary check.  GUI files in this module must talk to `KSLCore`
 * only through the public app surface; in particular, they must not
 * import `ksl.app.orchestrator.*`.
 *
 * Model-wiring code (`BundledModelProviders`) lives in
 * `ksl.examples.general.appsupport` in `KSLExamples`, so no module-local
 * exclusion is needed.
 */
class ScenarioAppLayerBoundaryTest {

    @Test
    fun `GUI files do not import ksl_app_orchestrator`() {
        val guiFiles = collectGuiFiles()
        assertTrue(guiFiles.isNotEmpty(), "No GUI source files found — test fixture broken?")

        val violations = guiFiles.flatMap { f ->
            val text = f.readText()
            FORBIDDEN_IMPORT_REGEX.findAll(text)
                .map { "${f.name}: ${it.value.trim()}" }
                .toList()
        }
        assertTrue(violations.isEmpty(),
            "Layer-boundary leak — the GUI must use KSLAppSession, not a specific orchestrator:\n" +
                violations.joinToString("\n  ", prefix = "  "))
    }

    private fun collectGuiFiles(): List<File> {
        val moduleSrc = File("src/main/kotlin/ksl/app/swing/scenario")
        if (!moduleSrc.isDirectory) return emptyList()
        return moduleSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private companion object {
        val FORBIDDEN_IMPORT_REGEX =
            Regex("""^import\s+ksl\.app\.orchestrator\..*$""", RegexOption.MULTILINE)
    }
}
