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
 * Serializable mirror of the engine's rank-assignment method
 * (`ksl.utilities.statistic.Statistic.Companion.Ranking`), surfaced so a
 * caller can choose how ties are handled when MODA ranks the fitted
 * distributions. The default (`ORDINAL`) matches the engine default.
 *
 * DENSE      — tied values share a rank; the next rank is not skipped.
 * FRACTIONAL — tied values receive the average of the ranks they span.
 * ORDINAL    — every value gets a distinct rank (ties broken arbitrarily).
 */
enum class RankingMethod { DENSE, FRACTIONAL, ORDINAL }
