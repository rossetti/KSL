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

import ksl.app.editor.BundleLibraryController.LoadBundleResult
import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSink
import java.nio.file.Path

/**
 *  What to tell the user after a *Load JAR…*, and how loudly.
 *
 *  One outcome should read the same in every app.  Before this, the same
 *  "that JAR carries no bundle" outcome appeared as four different sentences at
 *  four severities, and most of them named `KSLModelBundle` — the Java
 *  service-provider interface — at a simulation student who has no reason to
 *  know what an SPI is.  The wording here is deliberately about the file and
 *  what the user got, and it names the JAR by file name, not by absolute path.
 */
data class BundleLoadNotice(
    val message: String,
    val severity: NotificationSeverity
)

/** Builds the [BundleLoadNotice] for a [LoadBundleResult]. */
object BundleLoadNotices {

    /**
     *  Describe [outcome] for the user, naming [jar] by its file name.
     *
     *  Severity follows what the user got:
     *
     *  - **Info** — bundles are now available ([LoadBundleResult.Loaded],
     *    [LoadBundleResult.Reloaded]) or already were
     *    ([LoadBundleResult.AlreadyLoaded]).
     *  - **Warning** — the file yielded nothing usable, but nothing broke and
     *    the user can pick another ([LoadBundleResult.NoBundles],
     *    [LoadBundleResult.Rejected]).  This is what the substrate's own
     *    `LoadBundleResult` documentation asks for: a refusal is "a normal,
     *    user-correctable outcome — surface it as a warning, not an error."
     *  - **Error** — the load attempt threw ([LoadBundleResult.Failed]).
     *
     *  @param followUp optional next step appended when new bundles actually became
     *  available — e.g. "Use Open Model… to choose one." in the apps where that is
     *  how the user reaches them.  Appended to nothing else: a JAR that yielded no
     *  bundles has no next step to offer.
     */
    fun describe(outcome: LoadBundleResult, jar: Path, followUp: String? = null): BundleLoadNotice {
        val name = jar.fileName?.toString() ?: jar.toString()
        val next = followUp?.let { "  $it" } ?: ""
        return when (outcome) {
            is LoadBundleResult.Loaded -> info(
                "Loaded ${outcome.newBundleIds.size} bundle(s) from $name: " +
                    outcome.newBundleIds.joinToString(", ") + next
            )
            is LoadBundleResult.Reloaded -> info(
                "Reloaded $name from disk: " + outcome.bundleIds.joinToString(", ") + next
            )
            is LoadBundleResult.AlreadyLoaded -> info(
                "$name is already loaded (no change): " + outcome.bundleIds.joinToString(", ")
            )
            LoadBundleResult.NoBundles -> warn(
                "$name contains no KSL model bundle."
            )
            is LoadBundleResult.Rejected -> warn(
                "Skipped $name: ${outcome.reason}"
            )
            is LoadBundleResult.Failed -> error(
                "Could not load $name: ${outcome.reason}"
            )
        }
    }

    private fun info(message: String) = BundleLoadNotice(message, NotificationSeverity.INFO)
    private fun warn(message: String) = BundleLoadNotice(message, NotificationSeverity.WARNING)
    private fun error(message: String) = BundleLoadNotice(message, NotificationSeverity.ERROR)
}

/** Emit [notice] at its own severity.  Lets a host surface a load outcome in one line. */
fun NotificationSink.post(notice: BundleLoadNotice) {
    when (notice.severity) {
        NotificationSeverity.INFO -> info(notice.message)
        NotificationSeverity.WARNING -> warn(notice.message)
        NotificationSeverity.ERROR -> error(notice.message)
    }
}
