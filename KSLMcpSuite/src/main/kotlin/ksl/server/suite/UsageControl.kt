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

import ksl.service.usage.UsageEvent
import ksl.service.usage.UsageLevel

/**
 * Everything the console needs to display and control the local usage study, bundled so
 * `KslSuiteMcpServer.create` keeps a small signature. The composition root ([ksl.server.suite]'s `main`)
 * builds it over the shared `UsageStore` + config:
 * - [dir] — where the append-only log lives (for the console's disclosure + the "Show file" reveal);
 * - [level] — the current detail level (for the console + the Usage-study control's active state);
 * - [setLevel] — apply a new level LIVE and persist it to config (the opt-out);
 * - [exportAll] — the durable, all-time events for the CSV / JSONL export (not the current-run view);
 * - [label] — an optional student label stamped into the export filename, for attribution on hand-off.
 */
class UsageControl(
    val dir: String,
    val level: () -> UsageLevel,
    val setLevel: (UsageLevel) -> Unit,
    val exportAll: () -> List<UsageEvent>,
    val label: String? = null,
)
