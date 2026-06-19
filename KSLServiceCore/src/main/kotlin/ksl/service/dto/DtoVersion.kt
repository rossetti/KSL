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

package ksl.service.dto

import kotlinx.serialization.Serializable

/**
 * The version of the wire contract carried by every [RunResultDto].
 *
 * The DTO layer is a public, independently-versioned surface (Phase 7
 * strategic plan §4.5): fields may be added freely, but never renamed or
 * removed without a major [wire] bump. Consumers refuse to deserialize a
 * payload whose major version they do not understand.
 */
@Serializable
data class DtoVersion(val wire: String = "1.0")
