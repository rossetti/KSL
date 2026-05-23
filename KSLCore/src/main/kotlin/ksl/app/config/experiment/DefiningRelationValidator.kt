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

/**
 *  Syntactic validator for the words of a custom-fraction two-level
 *  design (see `Fraction.Custom`).
 *
 *  A defining relation has the algebraic form I = w1 = w2 = ...
 *  where each word w_i is a product of factor indices.  This
 *  validator covers syntax only:
 *
 *  - Each word is non-empty.
 *  - Every integer index lies in 1..numFactors.
 *  - No index repeats within a single word (each word is a *set* of
 *    factors, not a multiset).
 *  - The word count matches the declared fraction exponent.
 *
 *  **Group-theoretic validity** — whether the chosen words realise
 *  a non-degenerate 2^(k-p) fraction without collapsing
 *  unintentionally — is not checked here.  That is the substrate's
 *  responsibility at engine-build time
 *  (`TwoLevelFactorialDesign.fractionalIterator(...)`); errors
 *  surface as runtime exceptions on submit.
 *
 *  Exposed as an `object` so the GUI editor can call it directly
 *  for live feedback (red/green badges next to each word field).
 */
object DefiningRelationValidator {

    /** Outcome of [validate].  [Ok] carries the parsed words as
     *  integer sets (useful for downstream engine glue); [Invalid]
     *  carries one human-readable message per problem found. */
    sealed class Result {
        data class Ok(val parsed: List<Set<Int>>) : Result()
        data class Invalid(val errors: List<String>) : Result()
    }

    /**
     *  Validate [words] against the declared factor count and
     *  fraction exponent.  All errors are collected before
     *  returning — a single bad word does not short-circuit
     *  reporting of the others.
     */
    fun validate(
        words: List<List<Int>>,
        numFactors: Int,
        fractionExponent: Int
    ): Result {
        val errors = mutableListOf<String>()

        require(numFactors >= 1) {
            "numFactors must be >= 1; got $numFactors"
        }

        if (words.size != fractionExponent) {
            errors.add(
                "expected $fractionExponent word(s), got ${words.size}"
            )
        }

        val parsed = mutableListOf<Set<Int>>()
        for ((idx, raw) in words.withIndex()) {
            val label = "word #${idx + 1} ($raw)"
            if (raw.isEmpty()) {
                errors.add("$label is empty")
                parsed.add(emptySet())
                continue
            }
            val seen = mutableSetOf<Int>()
            val duplicates = mutableSetOf<Int>()
            var hasBadIndex = false
            for (i in raw) {
                if (i < 1 || i > numFactors) {
                    errors.add(
                        "$label uses index $i outside the legal range " +
                            "1..$numFactors"
                    )
                    hasBadIndex = true
                    continue
                }
                if (!seen.add(i)) duplicates.add(i)
            }
            if (duplicates.isNotEmpty()) {
                errors.add(
                    "$label repeats index(es) " +
                        duplicates.sorted().joinToString(", ")
                )
            }
            // Even when there are errors, capture what we parsed so a
            // partially-valid caller (e.g. the GUI editor) can still
            // highlight the bad characters in the input field.
            if (!hasBadIndex && duplicates.isEmpty()) parsed.add(seen)
            else parsed.add(seen - duplicates)
        }

        return if (errors.isEmpty()) Result.Ok(parsed) else Result.Invalid(errors)
    }
}
