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

package ksl.server.suite

/**
 * A book/code tool handler's result: the [text] shown to the client, plus optional usage-study metadata
 * a SEARCH tool surfaces from its ranked hits — [resultCount] (how many matched; 0 is the content-gap
 * signal) and [topScore] (the best relevance; low ⇒ a weak match). Non-search tools return only text
 * (via the `String` convenience registration); the recording wrapper reads these when present.
 */
data class ToolReply(
    val text: String,
    val resultCount: Int? = null,
    val topScore: Double? = null,
)
