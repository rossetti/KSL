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
 *  One factor in a designed experiment.
 *
 *  A factor is a variable whose levels are explored by the design.
 *  Each level is a raw value applied at run time to a model control
 *  or a random-variable parameter (see [binding]).  Two-level factors
 *  carry exactly two levels; higher-level (3+) factors are allowed
 *  for full-factorial designs, and level counts may differ across
 *  factors in the same document (heterogeneous-level full factorials
 *  are supported by the underlying `FactorialDesign`).
 *
 *  Validation (all enforced at `init` time so every code path — GUI,
 *  TOML decode, programmatic construction — fails fast on bad data):
 *  - [name] must be non-blank.
 *  - [levels] must contain at least 2 values.
 *  - [levels] must be **strictly increasing** (no duplicates, ordered
 *    smallest to largest).  Mirrors the substrate `Factor` constructor's
 *    precondition so the user finds out at factor-entry time instead
 *    of at preview / submit time.
 *
 *  Cross-factor invariants (name uniqueness, count constraints per
 *  design family) are enforced in [ExperimentConfiguration.init].
 */
@Serializable
data class FactorSpec(
    @TomlComment(
        "String (required, non-blank).  User-given factor name.  Must\n" +
        "be unique within the document.  Used in the design-points\n" +
        "preview, in regression-model term names ('A*B', 'name^2'),\n" +
        "and in defining-relations for fractional designs."
    )
    val name: String,

    @TomlComment(
        "List of doubles.  Raw values of this factor's levels in\n" +
        "strictly increasing order.  Must contain at least 2 values.\n" +
        "Two-level designs require exactly 2 levels per factor; full\n" +
        "factorials and central-composite designs accept any count\n" +
        "(heterogeneous counts across factors are allowed)."
    )
    val levels: List<Double>,

    @TomlComment(
        "How the factor's level value is applied to the model at run\n" +
        "time.  Rendered as a [factors.binding] sub-table with a 'type'\n" +
        "discriminator:\n" +
        "  type = 'control'      controlKey                (model control)\n" +
        "  type = 'rvParameter'  rvName, paramName         (RV parameter)"
    )
    val binding: ControlBinding
) {
    init {
        require(name.isNotBlank()) { "factor name must be non-blank" }
        require(levels.size >= 2) {
            "factor '$name' must have at least 2 levels; got ${levels.size}"
        }
        // Strictly increasing: each level must be strictly less than
        // the next.  Catches both duplicates and out-of-order entries
        // in one check, with a message that points at the first
        // offending adjacent pair.
        for (i in 1 until levels.size) {
            require(levels[i - 1] < levels[i]) {
                "factor '$name' levels must be strictly increasing; got " +
                    "$levels (level ${levels[i - 1]} is not < ${levels[i]})"
            }
        }
    }
}

/**
 *  How a [FactorSpec]'s level value is applied to the model.  Two
 *  variants: bind to a named model control, or bind to a single
 *  parameter of a named random variable.
 *
 *  Sealed so the codec emits a `type` discriminator + variant-specific
 *  keys.  The GUI editor picks the variant via radio + dropdown.
 */
@Serializable
sealed class ControlBinding {
    /**
     *  Bind to a named model control.  At run time the factor's
     *  current level value is written to the control via the model's
     *  control table (see `ksl.controls.Controls`).
     */
    @Serializable
    @SerialName("control")
    data class Control(
        @TomlComment(
            "String (required, non-blank).  Control identifier as\n" +
            "exposed by the model's Controls table (e.g.\n" +
            "'MM1:ServiceRate').  Resolved at submit time; an\n" +
            "unresolvable key surfaces a validation error."
        )
        val controlKey: String
    ) : ControlBinding() {
        init {
            require(controlKey.isNotBlank()) { "controlKey must be non-blank" }
        }
    }

    /**
     *  Bind to a single parameter of a named random variable.  At run
     *  time the factor's current level value is written to the named
     *  parameter on the RV (e.g. the 'mean' of an Exponential RV).
     */
    @Serializable
    @SerialName("rvParameter")
    data class RVParameter(
        @TomlComment(
            "String (required, non-blank).  RandomVariable name as it\n" +
            "appears in the model's RV parameter setter (e.g.\n" +
            "'MM1:ServiceTime')."
        )
        val rvName: String,

        @TomlComment(
            "String (required, non-blank).  Parameter key within the\n" +
            "named RV (e.g. 'mean', 'rate', 'scale').  The set of\n" +
            "valid keys is RV-type-specific; resolved at submit time."
        )
        val paramName: String
    ) : ControlBinding() {
        init {
            require(rvName.isNotBlank()) { "rvName must be non-blank" }
            require(paramName.isNotBlank()) { "paramName must be non-blank" }
        }
    }
}
