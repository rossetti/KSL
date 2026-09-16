package ksl.examples.general.utilities.fitting.mixture.doc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  The tutorial's code blocks are compile-verified, and this is what makes that claim true.
 *
 *  A snippet *host* on its own is not enough. Compiling `MixtureTutorialSnippets.kt` proves that
 *  the code in that file references real API; it proves nothing about the tutorial, which is a
 *  separate document that could drift away from it at any time. The failure that leaves behind is
 *  the worst kind: a green check next to a guide showing an API that no longer exists.
 *
 *  So this reads the tutorial, extracts every Kotlin block, and requires each one to appear in
 *  the host. Comparison ignores indentation and blank lines, because a snippet sits at column 0
 *  in Markdown and inside an object in Kotlin; everything else must match token for token.
 */
class MixtureTutorialSnippetsTest {

    private companion object {
        const val TUTORIAL = "docs/guides/ksl-mixture-tutorial.md"
        const val GUIDE = "docs/guides/ksl-mixture.md"
        const val HOST =
            "KSLExamples/src/test/kotlin/ksl/examples/general/utilities/fitting/mixture/doc/" +
                "MixtureTutorialSnippets.kt"
        const val EXAMPLES =
            "KSLExamples/src/main/kotlin/ksl/examples/general/utilities/fitting/mixture"
    }

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative")
            .map(::File).firstOrNull { it.isFile }
            ?: fail("cannot locate $relative from ${File(".").absolutePath}")

    /** The same search for a directory: Gradle starts in the module, IntelliJ at the root. */
    private fun repoDir(relative: String): File =
        listOf(relative, "../$relative")
            .map(::File).firstOrNull { it.isDirectory }
            ?: fail("cannot locate directory $relative from ${File(".").absolutePath}")

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

    /** Does the needle appear as a contiguous run of significant lines in the haystack? */
    private fun contains(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..(haystack.size - needle.size)) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }

    /**
     *  Both documents are checked against the same host, so a snippet shared between the guide
     *  and the tutorial is written once and verified once.
     */
    private fun assertBlocksAppearInHost(document: String, leastBlocks: Int) {
        val host = significant(repoFile(HOST).readText())
        val blocks = kotlinBlocks(repoFile(document).readText())

        println()
        println("$document: ${blocks.size} Kotlin blocks; host has ${host.size} significant lines")

        val missing = mutableListOf<String>()
        for ((i, block) in blocks.withIndex()) {
            val lines = significant(block)
            if (!contains(host, lines)) {
                missing += "block ${i + 1} (${lines.size} lines), starting: ${lines.firstOrNull()}"
            }
        }
        missing.forEach { println("  MISSING  $it") }

        assertTrue(
            blocks.size >= leastBlocks,
            "only ${blocks.size} code blocks were found in $document, fewer than the $leastBlocks " +
                "expected. Either it lost its examples or the fence parser stopped matching them, " +
                "and in both cases the rest of this check would pass by having nothing to check"
        )
        assertTrue(
            missing.isEmpty(),
            "these snippets in $document do not appear in MixtureTutorialSnippets.kt, so they are " +
                "not compile-verified and may reference an API that no longer exists: $missing"
        )
    }

    @Test
    fun everyCodeBlockInTheTutorialAppearsInTheCompiledSnippetHost() {
        assertBlocksAppearInHost(TUTORIAL, leastBlocks = 10)
    }

    @Test
    fun everyCodeBlockInTheGuideAppearsInTheCompiledSnippetHost() {
        assertBlocksAppearInHost(GUIDE, leastBlocks = 8)
    }

    /**
     *  Every runnable file the tutorial names must exist and must have a `main`.
     *
     *  A tutorial that sends a reader to a file that is not there fails at the first thing they
     *  try. Each part opens by naming its runnable file, and Appendix A lists them all, so the
     *  document promises these paths in two places.
     */
    @Test
    fun everyRunnableFileTheTutorialNamesExistsAndHasAMain() {
        val tutorial = repoFile(TUTORIAL).readText()
        val named = Regex("`(Example\\d+[A-Za-z]*\\.kt)`").findAll(tutorial)
            .map { it.groupValues[1] }.toSortedSet()

        assertTrue(
            named.size == 8,
            "expected the tutorial to name 8 runnable files, found ${named.size}: $named"
        )

        val problems = mutableListOf<String>()
        for (name in named) {
            val file = File(repoDir(EXAMPLES), name)
            when {
                !file.isFile -> problems += "$name does not exist"
                !file.readText().contains("fun main(") -> problems += "$name has no main"
            }
        }
        assertTrue(problems.isEmpty(), "the tutorial points at files that will not work: $problems")
    }

    /**
     *  The datasets the tutorial tells the reader to open must be there.
     *
     *  The guide says the data ships with the examples and can be opened or plotted. That claim is
     *  about two files on disk, and nothing else in the build would notice them going missing.
     */
    @Test
    fun theShippedDatasetsTheTutorialPromisesExist() {
        val dir = "KSLExamples/chapterFiles/Appendix-Distribution Fitting"
        for (name in listOf("ReceptionDeskServiceTimes.txt", "ReceptionDeskHoldOut.txt")) {
            val file = File(repoDir(dir), name)
            assertTrue(
                file.isFile,
                "$name is missing from $dir, but the tutorial tells the reader to open it"
            )
        }
    }
}
