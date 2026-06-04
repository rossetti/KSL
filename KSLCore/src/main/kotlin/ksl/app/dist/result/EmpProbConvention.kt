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

package ksl.app.dist.result

/**
 * Serializable mirror of the engine's empirical-probability plotting-position
 * convention (`ksl.utilities.statistic.EmpDistType`). Carried on the result so
 * a client can reproduce the engine's Q-Q / P-P plots exactly rather than
 * choosing its own plotting-position formula.
 *
 * BASE         — i/n
 * CONTINUITY1  — (i - 0.5) / n            (the engine default)
 * CONTINUITY2  — (i - 0.375) / (n + 0.25)
 */
enum class EmpProbConvention { BASE, CONTINUITY1, CONTINUITY2 }
