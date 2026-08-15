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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Tests for [BundleModelPickerDialog] — the modal picker every bundle-mode app
 *  shows, both at startup (Single) and mid-session (Single / Animation
 *  *Open Model…*).
 *
 *  Pins the public [BundleModelPickerDialog.Result] sealed shape that callers
 *  switch over, plus the confirm-button contract the callers depend on.  The
 *  modal itself is not unit-tested here — it needs a display and blocks the EDT;
 *  its behavioral coverage is the bundle-mode launchers and controller tests in
 *  the app modules.
 */
class BundleModelPickerDialogTest {

    @Test
    @DisplayName("Result has Selected and Cancelled variants")
    fun resultHasSelectedAndCancelledVariants() {
        val variants: List<BundleModelPickerDialog.Result> = listOf(
            BundleModelPickerDialog.Result.Selected(bundleId = "b", modelId = "m"),
            BundleModelPickerDialog.Result.Cancelled,
        )
        assertEquals(2, variants.size)
        assertTrue(variants[0] is BundleModelPickerDialog.Result.Selected)
        assertTrue(variants[1] is BundleModelPickerDialog.Result.Cancelled)
    }

    @Test
    @DisplayName("Selected exposes bundleId and modelId")
    fun selectedExposesBundleIdAndModelId() {
        val selected = BundleModelPickerDialog.Result.Selected(
            bundleId = "myBundle",
            modelId = "myModel"
        )
        assertEquals("myBundle", selected.bundleId)
        assertEquals("myModel", selected.modelId)
    }

    @Test
    @DisplayName("Selected with identical bundleId+modelId compares equal")
    fun selectedWithIdenticalIdsComparesEqual() {
        val a = BundleModelPickerDialog.Result.Selected("b", "m")
        val b = BundleModelPickerDialog.Result.Selected("b", "m")
        assertEquals(a, b, "data class equality should match on bundleId + modelId.")
    }

    @Test
    @DisplayName("Cancelled is a singleton object")
    fun cancelledIsASingletonObject() {
        val a: BundleModelPickerDialog.Result = BundleModelPickerDialog.Result.Cancelled
        val b: BundleModelPickerDialog.Result = BundleModelPickerDialog.Result.Cancelled
        assertTrue(a === b, "Cancelled must be a singleton.")
    }

    @Test
    @DisplayName("the confirm button defaults to Open for callers that do not override it")
    fun confirmButtonDefaultsToOpen() {
        // The Animation app's Open Model… and the Single app's Open Model… both rely on the
        // default; only the Single app's startup picker overrides it (to "Pick").
        assertEquals("Open", BundleModelPickerDialog.DEFAULT_CONFIRM_BUTTON_TEXT)
    }
}
