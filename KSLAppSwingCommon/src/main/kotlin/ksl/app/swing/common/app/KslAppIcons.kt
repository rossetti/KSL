/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.common.app

import java.awt.HeadlessException
import java.awt.Image
import java.awt.Taskbar
import java.util.EnumMap
import javax.imageio.ImageIO
import javax.swing.JFrame

/** Stable identities for the eight desktop applications and their icon resource folders. */
enum class KslDesktopApp(val assetName: String) {
    SINGLE("Single"),
    SCENARIO("Scenario"),
    EXPERIMENT("Experiment"),
    SIMOPT("Simopt"),
    DISTRIBUTION("Distribution"),
    RESULTS("Results"),
    BUNDLE("Bundle"),
    ANIMATION("Animation"),
}

/** Applies the reviewed KSL artwork to Swing windows and the platform taskbar or Dock. */
object KslAppIcons {

    internal val imageSizes = listOf(16, 24, 32, 48, 64, 128, 256, 512)
    private val cache = EnumMap<KslDesktopApp, List<Image>>(KslDesktopApp::class.java)

    /** Sets the frame's multi-resolution icon list and, where supported, the application icon. */
    fun install(app: KslDesktopApp, frame: JFrame) {
        val images = imagesFor(app)
        frame.iconImages = images
        installTaskbarIcon(images.last())
    }

    @Synchronized
    internal fun imagesFor(app: KslDesktopApp): List<Image> = cache.getOrPut(app) {
        imageSizes.map { size ->
            val path = "/ksl/app/icons/${app.assetName}/${app.assetName}-$size.png"
            val resource = checkNotNull(KslAppIcons::class.java.getResource(path)) {
                "missing desktop icon resource: $path"
            }
            checkNotNull(ImageIO.read(resource)) { "unreadable desktop icon resource: $path" }
        }
    }

    private fun installTaskbarIcon(image: Image) {
        try {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = image
        } catch (_: HeadlessException) {
            // Frames cannot be shown headless either; keep resource-level tests headless-safe.
        } catch (_: UnsupportedOperationException) {
            // Some desktop environments expose Taskbar but not application icon mutation.
        } catch (_: SecurityException) {
            // A restrictive runtime must not prevent the Swing window itself from opening.
        }
    }
}
