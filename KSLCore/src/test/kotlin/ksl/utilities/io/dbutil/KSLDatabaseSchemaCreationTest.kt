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

package ksl.utilities.io.dbutil

import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers KSL embedded-database schema creation after the move from a copied,
 * launch-directory script *file* to executing the schema directly from its
 * classpath *resource* (see `KSLDatabase.executeSchemaResource`).
 *
 * The central guarantee is that creation no longer depends on the working
 * directory being writable or on the static script-copy bootstrap succeeding —
 * the failure mode that broke the packaged headless server ("The script file
 * does not exist"). These tests build real SQLite and Derby databases and assert
 * the full KSL schema is present, that string- and file-based SQL parsing are
 * equivalent (so the switch is behavior-preserving), and that creation succeeds
 * even when the legacy script file is absent.
 *
 * No plots are rendered here, so these run headless without a display.
 */
class KSLDatabaseSchemaCreationTest {

    @Test
    @DisplayName("SQLite creation builds the full KSL schema (tables + views) from the classpath resource")
    fun sqliteCreationProducesFullSchema() {
        val tempDir = Files.createTempDirectory("ksl_sqlite_schema_test")
        // Construction itself throws KSLDatabaseNotConfigured if the tables are
        // missing, so reaching a non-null instance already proves the schema ran.
        val kslDb = KSLDatabase.createKSLDatabase("sqlite_schema_test", tempDir)
        assertTrue(kslDb.configured, "a freshly created SQLite KSLDatabase must report the full table schema")
        assertTrue(kslDb.experimentNames.isEmpty(), "a fresh database must have no experiments")
        // Querying a view succeeds (returns empty) only if the views were created
        // by the schema script — proving views, not just tables, were applied.
        assertEquals(
            0, kslDb.acrossReplicationViewStatistics.rowsCount(),
            "the across-replication view must exist and be empty on a fresh database",
        )
    }

    @Test
    @DisplayName("Embedded Derby creation builds the full KSL schema from the classpath resource")
    fun derbyCreationProducesFullSchema() {
        val tempDir = Files.createTempDirectory("ksl_derby_schema_test")
        val derbyDb = KSLDatabase.createEmbeddedDerbyKSLDatabase("derby_schema_test", tempDir)
        val kslDb = KSLDatabase(derbyDb)
        assertTrue(kslDb.configured, "a freshly created Derby KSLDatabase must report the full table schema")
        assertTrue(kslDb.experimentNames.isEmpty(), "a fresh database must have no experiments")
    }

    @Test
    @DisplayName("String- and file-based SQL parsing produce identical statements (switch is behavior-preserving)")
    fun stringAndFileParsingAreEquivalent() {
        val resourceText = KSLDatabase::class.java.classLoader
            .getResourceAsStream("KSL_SQLite.sql")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

        val fromString = DatabaseIfc.parseQueriesInString(resourceText)
        assertTrue(fromString.isNotEmpty(), "the KSL_SQLite.sql schema must parse into one or more statements")

        val tempFile = Files.createTempFile("KSL_SQLite", ".sql")
        Files.writeString(tempFile, resourceText)
        val fromFile = DatabaseIfc.parseQueriesInSQLScript(tempFile)

        assertEquals(
            fromFile, fromString,
            "parsing the schema from a string must yield the same statements as parsing it from a file",
        )
    }

    @Test
    @DisplayName("Schema scripts resolve as classpath resources; a bogus name resolves to null")
    fun schemaResourcesAreOnTheClasspath() {
        val loader = KSLDatabase::class.java.classLoader
        assertNotNull(
            loader.getResourceAsStream("KSL_SQLite.sql"),
            "KSL_SQLite.sql must be on the classpath for SQLite creation",
        )
        assertNotNull(
            loader.getResourceAsStream("KSL_Db.sql"),
            "KSL_Db.sql must be on the classpath for Derby/Postgres creation",
        )
        assertNull(
            loader.getResourceAsStream("KSL_DoesNotExist.sql"),
            "a missing resource must resolve to null so executeSchemaResource throws a clear error",
        )
    }

    @Test
    @DisplayName("Creation succeeds without the legacy dbScriptsDir script file (the regression that broke the server)")
    @Suppress("DEPRECATION")
    fun creationSucceedsWithoutLegacyScriptFile() {
        // Before the fix, creation read KSL_SQLite.sql from this working-directory
        // location; deleting it then creating reproduces the server's
        // "script file does not exist" failure under the old code.
        val legacyScript = KSLDatabase.dbScriptsDir.resolve("KSL_SQLite.sql")
        Files.deleteIfExists(legacyScript)

        val tempDir = Files.createTempDirectory("ksl_no_script_file_test")
        val kslDb = KSLDatabase.createKSLDatabase("no_script_file_test", tempDir)

        assertTrue(kslDb.configured, "creation must succeed without the legacy dbScriptsDir script file present")
        assertFalse(
            Files.exists(legacyScript),
            "creation must not depend on (or recreate) the legacy dbScriptsDir script file",
        )
    }
}
