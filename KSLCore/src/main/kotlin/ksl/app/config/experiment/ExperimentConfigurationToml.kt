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

import net.peanuuutz.tomlkt.Toml

/**
 *  TOML codec for [ExperimentConfiguration].
 *
 *  Mirrors [ksl.app.config.RunConfigurationToml]: `explicitNulls =
 *  false` so absent optional fields stay out of the encoded text;
 *  decoder uses the property defaults declared on each type, which
 *  means legacy files missing the newer fields still load cleanly.
 *
 *  Every `encode(...)` prepends [DOCUMENT_HEADER] — a `#`-prefixed
 *  banner that explains the document's purpose, layout (in encoded
 *  property order), and editing guidelines.  The decoder ignores
 *  comments; users can hand-edit field values and re-save through
 *  any host, and the comment block is regenerated.
 */
object ExperimentConfigurationToml {

    private val myToml = Toml {
        explicitNulls = false
    }

    /** Serialises [config] to a TOML string, prefixed with [DOCUMENT_HEADER]. */
    fun encode(config: ExperimentConfiguration): String =
        DOCUMENT_HEADER + myToml.encodeToString(ExperimentConfiguration.serializer(), config)

    /** Deserialises an [ExperimentConfiguration] from a TOML string
     *  produced by [encode]. */
    fun decode(text: String): ExperimentConfiguration =
        myToml.decodeFromString(ExperimentConfiguration.serializer(), text)

    /**
     *  Banner prepended to every encoded TOML file.  Mirrors the
     *  shape of `RunConfigurationToml.DOCUMENT_HEADER` but adapted
     *  for the factor-and-design vocabulary of designed experiments.
     */
    private val DOCUMENT_HEADER: String = """
        # ────────────────────────────────────────────────────────────────────────────
        #  KSL Experiment Configuration
        # ────────────────────────────────────────────────────────────────────────────
        #
        #  This file describes one designed experiment for the KSL Experiment app.
        #  The document binds ONE model to a set of FACTORS (variables with named
        #  levels) and a DESIGN that enumerates concrete design points from those
        #  factors.  At submit time the engine materialises the design and runs each
        #  point under the chosen random-stream policy.
        #
        #  This is a different shape from the Scenario / Single TOML format — see
        #  RunConfigurationToml.  Optimization runs have their own format under
        #  OptimizationRunConfigurationToml.
        #
        #  Document layout (top → bottom):
        #
        #    [outputConfig]      Document-wide output settings.  Analysis name,
        #                        database toggle and policy, CSV flags, report
        #                        formats.
        #    [modelReference]    The single model this experiment runs.
        #    [[factors]]         The factors whose levels the design explores.
        #                        One [[factors]] entry per factor; each carries
        #                        its name, level values, and binding to a model
        #                        control or RV parameter.
        #    [designSpec]        How design points are enumerated from the
        #                        factors.  Choose one of: 'fullFactorial',
        #                        'twoLevelFractional', 'centralComposite', or
        #                        'manual'.
        #    [replications]      Per-design-point replication strategy.  Either
        #                        'uniform' (every point gets the same count) or
        #                        'perPoint' (default + index-keyed override map).
        #    executionMode       Top-level string: 'CONCURRENT' (default — design
        #                        points run in parallel on the simulation
        #                        dispatcher) or 'SEQUENTIAL'.
        #    [streamPolicy]      Random-stream policy across design points.
        #                        Honoured under CONCURRENT only.
        #                        Default is 'independent' (each point starts from
        #                        a fresh stream block); 'commonRandomNumbers' is
        #                        explicit opt-in.
        #    [[bundleRefs]]      Optional list of model-bundle JARs the
        #                        modelReference depends on.
        #    [tracingConfig]     Animation / trace capture settings.  Defaults to
        #                        OFF; safe to omit unless you are running a
        #                        traced model.
        #
        #  Editing guidelines:
        #
        #   * String values use "double quotes".  Numbers and booleans are bare
        #     literals (replications = 10, axialSpacing = 1.682).
        #   * Sections marked "optional" can be omitted entirely.  Within a
        #     section, omitted fields take the default printed in the comment
        #     adjacent to that field.
        #   * Saving from the Experiment app overwrites this file.  Your
        #     hand-edited comments WILL NOT be preserved; field VALUES are
        #     preserved verbatim.
        #   * Factor names must be unique within the document.
        #   * Sealed-type discriminators use the 'type' key.  Allowed values are
        #     listed in the adjacent field comment.
        #
        #  Reference: https://rossetti.github.io/KSLBook/
        #
        # ────────────────────────────────────────────────────────────────────────────

        """.trimIndent()
}
