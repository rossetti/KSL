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
 *  Syntactic validator for the defining-relations of a
 *  custom-fraction two-level design (see `Fraction.Custom`).
 *
 *  A defining relation is a sequence of uppercase letters that names
 *  the factors whose product equals the identity I in the algebra of
 *  factor effects.  Letters refer to factors by position: A = factor 1,
 *  B = factor 2, …, where position is the index in the document's
 *  `factors` list.  A 2^(5-2) design (k = 5, p = 2) needs exactly two
 *  generators, for example 'ABCD' and 'ABE'.
 *
 *  This validator covers **syntax** only:
 *  - Each relation is a non-empty string.
 *  - All letters are uppercase A..Z within the legal range for the
 *    declared factor count.
 *  - No letter repeats within a single relation (each generator is a
 *    *set* of factors, not a multiset).
 *  - The relation count matches the fraction exponent.
 *
 *  **Group-theoretic validity** — does the chosen set of generators
 *  realise a non-degenerate 2^(k-p) fraction without collapsing
 *  unintentionally — is not checked here.  That is the substrate's
 *  responsibility at engine-build time
 *  (`TwoLevelFactorialDesign.fractionalIterator(...)` in Phase E2);
 *  errors surface as runtime exceptions on submit.
 *
 *  Exposed as an `object` so the Phase E7 GUI editor can call it
 *  directly for live feedback (red/green badges next to each
 *  relation field).
 */
object DefiningRelationValidator {

    /** Outcome of [validate].  [Ok] carries the parsed generators as
     *  letter sets (useful for downstream engine glue); [Invalid]
     *  carries one human-readable message per problem found. */
    sealed class Result {
        data class Ok(val parsed: List<Set<Char>>) : Result()
        data class Invalid(val errors: List<String>) : Result()
    }

    /**
     *  Validate [relations] against the declared factor count and
     *  fraction exponent.  All errors are collected before returning —
     *  a single bad relation does not short-circuit reporting of the
     *  others.
     */
    fun validate(
        relations: List<String>,
        numFactors: Int,
        fractionExponent: Int
    ): Result {
        val errors = mutableListOf<String>()

        if (relations.size != fractionExponent) {
            errors.add(
                "expected $fractionExponent defining relation(s), got ${relations.size}"
            )
        }

        val maxLetter: Char = if (numFactors in 1..26) {
            ('A'.code + numFactors - 1).toChar()
        } else {
            // numFactors outside 1..26 is itself an error; let the
            // caller learn that without also reporting per-relation
            // letter-range failures (which would be confusing).
            errors.add(
                "numFactors must be in 1..26 to use letter generators; got $numFactors"
            )
            return Result.Invalid(errors)
        }

        val parsed = mutableListOf<Set<Char>>()
        for ((idx, raw) in relations.withIndex()) {
            val label = "relation #${idx + 1} ('$raw')"
            if (raw.isEmpty()) {
                errors.add("$label is empty")
                parsed.add(emptySet())
                continue
            }
            val seen = mutableSetOf<Char>()
            val duplicates = mutableSetOf<Char>()
            var hasBadChar = false
            for (c in raw) {
                if (c !in 'A'..'Z') {
                    errors.add(
                        "$label contains non-uppercase-letter character '$c'"
                    )
                    hasBadChar = true
                    continue
                }
                if (c > maxLetter) {
                    errors.add(
                        "$label uses letter '$c' beyond the legal range " +
                            "A..$maxLetter for $numFactors factors"
                    )
                    hasBadChar = true
                    continue
                }
                if (!seen.add(c)) duplicates.add(c)
            }
            if (duplicates.isNotEmpty()) {
                errors.add(
                    "$label repeats letter(s) " +
                        duplicates.sorted().joinToString(", ") { "'$it'" }
                )
            }
            // Even when there are errors, capture what we parsed so a
            // partially-valid caller (e.g. the GUI editor) can still
            // highlight the bad characters in the input field.
            if (!hasBadChar && duplicates.isEmpty()) parsed.add(seen)
            else parsed.add(seen - duplicates)
        }

        return if (errors.isEmpty()) Result.Ok(parsed) else Result.Invalid(errors)
    }
}
