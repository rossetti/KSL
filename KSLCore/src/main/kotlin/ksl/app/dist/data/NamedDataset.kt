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

package ksl.app.dist.data

/**
 * One numeric series tagged with a human-readable label. The label typically
 * carries source provenance (originating column header, id value, file stem,
 * or inline key) so downstream reports and event traffic can identify the
 * dataset without an external lookup.
 *
 * Uses data-class identity (reference equality on `data`). Do not compare
 * `NamedDataset` instances for content equality; compare `data` arrays
 * directly when needed.
 */
data class NamedDataset(val name: String, val data: DoubleArray) {
    val size: Int get() = data.size
}
