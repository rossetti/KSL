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

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlComment

/**
 *  Experiment-app-specific output preferences.  Distinct from the
 *  shared `OutputConfig` (which carries analysisName, database
 *  policy, report formats — all common across the Single / Scenario
 *  / Experiment apps).  Lives at the top level of
 *  [ExperimentConfiguration] so the field doesn't pollute the
 *  shared OutputConfig with experiment-only concerns.
 *
 *  Currently carries one knob, with room for more per-design-point
 *  options when Phase E11 polish lands (per-point CSV reports,
 *  per-point configuration overrides, …).
 */
@Serializable
data class ExperimentOutputSpec(
    @TomlComment(
        "When true, the engine creates a subdirectory per design\n" +
        "point under the analysis output dir; each subdir holds that\n" +
        "point's kslOutput.txt plus any per-point CSV / plot artifacts.\n" +
        "When false (the default), all per-design-point models share\n" +
        "the analysis output dir and the diagnostic log is named\n" +
        "kslOutput_DP_<n>.txt.\n" +
        "\n" +
        "Off by default because the typical experiment has many design\n" +
        "points (a 2^10 design = 1024 points), and one folder per\n" +
        "point becomes unwieldy.  Turn it on when you need per-point\n" +
        "CSV reports or per-point configuration overrides — those\n" +
        "features depend on per-point dirs."
    )
    val usePerPointSubdirs: Boolean = false
)
