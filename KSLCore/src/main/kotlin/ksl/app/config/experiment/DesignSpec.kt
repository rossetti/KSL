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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 *  Specifies how the engine should enumerate design points from the
 *  document's factors.  Four families that mirror the construction
 *  APIs in `ksl.controls.experiments`:
 *
 *  - `FullFactorial` → `FactorialDesign(factors)`
 *  - `TwoLevelFactorial(fraction)` → `TwoLevelFactorialDesign` +
 *    either `designIterator()` (full), `halfFractionIterator(sign)`,
 *    or `fractionalIterator(relation, sign)`
 *  - `CentralComposite(...)` → `CentralCompositeDesign(twoLevelItr,
 *    numFactorialReps, numAxialReps, numCenterReps, axialSpacing)`
 *  - `Manual(points)` → `ExperimentalDesign(factors)` + one
 *    `addDesignPoint(values, numReps)` per row.
 *
 *  Sealed so the codec emits a `type` discriminator + variant-
 *  specific keys.  `ExperimentConfigurationBuilder` (Phase E2 engine
 *  glue) maps each variant to the matching substrate construction
 *  per the bullets above.
 *
 *  **Replications interaction.**  For `FullFactorial`,
 *  `TwoLevelFactorial`, and `Manual` the document-level
 *  `ReplicationSpec` drives the per-point replication count.  For
 *  `CentralComposite`, the spec's three rep knobs
 *  (`numFactorialReps`, `numAxialReps`, `numCenterReps`) override
 *  `ReplicationSpec` entirely — they are the substrate's native
 *  surface for CCD replications, and `ReplicationSpec.Uniform` would
 *  collapse them.  The hosting GUI surfaces this asymmetry by
 *  hiding the document uniform-reps field when CCD is selected.
 */
@Serializable
sealed class DesignSpec {

    /**
     *  Full factorial — every combination of factor levels.  The
     *  number of design points is the product of the factors' level
     *  counts.  Heterogeneous level counts are allowed.  No options;
     *  per-point replications are controlled by the document-level
     *  `ReplicationSpec`.
     */
    @Serializable
    @SerialName("fullFactorial")
    data object FullFactorial : DesignSpec()

    /**
     *  Two-level factorial — every factor must have exactly 2 levels.
     *  [fraction] selects which subset of the full 2^k design the
     *  substrate enumerates: full, half-fraction (with sign), or a
     *  custom fractional design with explicit defining relations.
     *
     *  Maps to `TwoLevelFactorialDesign(factors)` plus the iterator
     *  determined by [fraction].
     */
    @Serializable
    @SerialName("twoLevelFactorial")
    data class TwoLevelFactorial(
        @TomlComment(
            "Selection of which subset of the full 2^k to enumerate.\n" +
            "One of: { type = \"full\" }, { type = \"half\", sign = 1 | -1 },\n" +
            "or { type = \"custom\", relations = [...], sign = 1 | -1 }."
        )
        val fraction: Fraction = Fraction.Full
    ) : DesignSpec()

    /**
     *  Central composite design — factorial core + axial points +
     *  centre point(s).  Used to fit second-order response surface
     *  models (RSM).  Requires every factor to be two-level (the
     *  factorial portion); the substrate then adds 2k axial points
     *  at ± [axialSpacing] and one centre point replicated
     *  [numCenterReps] times.
     *
     *  [axialSpacing] is either the classical rotatable value
     *  (computed from k + the optional [factorialFraction]) or an
     *  explicit user-supplied number.  See
     *  `ksl.controls.experiments.CentralCompositeDesign.rotatableAxialSpacing`
     *  for the formula behind the rotatable choice.
     *
     *  The three replication knobs override the document-level
     *  `ReplicationSpec` entirely for CCDs — see this class's KDoc.
     */
    @Serializable
    @SerialName("centralComposite")
    data class CentralComposite(
        @TomlComment(
            "Axial-point spacing (α).  { type = \"rotatable\" } computes\n" +
            "α from k + the fractional-factorial fraction at engine-build\n" +
            "time; { type = \"explicit\", value = 1.0 } uses the literal\n" +
            "value (1.0 = face-centred design)."
        )
        val axialSpacing: AxialSpacing = AxialSpacing.Rotatable,

        @TomlComment(
            "Integer >= 1.  Replications at every factorial-portion\n" +
            "design point.  Default 1."
        )
        val numFactorialReps: Int = 1,

        @TomlComment(
            "Integer >= 1.  Replications at every axial point\n" +
            "(2 axials per factor).  Default 1."
        )
        val numAxialReps: Int = 1,

        @TomlComment(
            "Integer >= 1.  Replications at the single centre point.\n" +
            "Typical RSM practice is 4-6 for variance estimation.\n" +
            "Default 1."
        )
        val numCenterReps: Int = 1,

        @TomlComment(
            "Selection for the factorial portion of the CCD.\n" +
            "Default { type = \"full\" } (full 2^k core).  Use a\n" +
            "fractional subtype to build a small-fraction CCD."
        )
        val factorialFraction: Fraction = Fraction.Full
    ) : DesignSpec() {
        init {
            require(numFactorialReps >= 1) { "numFactorialReps must be >= 1; got $numFactorialReps" }
            require(numAxialReps >= 1) { "numAxialReps must be >= 1; got $numAxialReps" }
            require(numCenterReps >= 1) { "numCenterReps must be >= 1; got $numCenterReps" }
        }
    }

    /**
     *  Hand-authored list of design points.  Used to augment a
     *  generated design (e.g. add a specific factor combination to a
     *  CCD) or to specify a design that doesn't fit the other
     *  variants.  Each entry pins one factor-setting map; per-point
     *  replications may override the document-level `ReplicationSpec`.
     */
    @Serializable
    @SerialName("manual")
    data class Manual(
        @TomlComment(
            "List of design points.  Each entry is a [[designSpec.points]]\n" +
            "array-of-tables block giving the factor-value map and an\n" +
            "optional per-point replication count."
        )
        val points: List<ManualPointSpec>
    ) : DesignSpec() {
        init {
            require(points.isNotEmpty()) { "manual design must have at least one point" }
        }
    }
}

/**
 *  Selection of which subset of a full 2^k design to enumerate.
 *  Used by `DesignSpec.TwoLevelFactorial.fraction` and by
 *  `DesignSpec.CentralComposite.factorialFraction` (which can build
 *  a fractional core inside a CCD).
 */
@Serializable
sealed class Fraction {
    /**
     *  Full 2^k — all factor-level combinations.  Maps to
     *  `TwoLevelFactorialDesign.designIterator()`.
     */
    @Serializable
    @SerialName("full")
    data object Full : Fraction()

    /**
     *  Half-fraction (1/2 of the full 2^k).  [sign] = +1 selects the
     *  principal half (I = ...), [sign] = -1 the alternate half.
     *  Maps to
     *  `TwoLevelFactorialDesign.halfFractionIterator(half = sign.toDouble())`.
     */
    @Serializable
    @SerialName("half")
    data class HalfFraction(
        @TomlComment(
            "Sign of the half-fraction generator: 1 (principal half,\n" +
            "the default) or -1 (alternate half)."
        )
        val sign: Int = +1
    ) : Fraction() {
        init { require(sign == 1 || sign == -1) { "sign must be 1 or -1; got $sign" } }
    }

    /**
     *  Custom fractional design — explicit list of defining
     *  relations.  Each relation is a string of uppercase letters
     *  (e.g. `"ABCD"`); the engine glue converts each letter to its
     *  1-based factor index for the substrate's
     *  `fractionalIterator(relation: Set<Set<Int>>)`.  The resulting
     *  fraction realises a 2^(k-p) design where p = `relations.size`.
     */
    @Serializable
    @SerialName("custom")
    data class Custom(
        @TomlComment(
            "List of generator strings (e.g. \"ABCD\", \"ABE\").  Each\n" +
            "entry names the factors whose product equals the identity I.\n" +
            "Letter X refers to the X-th factor by position\n" +
            "(A = factor 1).  Letters within one relation must be unique;\n" +
            "every letter must lie within the document's factor count.\n" +
            "The fraction exponent p equals the list size; the realised\n" +
            "design is 2^(k-p).  Group-theoretic validity (that the\n" +
            "generators realise the expected fraction without collapsing)\n" +
            "is verified at engine-build time."
        )
        val relations: List<String>,

        @TomlComment(
            "Sign of the generator I = +1 or -I = -1.  Default 1."
        )
        val sign: Int = +1
    ) : Fraction() {
        init {
            require(relations.isNotEmpty()) { "custom fraction must have at least one relation" }
            require(sign == 1 || sign == -1) { "sign must be 1 or -1; got $sign" }
        }
    }
}

/**
 *  Axial spacing (α) for a central composite design.
 */
@Serializable
sealed class AxialSpacing {
    /**
     *  Compute α from k + the factorial fraction at engine-build
     *  time via
     *  `ksl.controls.experiments.CentralCompositeDesign.rotatableAxialSpacing`.
     *  Produces a rotatable design.
     */
    @Serializable
    @SerialName("rotatable")
    data object Rotatable : AxialSpacing()

    /**
     *  Use the user-supplied [value] directly.  α = 1.0 gives a
     *  face-centred design (axial points on the faces of the cube).
     */
    @Serializable
    @SerialName("explicit")
    data class Explicit(
        @TomlComment(
            "Double > 0.  Axial spacing in coded units.  α = 1.0\n" +
            "produces a face-centred CCD; (2^k)^(1/4) is the classical\n" +
            "rotatable value."
        )
        val value: Double
    ) : AxialSpacing() {
        init { require(value > 0.0) { "explicit axial spacing must be > 0; got $value" } }
    }
}

/**
 *  One design point in a [DesignSpec.Manual] design.
 *
 *  [factorValues] maps each factor's name to its raw value at this
 *  point.  Every factor in the document must appear; submit-time
 *  validation enforces this (the keys must match the document's
 *  `factors[*].name` set).
 *
 *  [replications] overrides the document-level
 *  [ReplicationSpec] for this single point; `null` (the default)
 *  means the document-level value applies.  Per-point overrides are
 *  typical for CCD-style centre-point augmentation in a Manual
 *  design.
 */
@Serializable
data class ManualPointSpec(
    @TomlComment(
        "Map of factor name -> raw value at this point.  Every factor\n" +
        "declared in [[factors]] must appear; extra or missing keys\n" +
        "are rejected at submit time."
    )
    val factorValues: Map<String, Double>,

    @TomlComment(
        "Integer >= 1, or omitted.  Per-point replication count\n" +
        "override.  Omit to inherit the document's [replications]\n" +
        "value."
    )
    val replications: Int? = null
) {
    init {
        require(factorValues.isNotEmpty()) {
            "ManualPointSpec.factorValues must contain at least one entry"
        }
        require(replications == null || replications >= 1) {
            "replications must be >= 1 when non-null; got $replications"
        }
    }
}
