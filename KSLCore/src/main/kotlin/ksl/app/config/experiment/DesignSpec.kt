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
 *  document's factors.  Four variants — full factorial, two-level
 *  fractional, central composite, and a manual list of points.
 *
 *  Sealed so the codec emits a `type` discriminator + variant-specific
 *  keys.  Phase E2 (engine glue) maps each variant to the matching
 *  `ksl.controls.experiments.*Design` substrate type.
 */
@Serializable
sealed class DesignSpec {
    /**
     *  Full factorial — every combination of factor levels.  The
     *  number of design points is the product of the factors' level
     *  counts.  Heterogeneous level counts are allowed.
     *
     *  Optional center-point augmentation: when [centerPoints] > 0,
     *  the design adds that many replicates of the midpoint of every
     *  factor's range.  Center points help fit second-order terms or
     *  estimate pure error.  Default 0 = no center points.
     */
    @Serializable
    @SerialName("fullFactorial")
    data class FullFactorial(
        @TomlComment(
            "Integer >= 0.  Number of replicate center-point runs to\n" +
            "add to the full-factorial design.  Each center point uses\n" +
            "the midpoint of every factor's range.  Default 0 = no\n" +
            "center points."
        )
        val centerPoints: Int = 0
    ) : DesignSpec() {
        init {
            require(centerPoints >= 0) { "centerPoints must be >= 0" }
        }
    }

    /**
     *  Two-level fractional factorial.  Realises a 2^(k-p) fraction of
     *  the full 2^k design by adding p generator relations.  Used when
     *  the full 2^k is too expensive and the analyst is willing to
     *  alias some interactions.
     *
     *  Defining relations are letter strings like 'ABCD' (= A · B · C · D
     *  in the algebra of factor effects).  Letters refer to factors by
     *  position: A = first factor in the document, B = second, etc.
     *  See [DefiningRelationValidator] for the validation rules.
     */
    @Serializable
    @SerialName("twoLevelFractional")
    data class TwoLevelFractional(
        @TomlComment(
            "Integer >= 2.  Number of factors k in the base 2^k design.\n" +
            "Must equal the document's factor count; otherwise the\n" +
            "document is rejected at decode time."
        )
        val numFactors: Int,

        @TomlComment(
            "Integer in 1..k-1.  Fraction exponent p in 2^(k-p).  A\n" +
            "5-factor design with p = 2 produces a 2^(5-2) = 8-point\n" +
            "design (one-quarter fraction).  Must match the size of\n" +
            "[definingRelations]."
        )
        val fractionExponent: Int,

        @TomlComment(
            "List of generator strings.  Each entry is a sequence of\n" +
            "uppercase letters (e.g. 'ABCD') naming the factors that\n" +
            "multiply to the identity I.  Letter X refers to the X-th\n" +
            "factor by position (A = factor 1).  Letters within one\n" +
            "relation must be unique; the list size must equal\n" +
            "[fractionExponent].  See DefiningRelationValidator for\n" +
            "syntax rules; group-theoretic validity (the realised\n" +
            "fraction matches the requested size) is verified at\n" +
            "submit time."
        )
        val definingRelations: List<String>
    ) : DesignSpec() {
        init {
            require(numFactors >= 2) { "numFactors must be >= 2" }
            require(fractionExponent in 1..(numFactors - 1)) {
                "fractionExponent must be in 1..(numFactors-1); got $fractionExponent for k=$numFactors"
            }
            require(definingRelations.size == fractionExponent) {
                "definingRelations.size (${definingRelations.size}) must equal " +
                    "fractionExponent ($fractionExponent)"
            }
        }
    }

    /**
     *  Central composite design — factorial core + axial points +
     *  centre points.  Used to fit second-order response surface
     *  models (RSM).
     *
     *  Axial points sit at ± [axialSpacing] (in coded units) along
     *  each factor axis.  The classical rotatable choice is
     *  α = (2^k)^(1/4); the default 1.682 corresponds to k = 3.
     *  Centre points are typically 4–6 replicates to estimate pure
     *  error.
     */
    @Serializable
    @SerialName("centralComposite")
    data class CentralComposite(
        @TomlComment(
            "Double > 0.  Axial-point spacing in coded units (α).\n" +
            "The classical rotatable value is (2^k)^(1/4) — for k = 3,\n" +
            "that is 1.682 (the default).  Use 1.0 for face-centred\n" +
            "designs (axials on the faces of the cube)."
        )
        val axialSpacing: Double = 1.682,

        @TomlComment(
            "Integer >= 0.  Number of replicate centre-point runs.\n" +
            "Typical values are 4–6 for variance estimation.  Default 5."
        )
        val centerPoints: Int = 5
    ) : DesignSpec() {
        init {
            require(axialSpacing > 0.0) { "axialSpacing must be > 0" }
            require(centerPoints >= 0) { "centerPoints must be >= 0" }
        }
    }

    /**
     *  Hand-authored list of design points.  Used to augment a
     *  generated design (e.g. add a specific factor combination to a
     *  CCD) or to specify a design that doesn't fit the other
     *  variants.  Each entry pins one factor-setting map; per-point
     *  replications may override the document-level uniform value.
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
 *  One design point in a [DesignSpec.Manual] design.
 *
 *  [factorValues] maps each factor's name to its raw value at this
 *  point.  Every factor in the document must appear; submit-time
 *  validation enforces this (the keys must match the document's
 *  `factors[*].name` set).
 *
 *  [replications] overrides the document-level [ReplicationSpec] for
 *  this single point; `null` (the default) means the document-level
 *  value applies.  Per-point overrides are typical for CCD centre
 *  points that want more replicates than the corners.
 */
@Serializable
data class ManualPointSpec(
    @TomlComment(
        "Map of factor name → raw value at this point.  Every factor\n" +
        "declared in [[factors]] must appear; extra or missing keys\n" +
        "are rejected at submit time."
    )
    val factorValues: Map<String, Double>,

    @TomlComment(
        "Integer >= 1, or omitted.  Per-point replication count\n" +
        "override.  Omit to inherit the document's [replications]\n" +
        "value.  Typical use: extra replicates at CCD centre points."
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
