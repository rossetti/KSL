package ksl.app.swing.single

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Layer-boundary check.  The single-app GUI files must talk to `KSLCore`
 * only through the public app surface (`KSLAppSession`, `RunSpec`, the
 * `ksl.app.config`/`ksl.app.session`/`ksl.app.validation` packages, and
 * the model-wiring types from `ksl.simulation`).
 *
 * In particular, the GUI must **not** import
 * `ksl.app.orchestrator.*`.  Orchestrators are an implementation detail
 * abstracted over by `KSLAppSession`; reaching past the session into a
 * specific orchestrator would defeat the whole point of the
 * GUI-agnostic interaction layer.
 *
 * `BundledModels.kt` is a model-wiring example, not GUI code, and is
 * excluded from this check.  It does import `ksl.simulation.*` and
 * `ksl.examples.*` to construct concrete `ModelBuilderIfc` instances —
 * the kind of thing a real user of the app-session API would also do.
 */
class SingleAppLayerBoundaryTest {

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
        val moduleSrc = File("src/main/kotlin/ksl/app/swing/single")
        if (!moduleSrc.isDirectory) return emptyList()
        return moduleSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in EXCLUDED_FROM_BOUNDARY_CHECK }
            .toList()
    }

    private companion object {
        /** Files that legitimately reach beyond the app surface. */
        val EXCLUDED_FROM_BOUNDARY_CHECK = setOf(
            "BundledModels.kt"  // model-wiring example; uses ksl.simulation + ksl.examples
        )

        /** Imports we want to keep out of the GUI surface. */
        val FORBIDDEN_IMPORT_REGEX =
            Regex("""^import\s+ksl\.app\.orchestrator\..*$""", RegexOption.MULTILINE)
    }
}
