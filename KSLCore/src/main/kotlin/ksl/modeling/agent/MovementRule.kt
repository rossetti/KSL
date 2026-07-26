/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import kotlinx.serialization.Serializable

/**
 *  Movement rule for a [GridGraph]. Selects the allowed neighbors of
 *  each cell:
 *   - [MOORE] — the 8 surrounding cells (orthogonal + diagonal).
 *     The standard for most grid pathfinding.
 *   - [VON_NEUMANN] — the 4 orthogonal cells only (no diagonals).
 *     Used when diagonals are not allowed (tile-aligned movement,
 *     some board games).
 */
@Serializable
enum class MovementRule { MOORE, VON_NEUMANN }
