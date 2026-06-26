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

package ksl.service.store

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ResultStoreTest {

    private fun result(id: String) = StoredResult(
        resultId = id,
        kind = ResultKind.RUN,
        createdAt = Clock.System.now(),
        request = buildJsonObject { put("model", "MM1") },
        payload = buildJsonObject { put("type", "completed") },
    )

    @Test
    fun `a stored result survives a new store reading the same directory`() {
        val dir = Files.createTempDirectory("rs-persist")
        ResultStore(dir).put(result("abc123"))

        // A fresh store (cold memory tier) finds it on disk.
        val reopened = ResultStore(dir).get("abc123")
        assertNotNull(reopened, "expected the result to persist to disk")
        assertEquals("MM1", (reopened.request as kotlinx.serialization.json.JsonObject)["model"].let { (it as JsonPrimitive).content })
    }

    @Test
    fun `cachedRun produces once then serves the cache for the same key`() = runBlocking {
        val store = ResultStore(Files.createTempDirectory("rs-cache"))
        var productions = 0
        val produce: suspend () -> kotlinx.serialization.json.JsonElement = {
            productions++
            buildJsonObject { put("type", "completed") }
        }

        val first = store.cachedRun("key-1", ResultKind.RUN, buildJsonObject { }, useCache = true, produce)
        val second = store.cachedRun("key-1", ResultKind.RUN, buildJsonObject { }, useCache = true, produce)

        assertEquals(false, first.fromCache, "first call is a miss")
        assertEquals(true, second.fromCache, "second call (same key) is a hit")
        assertEquals(1, productions, "the work ran only once")
    }

    @Test
    fun `useCache false re-runs even on a present key`() = runBlocking {
        val store = ResultStore(Files.createTempDirectory("rs-fresh"))
        var productions = 0
        val produce: suspend () -> kotlinx.serialization.json.JsonElement = {
            productions++; buildJsonObject { put("n", productions) }
        }
        store.cachedRun("k", ResultKind.RUN, buildJsonObject { }, useCache = true, produce)
        store.cachedRun("k", ResultKind.RUN, buildJsonObject { }, useCache = false, produce)
        assertEquals(2, productions, "useCache=false should re-run")
    }
}
