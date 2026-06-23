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

package ksl.app.config.experiment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Tests for [DefiningRelationValidator].  Syntactic checks only;
 *  group-theoretic validity is the engine's responsibility at
 *  submit time.
 */
class DefiningRelationValidatorTest {

    @Test
    fun `valid single-word half fraction parses cleanly`() {
        // 2^(4-1) design: one word [1,2,3,4].
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(1, 2, 3, 4)),
            numFactors = 4,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Ok>(r)
        assertEquals(listOf(setOf(1, 2, 3, 4)), r.parsed)
    }

    @Test
    fun `valid two-word quarter fraction parses cleanly`() {
        // 2^(5-2) design: two words.
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(1, 2, 3, 4), listOf(1, 2, 5)),
            numFactors = 5,
            fractionExponent = 2
        )
        assertIs<DefiningRelationValidator.Result.Ok>(r)
        assertEquals(
            listOf(setOf(1, 2, 3, 4), setOf(1, 2, 5)),
            r.parsed
        )
    }

    @Test
    fun `empty word is rejected`() {
        val r = DefiningRelationValidator.validate(
            words = listOf(emptyList()),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "empty" in it })
    }

    @Test
    fun `index zero is rejected as out-of-range`() {
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(0, 1, 2)),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "index 0 outside the legal range" in it })
    }

    @Test
    fun `negative index is rejected as out-of-range`() {
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(-1, 2)),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "index -1 outside the legal range" in it })
    }

    @Test
    fun `index beyond declared factor count is rejected`() {
        // 3 factors -> max index is 3; using 5 is illegal.
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(1, 3, 5)),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "index 5 outside the legal range 1..3" in it })
    }

    @Test
    fun `repeated index within a single word is rejected`() {
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(1, 2, 3, 1)),
            numFactors = 4,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "repeats" in it && "1" in it })
    }

    @Test
    fun `wrong word count is rejected`() {
        // 2^(5-2) expects 2 words; providing 1 is wrong.
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(1, 2, 3, 4)),
            numFactors = 5,
            fractionExponent = 2
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "expected 2 word(s), got 1" in it })
    }

    @Test
    fun `multiple errors across multiple words are all reported`() {
        // Two words, both broken in different ways.
        val r = DefiningRelationValidator.validate(
            words = listOf(listOf(1, 1, 2), listOf(1, 2, 9)),
            numFactors = 3,
            fractionExponent = 2
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        // Word #1: repeats 1.
        assertTrue(r.errors.any { "word #1" in it && "repeats" in it })
        // Word #2: 9 out-of-range.
        assertTrue(r.errors.any { "word #2" in it && "9" in it })
    }

    @Test
    fun `non-positive numFactors throws`() {
        assertFailsWith<IllegalArgumentException> {
            DefiningRelationValidator.validate(
                words = listOf(listOf(1)),
                numFactors = 0,
                fractionExponent = 1
            )
        }
    }
}
