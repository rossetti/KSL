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

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 * Document-wide output settings for a simulation-optimization run.
 *
 * Optimization counterpart to `ksl.app.config.OutputConfig`.  Carries
 * only the fields the optimization runtime actually consumes —
 * [analysisName] (the per-run subdirectory identity) and
 * [outputDirectory] (the host-resolved absolute path used at submit
 * time).
 *
 * The Single / Scenario apps' `OutputConfig` additionally persists
 * database, CSV, and report toggles; none of those apply to
 * optimization runs in v1, so they are intentionally absent from
 * this spec.  When the optimization orchestrator gains those sinks,
 * fields grow here rather than overloading the shared `OutputConfig`.
 *
 * Lives at document scope on
 * [OptimizationRunConfiguration.output] so every run produced by a
 * single document shares one output-directory layout under the
 * active workspace.
 *
 * @property analysisName user-facing label for this analysis; names
 *           the subdirectory under `<workspace>/output/<analysisName>/`
 *           where every artifact lands.  Sanitised at write time via
 *           the shared `ksl.app.config.sanitizeAnalysisName` helper;
 *           the stored value is the user-typed form so the UI shows
 *           what they typed.  Defaults to `"Untitled"`.
 * @property outputDirectory absolute filesystem path the runtime
 *           uses for the model's `outputDirectory`.  Set at submit
 *           time by the hosting app from the workspace plus
 *           [analysisName]; **do not edit by hand**.  `null` means
 *           the framework default (`kslOutput` under the JVM working
 *           directory).
 */
@Serializable
data class OptimizationOutputConfig(
    @TomlComment(
        "String. Identity for this analysis.  Names the subdirectory\n" +
        "<workspace>/output/<analysisName>/ where artifacts land.\n" +
        "Sanitised at write time (letters/digits/_/-, max 64 chars;\n" +
        "anything else replaced with _).  Default: 'Untitled'."
    )
    val analysisName: String = "Untitled",

    @TomlComment(
        "Absolute filesystem path the runtime uses for the model's\n" +
        "outputDirectory.  Set at submit time by the hosting app from\n" +
        "the workspace plus analysisName; DO NOT edit by hand.  null =\n" +
        "framework default ('kslOutput' under the JVM working dir)."
    )
    val outputDirectory: String? = null
)
