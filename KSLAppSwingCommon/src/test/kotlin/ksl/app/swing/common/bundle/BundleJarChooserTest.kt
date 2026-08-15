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

package ksl.app.swing.common.bundle

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Covers where *Load JAR…* opens.  The chooser itself needs a display, but the
 *  precedence — session memory, then the workspace bundles folder, then nothing —
 *  is pure and pinned here.
 */
class BundleJarChooserTest {

    @BeforeTest
    @AfterTest
    fun clearSessionMemory() {
        // The session memory is process-wide; keep it from leaking between tests
        // (and out of this class into the rest of the module's suite).
        BundleJarChooser.forgetForTest()
    }

    @Test
    @DisplayName("with no history and no workspace folder the chooser is left at the platform default")
    fun noHistoryAndNoWorkspaceFolderYieldsNull(@TempDir tempDir: Path) {
        assertNull(BundleJarChooser.startDirectory(null))
        assertNull(
            BundleJarChooser.startDirectory(tempDir.resolve("does-not-exist")),
            "a workspace folder that isn't there must not be handed to the chooser"
        )
    }

    @Test
    @DisplayName("with no history the workspace bundles folder is used")
    fun workspaceFolderIsUsedWithoutHistory(@TempDir tempDir: Path) {
        val bundles = tempDir.resolve("bundles").also { it.createDirectories() }
        assertEquals(bundles, BundleJarChooser.startDirectory(bundles))
    }

    @Test
    @DisplayName("the last directory a JAR came from this session wins over the workspace folder")
    fun sessionHistoryWinsOverTheWorkspaceFolder(@TempDir tempDir: Path) {
        val bundles = tempDir.resolve("bundles").also { it.createDirectories() }
        val buildOutput = tempDir.resolve("project/build/libs").also { it.createDirectories() }
        buildOutput.resolve("my-model.jar").createFile()

        BundleJarChooser.remember(buildOutput.resolve("my-model.jar"))

        assertEquals(buildOutput, BundleJarChooser.lastDirectory)
        assertEquals(
            buildOutput, BundleJarChooser.startDirectory(bundles),
            "a user who loaded a JAR from a build folder should return there, not to the workspace"
        )
    }

    @Test
    @DisplayName("a remembered directory that has since disappeared falls back to the workspace folder")
    fun staleHistoryFallsBackToTheWorkspaceFolder(@TempDir tempDir: Path) {
        val bundles = tempDir.resolve("bundles").also { it.createDirectories() }
        BundleJarChooser.remember(tempDir.resolve("gone/removed.jar"))
        assertEquals(bundles, BundleJarChooser.startDirectory(bundles))
    }
}
