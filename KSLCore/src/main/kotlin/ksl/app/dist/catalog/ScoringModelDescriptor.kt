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

package ksl.app.dist.catalog

import ksl.utilities.distributions.fitting.scoring.PDFScoringModel

/**
 * Self-describing record for one PDF scoring model registered with the
 * fitting catalog. Scoring models are always created fresh per fit, since
 * PDFModeler already copies them internally during scoring and a fresh
 * instance at the catalog boundary avoids any chance of cross-fit state.
 */
data class ScoringModelDescriptor(
    val id: String,
    val displayName: String,
    val factory: () -> PDFScoringModel
)
