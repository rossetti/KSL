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

package ksl.app.single.results

import java.nio.file.Path
import java.time.LocalDateTime

/**
 *  One record in the single-app post-run reporting history.  Records
 *  are appended on every successful materialise (auto-render after a
 *  run, or user-initiated from the post-run reporting UI) and survive
 *  until the next Simulate click clears the list, the user removes
 *  them, or the host's bound (typically `MAX_RECENT_REPORT_SAVES` on
 *  its controller) evicts the oldest.
 *
 *  Substrate-level DTO so any single-app host (Swing today; web /
 *  CLI / headless tomorrow) reuses the same record shape.  Files on
 *  disk are NOT removed when a record is dropped — that's the user's
 *  domain.  The list is a session-level navigation aid, not the
 *  source of truth for what's been published.
 *
 *  @param timestamp wall-clock time of the save
 *  @param fileName  the file's name (no directory component)
 *  @param path      absolute path to the file on disk
 *  @param origin    [Origin.AUTO] for auto-rendered files (post-run
 *                   completion), [Origin.MANUAL] for user-initiated
 *                   saves
 */
data class ReportSaveRecord(
    val timestamp: LocalDateTime,
    val fileName: String,
    val path: Path,
    val origin: Origin
) {
    enum class Origin { AUTO, MANUAL }
}
