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
 * All variants can round-trip through JSON/TOML:
 *
 * - [DynamicPolynomial] — a polynomial penalty (grows with the violation
 *   magnitude and the iteration counter); the memoryless default.
 * - [WithMemory] — retained only for backward compatibility of existing
 *   configuration files. It maps to the same
 *   [ksl.simopt.problem.DynamicPolynomialPenalty]; the former inverse
 *   square-root "memory" factor has been removed because it is not part of
 *   the Park and Kim (2015) penalty.
 * - [ParkKim] — the faithful Park and Kim (2015) Penalty Function with
 *   Memory: a standardized measure of violation plus an appreciation/
 *   depreciation penalty sequence, with a polynomial fallback for the
 *   no-re-sampling regime. Maps to [ksl.simopt.problem.ParkKimPenalty].
 *
 * The [ksl.simopt.problem.PenaltyFunction] base class cannot be persisted in
 * general; only these data-only spec variants have a serializable representation.
 *
 * Sealed-class polymorphic serialization is used: the JSON/TOML output
 * carries a `"type"` discriminator with values `"withMemory"`,
 * `"dynamicPolynomial"`, or `"parkKim"`.
 */
@Serializable
sealed class PenaltyFunctionSpec {

    /**
     * Retained only for backward compatibility of existing configuration
     * files. Maps to [ksl.simopt.problem.DynamicPolynomialPenalty] — the
     * same polynomial penalty as [DynamicPolynomial]. The former
     * `1/sqrt(sampleCount)` factor has been removed (it is not part of the
     * Park and Kim (2015) penalty and weakened the penalty as evidence of
     * infeasibility grew). Prefer [DynamicPolynomial]. A faithful Penalty
     * Function with Memory is planned for a future release.
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
            "be > 0 and finite.  Default: 1.0 (linear)."
        )
        val violationExponent: Double = 1.0
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
            "be > 0 and finite.  Default: 1.0 (linear)."
        )
        val violationExponent: Double = 1.0
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
     * The faithful Park and Kim (2015) Penalty Function with Memory. Maps to
     * [ksl.simopt.problem.ParkKimPenalty] with an
     * [ksl.simopt.problem.AppreciateDepreciateSequence] (Eq. 4) and a
     * [ksl.simopt.problem.DynamicPolynomialPenalty] fallback (used until a
     * design point has been re-sampled, and whenever the regime never
     * re-samples).
     *
     * @property appreciationFactor a: the penalty-sequence growth factor when
     *           a solution looks infeasible; must be `> 1` and finite
     * @property depreciationFactor d: the decay factor when a solution looks
     *           feasible; must be in `(0, 1)`
     * @property initialLambda the starting penalty-sequence value; must be
     *           `> 0` and finite
     * @property fallbackBasePenalty the fallback polynomial's scaling
     *           coefficient C; must be `> 0` and finite
     * @property fallbackIterationExponent the fallback polynomial's iteration
     *           exponent (`beta`); must be `>= 0` and finite
     * @property fallbackViolationExponent the fallback polynomial's violation
     *           exponent (`alpha`); must be `> 0` and finite
     */
    @Serializable
    @SerialName("parkKim")
    data class ParkKim(
        @TomlComment(
            "Number. Penalty-sequence appreciation factor a, applied when a\n" +
            "solution looks infeasible.  Must be > 1 and finite.  Default: 2.0."
        )
        val appreciationFactor: Double = 2.0,

        @TomlComment(
            "Number. Penalty-sequence depreciation factor d, applied when a\n" +
            "solution looks feasible.  Must be in (0, 1).  Default: 0.5."
        )
        val depreciationFactor: Double = 0.5,

        @TomlComment(
            "Number. Initial penalty-sequence value (lambda) on a solution's\n" +
            "first visit.  Must be > 0 and finite.  Default: 1.0."
        )
        val initialLambda: Double = 1.0,

        @TomlComment(
            "Number. Fallback polynomial scaling coefficient C, used until a\n" +
            "design point is re-sampled.  Must be > 0 and finite.  Default: 100.0."
        )
        val fallbackBasePenalty: Double = 100.0,

        @TomlComment(
            "Number. Fallback polynomial iteration exponent (β).  Must be >= 0\n" +
            "and finite.  Default: 1.0."
        )
        val fallbackIterationExponent: Double = 1.0,

        @TomlComment(
            "Number. Fallback polynomial violation exponent (α).  Must be > 0\n" +
            "and finite.  Default: 1.0 (linear)."
        )
        val fallbackViolationExponent: Double = 1.0
    ) : PenaltyFunctionSpec() {
        init {
            require(appreciationFactor > 1.0 && appreciationFactor.isFinite()) {
                "appreciationFactor must be > 1 and finite; was $appreciationFactor"
            }
            require(depreciationFactor > 0.0 && depreciationFactor < 1.0) {
                "depreciationFactor must be in (0, 1); was $depreciationFactor"
            }
            require(initialLambda > 0.0 && initialLambda.isFinite()) {
                "initialLambda must be > 0 and finite; was $initialLambda"
            }
            require(fallbackBasePenalty > 0.0 && fallbackBasePenalty.isFinite()) {
                "fallbackBasePenalty must be > 0 and finite; was $fallbackBasePenalty"
            }
            require(fallbackIterationExponent >= 0.0 && fallbackIterationExponent.isFinite()) {
                "fallbackIterationExponent must be >= 0 and finite; was $fallbackIterationExponent"
            }
            require(fallbackViolationExponent > 0.0 && fallbackViolationExponent.isFinite()) {
                "fallbackViolationExponent must be > 0 and finite; was $fallbackViolationExponent"
            }
        }
    }
}
