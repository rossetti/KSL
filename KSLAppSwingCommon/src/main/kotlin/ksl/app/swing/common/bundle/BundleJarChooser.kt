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

import java.awt.Component
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 *  The one *Load JAR…* file chooser, shared by every app's *Bundles* menu and by the
 *  startup model picker, so the dialog looks the same and opens in the same place
 *  wherever the user reaches it.
 *
 *  **Where it opens.**  A bare `JFileChooser` resolves the platform default every time
 *  it is constructed — Swing keeps no memory across chooser instances — so before this
 *  existed, every *Load JAR…* in every app opened at the user's home directory, session
 *  after session, however many JARs they had already loaded.  The start directory is now
 *  the first of:
 *
 *  1. the directory the user last loaded a JAR from **this session** ([lastDirectory]) —
 *     bundle JARs are often built somewhere outside the workspace, and going back there
 *     should be free after the first visit;
 *  2. the caller's `workspaceBundleDir` — normally
 *     `ksl.app.settings.WorkspaceLayout.preferredBundleDir`, i.e. where the app already
 *     looks for bundles and where the guides tell users to put their own;
 *  3. nothing — the chooser keeps the platform default.
 *
 *  Threading: Swing-confined; call on the EDT.
 */
object BundleJarChooser {

    /** Last directory a JAR was successfully chosen from, for this JVM session only.
     *  Not persisted: a remembered path is a convenience within a sitting, not a setting. */
    var lastDirectory: Path? = null
        private set

    /**
     *  The directory the chooser should open in, given the caller's
     *  [workspaceBundleDir].  Pure — no Swing involved — so the precedence is unit
     *  testable without a display.  Returns null when neither candidate exists, which
     *  leaves the chooser at the platform default.
     */
    fun startDirectory(workspaceBundleDir: Path?): Path? =
        listOfNotNull(lastDirectory, workspaceBundleDir)
            .firstOrNull { it.exists() && it.isDirectory() }

    /** Records the directory of a chosen [jar] as [lastDirectory]. */
    fun remember(jar: Path) {
        jar.parent?.let { lastDirectory = it }
    }

    /** Test-only: drop the session memory so ordering tests start from a known state. */
    internal fun forgetForTest() {
        lastDirectory = null
    }

    /**
     *  Show the modal *Load Bundle JAR* chooser over [parent], starting in
     *  [startDirectory] of [workspaceBundleDir].  Returns the chosen path — remembering
     *  its directory for the rest of the session — or null when the user cancels.
     */
    fun choose(parent: Component?, workspaceBundleDir: Path?): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Load Bundle JAR"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("Bundle JAR (*.jar)", "jar")
            startDirectory(workspaceBundleDir)?.let { currentDirectory = it.toFile() }
        }
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null
        val path = chooser.selectedFile?.toPath() ?: return null
        remember(path)
        return path
    }
}
