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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Tests for [DefiningRelationValidator].  Syntactic checks only;
 *  group-theoretic validity is the engine's responsibility at
 *  submit time.
 */
class DefiningRelationValidatorTest {

    @Test
    fun `valid single-relation half fraction parses cleanly`() {
        // 2^(4-1) design: one generator 'ABCD'.
        val r = DefiningRelationValidator.validate(
            relations = listOf("ABCD"),
            numFactors = 4,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Ok>(r)
        assertEquals(listOf(setOf('A', 'B', 'C', 'D')), r.parsed)
    }

    @Test
    fun `valid two-relation quarter fraction parses cleanly`() {
        // 2^(5-2) design: two generators.
        val r = DefiningRelationValidator.validate(
            relations = listOf("ABCD", "ABE"),
            numFactors = 5,
            fractionExponent = 2
        )
        assertIs<DefiningRelationValidator.Result.Ok>(r)
        assertEquals(
            listOf(setOf('A', 'B', 'C', 'D'), setOf('A', 'B', 'E')),
            r.parsed
        )
    }

    @Test
    fun `empty relation string is rejected`() {
        val r = DefiningRelationValidator.validate(
            relations = listOf(""),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "empty" in it })
    }

    @Test
    fun `lowercase letter is rejected`() {
        val r = DefiningRelationValidator.validate(
            relations = listOf("aBC"),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "non-uppercase-letter character 'a'" in it })
    }

    @Test
    fun `digit in relation is rejected`() {
        val r = DefiningRelationValidator.validate(
            relations = listOf("AB2"),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "'2'" in it })
    }

    @Test
    fun `letter beyond declared factor count is rejected`() {
        // 3 factors → max letter is 'C'; using 'E' is illegal.
        val r = DefiningRelationValidator.validate(
            relations = listOf("ACE"),
            numFactors = 3,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "letter 'E' beyond the legal range A..C" in it })
    }

    @Test
    fun `repeated letter within a single relation is rejected`() {
        val r = DefiningRelationValidator.validate(
            relations = listOf("ABCA"),
            numFactors = 4,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "repeats letter" in it && "'A'" in it })
    }

    @Test
    fun `wrong relation count is rejected`() {
        // 2^(5-2) expects 2 relations; providing 1 is wrong.
        val r = DefiningRelationValidator.validate(
            relations = listOf("ABCD"),
            numFactors = 5,
            fractionExponent = 2
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "expected 2 defining relation(s), got 1" in it })
    }

    @Test
    fun `multiple errors across multiple relations are all reported`() {
        // Two relations, both broken in different ways.
        val r = DefiningRelationValidator.validate(
            relations = listOf("AAB", "abc"),
            numFactors = 3,
            fractionExponent = 2
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        // Relation #1: repeats 'A'.
        assertTrue(r.errors.any { "relation #1" in it && "repeats" in it })
        // Relation #2: lowercase letters.
        assertTrue(r.errors.any { "relation #2" in it && "'a'" in it })
    }

    @Test
    fun `numFactors out of letter range short-circuits per-relation checks`() {
        // 27 factors can't all be encoded in single letters; the
        // validator reports the range issue and does not try to
        // resolve any relations.
        val r = DefiningRelationValidator.validate(
            relations = listOf("ABCD"),
            numFactors = 27,
            fractionExponent = 1
        )
        assertIs<DefiningRelationValidator.Result.Invalid>(r)
        assertTrue(r.errors.any { "numFactors must be in 1..26" in it })
    }
}
