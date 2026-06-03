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
 * Field separator for a delimited text input source.
 *
 * COMMA — standard CSV; pairs with WIDE or LONG layouts.
 * WHITESPACE — any run of whitespace separates values; pairs with SINGLE
 * (scanner-style numeric stream, no header).
 *
 * TAB is intentionally absent in this phase; it will be added once the
 * importer is routed through a configurable CSV format or TabularInputFile.
 */
enum class Delimiter { COMMA, WHITESPACE }
