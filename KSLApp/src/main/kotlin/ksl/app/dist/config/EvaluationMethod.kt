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

package ksl.app.dist.config

/**
 * Serializable mirror of the engine's recommendation criterion
 * (`ksl.utilities.distributions.fitting.EvaluationMethod`), surfaced so a
 * caller can choose how the recommended distribution and the fit ordering
 * are determined. The default (`SCORING`) matches the engine default.
 *
 * SCORING — order fits by the MODA overall weighted value (higher is better).
 * RANKING — order fits by the average of their per-metric ranks (lower is better).
 */
enum class EvaluationMethod { SCORING, RANKING }
