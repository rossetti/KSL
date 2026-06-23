/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.validation

/**
 * One segment of a tokenized [FieldError.path].  Paths look like
 * `scenarios[3].runOverrides.lengthOfReplication`; tokenization
 * produces an alternating-but-not-strictly sequence of
 * [Name] (field reference) and [Index] (list index) segments.
 */
sealed class PathSegment {
    /** A named field segment, e.g. `runOverrides`. */
    data class Name(val name: String) : PathSegment()

    /** A zero-based list-index segment, e.g. `[3]`. */
    data class Index(val n: Int) : PathSegment()
}

/**
 * Pure utility for tokenizing the dotted/bracketed strings carried by
 * [FieldError.path].  Lives in `KSLCore` so non-Swing consumers (test
 * harnesses, CLI tools, log surfaces) can use it without dragging in a
 * GUI dependency.
 *
 * The parser is forgiving: malformed bracket sections are treated as
 * literal name characters rather than raising errors.  Validation
 * paths originate inside the substrate (the validators construct them
 * programmatically), so well-formed input is the common case and a
 * loud failure on garbage input would convert a display problem into a
 * crash.
 */
object PathParser {

    /**
     * Splits [path] into its constituent segments.
     *
     * Examples:
     *  - `"scenarios[3].runOverrides.lengthOfReplication"` →
     *    [Name("scenarios"), Index(3), Name("runOverrides"),
     *    Name("lengthOfReplication")].
     *  - `"bundleRefs[1].paths[0]"` →
     *    [Name("bundleRefs"), Index(1), Name("paths"), Index(0)].
     *  - `""` → empty list.
     */
    fun parse(path: String): List<PathSegment> {
        if (path.isEmpty()) return emptyList()
        val out = mutableListOf<PathSegment>()
        val current = StringBuilder()
        var i = 0
        while (i < path.length) {
            when (val c = path[i]) {
                '.' -> {
                    flushName(current, out)
                    i++
                }
                '[' -> {
                    flushName(current, out)
                    val close = path.indexOf(']', i + 1)
                    if (close < 0) {
                        // Unclosed bracket: treat the rest literally.
                        current.append(path.substring(i))
                        i = path.length
                    } else {
                        val inner = path.substring(i + 1, close)
                        val asInt = inner.toIntOrNull()
                        if (asInt != null && asInt >= 0) {
                            out.add(PathSegment.Index(asInt))
                        } else {
                            // Non-numeric bracket contents: treat as a literal name segment.
                            out.add(PathSegment.Name("[$inner]"))
                        }
                        i = close + 1
                    }
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        flushName(current, out)
        return out
    }

    /**
     * True when [prefix] is at the same segment boundary as a prefix of
     * [candidate] — i.e. either equals it or names an ancestor in the
     * dotted-bracket tree.
     *
     * Examples:
     *  - `isAtOrBelow("scenarios[3]", "scenarios[3]")` → true.
     *  - `isAtOrBelow("scenarios[3]", "scenarios[3].runOverrides.x")` → true.
     *  - `isAtOrBelow("scenarios[3]", "scenarios[30].x")` → false.
     *  - `isAtOrBelow("scenarios", "scenarios[3].x")` → true (whole subtree).
     *  - `isAtOrBelow("", anything)` → true (empty prefix matches all).
     */
    fun isAtOrBelow(prefix: String, candidate: String): Boolean {
        if (prefix.isEmpty()) return true
        val prefixSegs = parse(prefix)
        val candidateSegs = parse(candidate)
        if (candidateSegs.size < prefixSegs.size) return false
        for (i in prefixSegs.indices) {
            if (prefixSegs[i] != candidateSegs[i]) return false
        }
        return true
    }

    private fun flushName(buf: StringBuilder, out: MutableList<PathSegment>) {
        if (buf.isNotEmpty()) {
            out.add(PathSegment.Name(buf.toString()))
            buf.clear()
        }
    }
}
