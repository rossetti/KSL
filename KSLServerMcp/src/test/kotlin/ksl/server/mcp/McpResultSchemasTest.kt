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

package ksl.server.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards that the execution-result output schemas actually declare the fields the
 * DTOs put on the wire (Theme A / A2). These previously under-declared their payload
 * (bare `object`/`array` for best/items/iterations; responses missing the sufficient
 * statistics; the fit candidates missing the MODA ranking fields), so a schema-driven
 * consumer could not see present data. The assertions below pin the declared shape to
 * the DTO shape so the two cannot silently drift apart again.
 */
class McpResultSchemasTest {

    private fun ToolSchema.top(): JsonObject = properties!!
    private fun JsonObject.child(key: String): JsonObject = this[key]!!.jsonObject
    private fun JsonObject.propsOf(): JsonObject = child("properties")
    private fun JsonObject.itemProps(): JsonObject = child("items").propsOf()

    @Test
    fun `run responses declare the sufficient statistics`() {
        val responseFields = McpResultSchemas.run.top().child("responses").itemProps().keys
        // The A2 additions plus the pre-existing summary fields.
        assertTrue(
            responseFields.containsAll(listOf("name", "average", "stdErr", "halfWidth", "count", "sum", "deviationSumOfSquares")),
            "run responses items under-declare the response statistics: $responseFields",
        )
    }

    @Test
    fun `run batch items declare the BatchItemDto shape`() {
        val itemProps = McpResultSchemas.run.top().child("items").itemProps()
        assertTrue("itemName" in itemProps.keys, "batch item missing itemName: ${itemProps.keys}")
        val itemResponseFields = itemProps.child("responses").itemProps().keys
        assertTrue(
            itemResponseFields.containsAll(listOf("name", "average", "sum", "deviationSumOfSquares")),
            "batch item responses under-declared: $itemResponseFields",
        )
    }

    @Test
    fun `run best declares the SolutionDto shape and iterations the IterationDto shape`() {
        val bestFields = McpResultSchemas.run.top().child("best").propsOf().keys
        assertTrue(
            bestFields.containsAll(listOf("inputs", "estimatedObjFncValue", "penalizedObjFncValue", "isValid")),
            "best under-declared the SolutionDto shape: $bestFields",
        )
        val iterationFields = McpResultSchemas.run.top().child("iterations").itemProps().keys
        assertTrue(
            iterationFields.containsAll(listOf("iterationNumber", "numOracleCalls", "estimatedObjFncValue", "penalizedObjFncValue")),
            "iterations under-declared the IterationDto shape: $iterationFields",
        )
    }

    @Test
    fun `run declares artifacts`() {
        assertTrue("artifacts" in McpResultSchemas.run.top().keys, "run schema is missing artifacts")
    }

    @Test
    fun `get_response declares the sufficient statistics`() {
        val fields = McpResultSchemas.response.top().keys
        assertTrue(
            fields.containsAll(listOf("name", "confLevel", "sum", "deviationSumOfSquares")),
            "get_response under-declares the response statistics: $fields",
        )
    }

    @Test
    fun `fit declares the MODA ranking fields plus scoring and dataSummary`() {
        val candidateFields = McpResultSchemas.fit.top().child("fits").itemProps().keys
        assertTrue(
            candidateFields.containsAll(listOf("weightedValue", "averageRanking")),
            "fit candidates missing the MODA ranking fields: $candidateFields",
        )
        val topLevel = McpResultSchemas.fit.top().keys
        assertTrue(topLevel.containsAll(listOf("scoring", "dataSummary")), "fit schema missing scoring/dataSummary: $topLevel")
    }
}
