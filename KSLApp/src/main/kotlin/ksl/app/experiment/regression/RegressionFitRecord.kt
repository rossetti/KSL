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

package ksl.app.experiment.regression

import ksl.utilities.statistic.RegressionResultsIfc
import java.nio.file.Path
import java.time.LocalDateTime

/**
 *  One record in the experiment-app regression-fit history.  Records
 *  are appended on every successful fit and survive until the host's
 *  bound (typically `MAX_RECENT_FITS` on its controller) evicts them,
 *  the user removes them, or an R1 lifecycle event (Simulate /
 *  structural mutation / reset) clears the whole list.
 *
 *  Substrate-level DTO so any experiment-app host (Swing today; web
 *  / CLI tomorrow) reuses the same record shape.  Carries the [fit]
 *  itself (a self-contained numeric object), enough metadata to
 *  re-render its HTML report at any time, and a (possibly-empty)
 *  list of paths previously written to disk by the user's Save
 *  action.  [savedPaths] is the only mutable surface: it grows when
 *  the user re-saves.  Hosts use `savedPaths.isEmpty()` as the
 *  "unsaved" badge predicate.
 *
 *  @param timestamp        wall clock time of the fit (for the
 *                          Recent Fits list's time column)
 *  @param response         response variable name the fit was against
 *  @param modelExpression  the LinearModel as a parsable string
 *                          (output of `LinearModel.asString`)
 *  @param coded            true if factor levels were coded (-1, +1
 *                          style), false if natural units
 *  @param confidenceLevel  CI level used to render the report (passed
 *                          through to `toReport(confidenceLevel=...)`)
 *  @param fit              the substrate regression results object
 *  @param savedPaths       paths under
 *                          `<workspace>/output/<analysisName>/reports/`
 *                          that this fit has been materialised to;
 *                          empty until the user clicks Save
 */
data class RegressionFitRecord(
    val timestamp: LocalDateTime,
    val response: String,
    val modelExpression: String,
    val coded: Boolean,
    val confidenceLevel: Double,
    val fit: RegressionResultsIfc,
    val savedPaths: List<Path> = emptyList()
)
