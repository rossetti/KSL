package ksl.examples.decision.tutorial.doc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  The tutorial's code blocks are compile-verified, and this is what makes that claim true.
 *
 *  A snippet *host* on its own is not enough. `SimoptTutorialSnippets.kt` — the equivalent
 *  file for `ksl-simopt-tutorial` — is compile-only, and nothing checks that the tutorial's
 *  blocks actually appear in it. That means the host can compile perfectly while the
 *  tutorial shows an API that no longer exists: a green check measuring nothing, which is
 *  the defect shape this subsystem has hit repeatedly.
 *
 *  So this reads the tutorial, extracts every Kotlin block, and requires each one to appear
 *  in the host. Comparison ignores indentation and blank lines, because a snippet sits at
 *  column 0 in Markdown and inside a nested object in Kotlin; everything else must match
 *  token for token.
 */
class DecisionTutorialSnippetsTest {

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative")
            .map(::File).firstOrNull { it.isFile }
            ?: fail("cannot locate $relative from ${File(".").absolutePath}")

    /** Every ```kotlin fenced block in the document, in order. */
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
        check(!inBlock) { "the tutorial has an unterminated ```kotlin fence" }
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
    fun everyCodeBlockInTheTutorialAppearsInTheCompiledSnippetHost() {
        val tutorial = repoFile("docs/guides/ksl-decision-tutorial.md").readText()
        val host = significant(
            repoFile("KSLExamples/src/test/kotlin/ksl/examples/decision/tutorial/doc/DecisionTutorialSnippets.kt")
                .readText())

        val blocks = kotlinBlocks(tutorial)
        println()
        println("ksl-decision-tutorial.md: ${blocks.size} Kotlin blocks; host has ${host.size} significant lines")

        val missing = mutableListOf<String>()
        for ((i, block) in blocks.withIndex()) {
            val lines = significant(block)
            if (!contains(host, lines)) {
                missing += "block ${i + 1} (${lines.size} lines), starting: ${lines.firstOrNull()}"
            }
        }
        missing.forEach { println("  MISSING  $it") }

        assertTrue(blocks.size >= 10,
            "only ${blocks.size} code blocks were found in the tutorial. Either it lost its " +
                "examples or the fence parser stopped matching them, and in both cases the rest " +
                "of this test would pass by having nothing to check")
        assertTrue(missing.isEmpty(),
            "these tutorial snippets do not appear in DecisionTutorialSnippets.kt, so they are " +
                "not compile-verified and may reference an API that no longer exists: $missing")
    }

    /**
     *  Every companion file the tutorial's Appendix A promises must exist, and every one it
     *  says has a `main` must have one.
     *
     *  A tutorial that sends a reader to a file that is not there fails at the first thing
     *  they try, and Appendix A is the first thing a hands-on reader uses.
     */
    @Test
    fun everyCompanionFileAppendixAPromisesExistsAndHasTheMainItClaims() {
        val tutorial = repoFile("docs/guides/ksl-decision-tutorial.md").readText()
        val appendix = tutorial.substringAfter("## Appendix A").substringBefore("## Appendix B")

        // Rows look like: | `File.kt` | role | yes/no |
        val row = Regex("""\|\s*`([A-Za-z0-9_]+\.kt)`\s*\|[^|]*\|\s*(yes|no)\s*\|""")
        val rows = row.findAll(appendix).map { it.groupValues[1] to (it.groupValues[2] == "yes") }.toList()
        println()
        rows.forEach { (f, hasMain) -> println("  $f  main=$hasMain") }

        assertTrue(rows.size >= 5, "Appendix A lists only ${rows.size} companion files")
        val dir = "KSLExamples/src/main/kotlin/ksl/examples/decision/tutorial"
        for ((fileName, claimsMain) in rows) {
            val f = repoFile("$dir/$fileName")
            val text = f.readText()
            val hasMain = text.contains(Regex("""^fun main\(""", RegexOption.MULTILINE))
            assertTrue(claimsMain == hasMain,
                "Appendix A says $fileName ${if (claimsMain) "has" else "has no"} a main function, " +
                    "and the file ${if (hasMain) "has" else "has no"} one")
        }
    }

    /** The tutorial must be listed where a reader would look for it. */
    @Test
    fun theTutorialIsListedInTheGuidesIndex() {
        val index = repoFile("docs/guides/README.md").readText()
        assertTrue(index.contains("ksl-decision-tutorial.md"),
            "docs/guides/README.md does not link ksl-decision-tutorial.md, so the tutorial " +
                "exists and nobody reading the index would find it")
    }
}
