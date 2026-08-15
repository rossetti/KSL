package ksl.modeling.decision.doc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  `docs/guides/README.md` states that "library-guide code snippets are compile-verified against
 *  the source on every build". For a guide, that is true only if two things hold: the snippet host
 *  compiles, and the host **actually contains what the guide shows**. The build gives the first for
 *  free. Nothing gave the second.
 *
 *  Without it, `DecisionGuideSnippets.kt` can compile perfectly while `ksl-decision.md` shows an API
 *  that no longer exists — which is the same defect shape this subsystem has hit repeatedly: a green
 *  check that measures nothing. So this test reads the guide, extracts every Kotlin block, and
 *  requires each one to appear in the host.
 *
 *  Comparison ignores indentation and blank lines, because a snippet sits at column 0 in Markdown
 *  and inside a nested object in Kotlin, and requiring them to match on whitespace would make the
 *  test fail for a reason nobody cares about. Everything else must match exactly, token for token.
 */
class DecisionGuideSnippetsTest {

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative")
            .map(::File).firstOrNull { it.isFile }
            ?: fail("cannot locate $relative from ${File(".").absolutePath}")

    /** Every ```kotlin fenced block in the guide, in order. */
    private fun kotlinBlocks(markdown: String): List<String> {
        val blocks = mutableListOf<String>()
        var inBlock = false
        val current = StringBuilder()
        for (line in markdown.lines()) {
            when {
                !inBlock && line.trimEnd() == "```kotlin" -> { inBlock = true; current.setLength(0) }
                inBlock && line.trimEnd() == "```" -> { inBlock = false; blocks += current.toString() }
                inBlock -> current.appendLine(line)
            }
        }
        check(!inBlock) { "the guide has an unterminated ```kotlin fence" }
        return blocks
    }

    /** Significant lines only: trimmed, blanks dropped. */
    private fun significant(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Does [needle] appear as a contiguous run of significant lines in [haystack]? */
    private fun contains(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..(haystack.size - needle.size)) {
            if ((needle.indices).all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }

    @Test
    fun everyCodeBlockInTheGuideAppearsInTheCompiledSnippetHost() {
        val guide = repoFile("docs/guides/ksl-decision.md").readText()
        val host = significant(
            repoFile("KSLCore/src/test/kotlin/ksl/modeling/decision/doc/DecisionGuideSnippets.kt").readText())

        val blocks = kotlinBlocks(guide)
        println()
        println("ksl-decision.md: ${blocks.size} Kotlin blocks; host has ${host.size} significant lines")

        val missing = mutableListOf<String>()
        for ((i, block) in blocks.withIndex()) {
            val lines = significant(block)
            if (!contains(host, lines)) {
                missing += "block ${i + 1} (${lines.size} lines), starting: ${lines.firstOrNull()}"
            }
        }
        missing.forEach { println("  MISSING  $it") }

        assertTrue(blocks.size >= 10,
            "only ${blocks.size} code blocks were found in the guide. Either the guide lost its " +
                "examples or the fence parser stopped matching them, and in both cases the rest of " +
                "this test would pass by having nothing to check")
        assertTrue(missing.isEmpty(),
            "these guide snippets do not appear in DecisionGuideSnippets.kt, so they are not " +
                "compile-verified and may reference an API that no longer exists: $missing")
    }

    /** The guide must be listed where a reader would look for it. */
    @Test
    fun theGuideIsListedInTheGuidesIndex() {
        val index = repoFile("docs/guides/README.md").readText()
        assertTrue(index.contains("ksl-decision.md"),
            "docs/guides/README.md does not link ksl-decision.md, so the guide exists and nobody " +
                "reading the index would find it")
    }
}
