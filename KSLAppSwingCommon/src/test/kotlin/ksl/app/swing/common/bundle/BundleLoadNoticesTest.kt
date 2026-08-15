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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Pins the *Load JAR…* wording every app shares.  These messages are the app's
 *  voice to a simulation student, so the assertions are about what that student
 *  reads: no Java service-provider vocabulary, the JAR named by file name, and one
 *  severity per outcome regardless of which app is asking.
 */
class BundleLoadNoticesTest {

    private val jar: Path = Path.of("/Users/someone/Documents/KSLWork/bundles/my-models.jar")

    private fun describe(outcome: LoadBundleResult, followUp: String? = null) =
        BundleLoadNotices.describe(outcome, jar, followUp)

    private val everyOutcome: List<LoadBundleResult> = listOf(
        LoadBundleResult.Loaded(listOf("a.b")),
        LoadBundleResult.Reloaded(listOf("a.b")),
        LoadBundleResult.AlreadyLoaded(listOf("a.b")),
        LoadBundleResult.NoBundles,
        LoadBundleResult.Rejected("no bundle.toml manifest"),
        LoadBundleResult.Failed("boom"),
    )

    @Test
    @DisplayName("no outcome exposes the service-provider interface to the user")
    fun noOutcomeNamesTheServiceProviderInterface() {
        for (outcome in everyOutcome) {
            val message = describe(outcome).message
            assertFalse(
                message.contains("KSLModelBundle") || message.contains("SPI") ||
                    message.contains("service registration"),
                "user-facing message should not name the SPI; was: $message"
            )
        }
    }

    @Test
    @DisplayName("every outcome names the JAR by file name, not by absolute path")
    fun everyOutcomeNamesTheJarByFileName() {
        for (outcome in everyOutcome) {
            val message = describe(outcome).message
            assertFalse(
                message.contains(jar.parent.toString()),
                "message should not carry the full path; was: $message"
            )
            if (outcome !is LoadBundleResult.Loaded) {
                assertTrue(
                    message.contains("my-models.jar"),
                    "message should name the JAR; was: $message"
                )
            }
        }
    }

    @Test
    @DisplayName("severity follows what the user got")
    fun severityFollowsTheOutcome() {
        assertEquals(NotificationSeverity.INFO, describe(LoadBundleResult.Loaded(listOf("a"))).severity)
        assertEquals(NotificationSeverity.INFO, describe(LoadBundleResult.Reloaded(listOf("a"))).severity)
        assertEquals(NotificationSeverity.INFO, describe(LoadBundleResult.AlreadyLoaded(listOf("a"))).severity)
        // Nothing usable came of the file, but nothing broke — the user can pick another.
        assertEquals(NotificationSeverity.WARNING, describe(LoadBundleResult.NoBundles).severity)
        assertEquals(NotificationSeverity.WARNING, describe(LoadBundleResult.Rejected("why")).severity)
        // Only a thrown load is an error.
        assertEquals(NotificationSeverity.ERROR, describe(LoadBundleResult.Failed("why")).severity)
    }

    @Test
    @DisplayName("a refusal keeps the substrate's reason, which points at the fix")
    fun aRefusalKeepsItsReason() {
        val notice = describe(LoadBundleResult.Rejected("no bundle.toml manifest"))
        assertTrue(notice.message.contains("no bundle.toml manifest"), notice.message)
    }

    @Test
    @DisplayName("the follow-up hint is appended only when bundles actually became available")
    fun followUpAppearsOnlyWhenSomethingLoaded() {
        val hint = "Use Open Model… to choose one."
        assertTrue(describe(LoadBundleResult.Loaded(listOf("a")), hint).message.endsWith(hint))
        assertTrue(describe(LoadBundleResult.Reloaded(listOf("a")), hint).message.endsWith(hint))
        // A JAR that yielded nothing has no next step to offer.
        for (outcome in listOf(
            LoadBundleResult.AlreadyLoaded(listOf("a")),
            LoadBundleResult.NoBundles,
            LoadBundleResult.Rejected("why"),
            LoadBundleResult.Failed("why"),
        )) {
            assertFalse(
                describe(outcome, hint).message.contains(hint),
                "no next step should be offered for $outcome"
            )
        }
    }
}
