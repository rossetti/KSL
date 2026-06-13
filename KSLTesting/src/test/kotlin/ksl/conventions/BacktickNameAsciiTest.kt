package ksl.conventions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * Source-hygiene guard: no Kotlin function declared with a backtick identifier
 * may contain a non-ASCII character in its name.
 *
 * Backtick function names become part of the generated `.class` file name. A
 * non-ASCII character there (an em-dash, en-dash, smart quote, etc.) cannot be
 * encoded by the JVM/filesystem under common platform encodings and breaks the
 * build. This test walks every module's Kotlin sources and fails fast with the
 * exact offenders so the problem is caught at test time rather than at compile
 * or class-load time.
 *
 * If this test fails, replace the offending character with its ASCII
 * equivalent (e.g. an em-dash with a plain hyphen `-`).
 */
class BacktickNameAsciiTest {

    @Test
    fun `no backtick function name contains a non-ASCII character`() {
        val root = repoRoot()
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" }

        val funKeyword = Regex("""\bfun\b""")
        val backtickName = Regex("`([^`]+)`")

        val violations = mutableListOf<String>()
        for (file in sources) {
            file.useLines { lines ->
                lines.forEachIndexed { idx, raw ->
                    val line = raw.trimStart()
                    // Skip comment lines so backticked prose in KDoc is ignored.
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                    if (!funKeyword.containsMatchIn(line)) return@forEachIndexed
                    for (m in backtickName.findAll(raw)) {
                        val name = m.groupValues[1]
                        if (name.any { it.code > 0x7F }) {
                            val rel = file.relativeTo(root).path
                            violations += "$rel:${idx + 1}: `$name`"
                        }
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Found backtick function name(s) with non-ASCII characters.")
                appendLine("Replace the non-ASCII character(s) with an ASCII equivalent (e.g. em-dash -> '-'):")
                violations.forEach { appendLine("  $it") }
            }
        )
    }

    /** Walk up from the test working directory to the repository root (the dir holding `gradlew`). */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "gradlew").exists()) return dir
            dir = dir.parentFile
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
