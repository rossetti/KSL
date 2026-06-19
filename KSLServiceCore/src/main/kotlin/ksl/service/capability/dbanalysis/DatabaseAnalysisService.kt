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

package ksl.service.capability.dbanalysis

import kotlinx.serialization.Serializable
import ksl.app.comparison.ComparisonDataSourceIfc
import ksl.app.comparison.ExperimentRow
import ksl.app.comparison.KSLDatabaseComparisonSource
import ksl.app.comparison.ResponseRow
import ksl.utilities.io.dbutil.DerbyDb
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.SQLiteDb
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Wire projection of [ExperimentRow]; the domain type is not @Serializable. */
@Serializable
data class ExperimentInfoDto(
    val name: String,
    val modelIdentifier: String,
    val numReplications: Int,
    val responses: List<ResponseInfoDto>,
)

/** Wire projection of [ResponseRow]. */
@Serializable
data class ResponseInfoDto(
    val name: String,
    val category: String,
)

/** An opened, read-only database session managed by [DatabaseAnalysisService]. */
class DbHandle internal constructor(
    val id: String,
    internal val database: KSLDatabase,
    internal val source: KSLDatabaseComparisonSource,
    val label: String,
)

/**
 * The service-core surface for capability C — analyzing a saved results
 * database (strategic plan §5.7).
 *
 * Unlike the run and fit capabilities, this one is **not** a submit-and-stream
 * job: it is *open-then-query*, so it sits beside the generic job spine rather
 * than on it. It opens a `KSLDatabase` (SQLite file or embedded Derby
 * directory) **read-only**, exposes its experiments / responses / per-replication
 * observations through the existing [ComparisonDataSourceIfc] seam, and never
 * mutates the database.
 *
 * Connection lifecycle is intentionally minimal in this first cut: handles are
 * tracked and dropped on [close]; richer pooling/eviction can follow if a
 * long-running server needs it.
 */
class DatabaseAnalysisService : AutoCloseable {

    private val handles = ConcurrentHashMap<String, DbHandle>()

    /**
     * Opens an embedded database at [path] read-only: a SQLite `.db` file, or an
     * embedded Derby directory (detected by a `service.properties` entry).
     */
    fun open(path: Path): DbHandle {
        require(Files.exists(path)) { "No database found at $path" }
        val derby = Files.isDirectory(path) && Files.exists(path.resolve("service.properties"))
        val database =
            if (derby) KSLDatabase(DerbyDb.openDatabase(path)) else KSLDatabase(SQLiteDb.openDatabase(path))
        return attach(database, path.fileName.toString())
    }

    /**
     * Wraps an already-open [KSLDatabase] (e.g. one a caller produced in-process)
     * as a tracked handle. The eager [KSLDatabaseComparisonSource] snapshot reads
     * the experiment/observation metadata up front.
     */
    fun attach(database: KSLDatabase, label: String = "database"): DbHandle {
        val handle = DbHandle(
            id = UUID.randomUUID().toString(),
            database = database,
            source = KSLDatabaseComparisonSource(database),
            label = label,
        )
        handles[handle.id] = handle
        return handle
    }

    /** Every experiment recorded in the database. */
    fun listExperiments(handle: DbHandle): List<ExperimentInfoDto> =
        handle.source.availableExperiments().map { it.toDto() }

    /** The responses recorded by one experiment, or empty if unknown. */
    fun listResponses(handle: DbHandle, experimentName: String): List<ResponseInfoDto> =
        handle.source.availableExperiments()
            .firstOrNull { it.name == experimentName }
            ?.responses?.map { it.toDto() }
            ?: emptyList()

    /** Per-replication observations for one response, or null if absent. */
    fun observations(handle: DbHandle, experimentName: String, responseName: String): DoubleArray? =
        handle.source.observations(experimentName, responseName)

    /** The underlying comparison source, for callers that drive MCB / CI analyses directly. */
    fun comparisonSource(handle: DbHandle): ComparisonDataSourceIfc = handle.source

    /** Releases a handle. Best-effort closes the underlying connection if closeable. */
    fun close(handle: DbHandle) {
        handles.remove(handle.id)
        (handle.database as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    override fun close() {
        handles.values.toList().forEach { close(it) }
    }

    private fun ExperimentRow.toDto() =
        ExperimentInfoDto(name, modelIdentifier, numReplications, responses.map { it.toDto() })

    private fun ResponseRow.toDto() = ResponseInfoDto(name, category.name)
}
