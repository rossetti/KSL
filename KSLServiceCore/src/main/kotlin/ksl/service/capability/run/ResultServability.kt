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

package ksl.service.capability.run

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.store.StoredResult

/**
 * True unless this stored result holds a non-servable run outcome — a `Failed`/`Cancelled`
 * [RunResultDto] (identified by its `type` discriminator). Such a result is deliberately **retained**
 * in the store for diagnostics (`get_result` / `list_results`), but a cache lookup must treat it as a
 * miss and re-run rather than serve the stale failure. A later success overwrites it under the same
 * key (self-healing). Non-run payloads (e.g. a fit report) carry no run `type` discriminator and are
 * servable (and fit failures throw before persisting, so none are stored).
 */
fun StoredResult.holdsServableResult(): Boolean {
    val type = ((payload as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
    return type !in RunResultDto.NON_SERVABLE_TYPES
}
