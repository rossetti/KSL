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
 * Physical arrangement of one or more datasets in a delimited input source.
 *
 * SINGLE — the whole file is a single series of numeric values (no header,
 * whitespace-separated). Produces exactly one dataset.
 *
 * WIDE — column-per-dataset: a header row names each column and each
 * numeric column is one dataset. Produces one dataset per (selected) column.
 *
 * LONG — two designated columns: an id column tagging each row's dataset
 * membership and a value column carrying the numeric observation. Produces
 * one dataset per distinct id, in first-encountered order.
 */
enum class DatasetLayout { SINGLE, WIDE, LONG }
