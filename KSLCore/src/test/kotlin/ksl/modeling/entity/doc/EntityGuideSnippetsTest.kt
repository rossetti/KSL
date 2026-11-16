/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.entity.doc

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 *  `docs/guides/README.md` says library-guide snippets are compile-verified against the source on every
 *  build. For `ksl-entity.md` that holds only if the snippet host compiles and the host contains what the
 *  guide shows. The build gives the first; this test gives the second. It reads the guide, extracts every
 *  Kotlin block, and requires each to appear in `EntityGuideSnippets.kt`.
 *
 *  A fence may be indented, as a block inside a list item is, so fences are matched after trimming.
 *  Comparison ignores indentation and blank lines, because a snippet sits at column 0 in Markdown and
 *  inside a class or a process in Kotlin; everything else must match token for token.
 */
class EntityGuideSnippetsTest {

    private fun repoFile(relative: String): File =
        listOf(relative, "../$relative")
            .map(::File).firstOrNull { it.isFile }
            ?: fail("cannot locate $relative from ${File(".").absolutePath}")

    /** Every Kotlin fenced block of [markdown], in order, indented fences included. */
    private fun kotlinBlocks(markdown: String): List<String> {
        val blocks = mutableListOf<String>()
        var inBlock = false
        val current = StringBuilder()
        for (line in markdown.lines()) {
            val fence = line.trim()
            when {
                !inBlock && fence == "```kotlin" -> { inBlock = true; current.setLength(0) }
                inBlock && fence == "```" -> { inBlock = false; blocks += current.toString() }
                inBlock -> current.appendLine(line)
            }
        }
        check(!inBlock) { "the guide has an unterminated kotlin fence" }
        return blocks
    }

    /** Significant lines only: trimmed, blanks dropped. */
    private fun significant(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Does [needle] appear as a contiguous run of significant lines in [haystack]? */
    private fun contains(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..(haystack.size - needle.size)) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return true
        }
        return false
    }

    @Test
    @DisplayName("every Kotlin block of ksl-entity.md appears in the compiled snippet host")
    fun everyBlockIsInTheHost() {
        val guide = repoFile("docs/guides/ksl-entity.md").readText()
        val host = significant(
            repoFile("KSLCore/src/test/kotlin/ksl/modeling/entity/doc/EntityGuideSnippets.kt").readText()
        )
        val blocks = kotlinBlocks(guide)
        val missing = blocks.withIndex()
            .filterNot { (_, block) -> contains(host, significant(block)) }
            .map { (i, block) -> "block ${i + 1}, starting: ${significant(block).firstOrNull()}" }

        assertTrue(
            blocks.size >= 25,
            "only ${blocks.size} Kotlin blocks were found in the guide. Either the guide lost its examples " +
                "or the fence parser stopped matching them, and then this test would pass with nothing to check"
        )
        assertTrue(
            missing.isEmpty(),
            "these blocks of ksl-entity.md are not in EntityGuideSnippets.kt, so nothing compiles them and " +
                "they may show an API that no longer exists: $missing"
        )
    }

    @Test
    @DisplayName("ksl-entity.md is listed in the guides index")
    fun theGuideIsListed() {
        val index = repoFile("docs/guides/README.md").readText()
        assertTrue(
            index.contains("ksl-entity.md"),
            "docs/guides/README.md does not link ksl-entity.md, so a reader of the index would not find it"
        )
    }
}
