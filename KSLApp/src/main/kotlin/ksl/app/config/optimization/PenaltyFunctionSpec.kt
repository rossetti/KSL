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

package ksl.app.config.optimization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 * Serializable selection of a penalty function used by an optimization
 * problem to penalize constraint violations.
 *
 * Mirrors the two engine implementations of
 * [ksl.simopt.problem.PenaltyFunctionIfc] that are constructed from plain
 * data and therefore can round-trip through JSON/TOML:
 *
 * - [WithMemory] — mirrors
 *   [ksl.simopt.problem.PenaltyFunctionWithMemory], whose memory factor
 *   dampens stochastic noise by scaling with the inverse square root of
 *   the response sample count;
 * - [DynamicPolynomial] — mirrors
 *   [ksl.simopt.problem.DynamicPolynomialPenalty], a polynomial penalty
 *   that grows with both the violation magnitude and the iteration
 *   counter.
 *
 * The [ksl.simopt.problem.PenaltyFunctionIfc] interface itself is a
 * `fun interface` and so cannot be persisted in general; only the two
 * concrete data-only implementations have a serializable representation.
 *
 * Sealed-class polymorphic serialization is used: the JSON/TOML output
 * carries a `"type"` discriminator with values `"withMemory"` or
 * `"dynamicPolynomial"`.
 */
@Serializable
sealed class PenaltyFunctionSpec {

    /**
     * Mirrors [ksl.simopt.problem.PenaltyFunctionWithMemory].
     *
     * The penalty grows polynomially with the violation magnitude and the
     * iteration counter, but is dampened by `1/sqrt(sampleCount)` so that
     * stochastic noise on response measurements does not infinitely
     * penalize boundary solutions.
     *
     * @property basePenalty scaling coefficient (C); must be `> 0` and finite
     * @property iterationExponent power applied to the iteration counter
     *           (`beta`); must be `>= 0` and finite
     * @property violationExponent power applied to the violation magnitude
     *           (`alpha`); must be `> 0` and finite
     */
    @Serializable
    @SerialName("withMemory")
    data class WithMemory(
        @TomlComment(
            "Number. Scaling coefficient C of the penalty.  Must be > 0\n" +
            "and finite.  Default: 100.0."
        )
        val basePenalty: Double = 100.0,

        @TomlComment(
            "Number. Power applied to the iteration counter (β).  Must be\n" +
            ">= 0 and finite.  Default: 1.0."
        )
        val iterationExponent: Double = 1.0,

        @TomlComment(
            "Number. Power applied to the violation magnitude (α).  Must\n" +
            "be > 0 and finite.  Default: 2.0."
        )
        val violationExponent: Double = 2.0
    ) : PenaltyFunctionSpec() {
        init {
            require(basePenalty > 0.0 && basePenalty.isFinite()) {
                "basePenalty must be > 0 and finite; was $basePenalty"
            }
            require(iterationExponent >= 0.0 && iterationExponent.isFinite()) {
                "iterationExponent must be >= 0 and finite; was $iterationExponent"
            }
            require(violationExponent > 0.0 && violationExponent.isFinite()) {
                "violationExponent must be > 0 and finite; was $violationExponent"
            }
        }
    }

    /**
     * Mirrors [ksl.simopt.problem.DynamicPolynomialPenalty].
     *
     * A polynomial penalty that scales with both the violation magnitude
     * and the iteration counter, with no sample-count dampening; suitable
     * for deterministic linear and functional constraints whose violation
     * is computed exactly rather than estimated.
     *
     * @property basePenalty scaling coefficient (C); must be `> 0` and finite
     * @property iterationExponent power applied to the iteration counter
     *           (`beta`); must be `>= 0` and finite
     * @property violationExponent power applied to the violation magnitude
     *           (`alpha`); must be `> 0` and finite
     */
    @Serializable
    @SerialName("dynamicPolynomial")
    data class DynamicPolynomial(
        @TomlComment(
            "Number. Scaling coefficient C of the penalty.  Must be > 0\n" +
            "and finite.  Default: 100.0."
        )
        val basePenalty: Double = 100.0,

        @TomlComment(
            "Number. Power applied to the iteration counter (β).  Must be\n" +
            ">= 0 and finite.  Default: 1.0."
        )
        val iterationExponent: Double = 1.0,

        @TomlComment(
            "Number. Power applied to the violation magnitude (α).  Must\n" +
            "be > 0 and finite.  Default: 2.0."
        )
        val violationExponent: Double = 2.0
    ) : PenaltyFunctionSpec() {
        init {
            require(basePenalty > 0.0 && basePenalty.isFinite()) {
                "basePenalty must be > 0 and finite; was $basePenalty"
            }
            require(iterationExponent >= 0.0 && iterationExponent.isFinite()) {
                "iterationExponent must be >= 0 and finite; was $iterationExponent"
            }
            require(violationExponent > 0.0 && violationExponent.isFinite()) {
                "violationExponent must be > 0 and finite; was $violationExponent"
            }
        }
    }
}
