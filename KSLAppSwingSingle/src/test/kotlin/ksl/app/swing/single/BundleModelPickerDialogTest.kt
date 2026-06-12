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

package ksl.app.swing.single

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Tests for [BundleModelPickerDialog] — the modal picker shown by
 *  [KSLSingleApp.launch] when the developer omits
 *  `modelBuilder(...)` from the `kslSingleApp { … }` DSL.
 *
 *  Pins the public [BundleModelPickerDialog.Result] sealed shape
 *  that callers (currently only [KSLSingleApp.resolveController])
 *  switch over.  The modal dialog itself is not unit-tested headless
 *  here — its behavioral coverage is the bundle-mode launcher
 *  `BundleLaunchedSingleApp` plus the bundle-mode controller tests in
 *  [SingleAppControllerConfigurationTest].
 */
class BundleModelPickerDialogTest {

    @Test
    fun `Result has Selected and Cancelled variants`() {
        val variants: List<BundleModelPickerDialog.Result> = listOf(
            BundleModelPickerDialog.Result.Selected(bundleId = "b", modelId = "m"),
            BundleModelPickerDialog.Result.Cancelled,
        )
        assertEquals(2, variants.size)
        assertTrue(variants[0] is BundleModelPickerDialog.Result.Selected)
        assertTrue(variants[1] is BundleModelPickerDialog.Result.Cancelled)
    }

    @Test
    fun `Selected exposes bundleId and modelId`() {
        val selected = BundleModelPickerDialog.Result.Selected(
            bundleId = "myBundle",
            modelId = "myModel"
        )
        assertEquals("myBundle", selected.bundleId)
        assertEquals("myModel", selected.modelId)
    }

    @Test
    fun `Selected with identical bundleId+modelId compares equal`() {
        val a = BundleModelPickerDialog.Result.Selected("b", "m")
        val b = BundleModelPickerDialog.Result.Selected("b", "m")
        assertEquals(a, b,
            "data class equality should match on bundleId + modelId.")
    }

    @Test
    fun `Cancelled is a singleton object`() {
        val a: BundleModelPickerDialog.Result = BundleModelPickerDialog.Result.Cancelled
        val b: BundleModelPickerDialog.Result = BundleModelPickerDialog.Result.Cancelled
        assertTrue(a === b, "Cancelled must be a singleton.")
    }
}
