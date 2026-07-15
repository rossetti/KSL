/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

package ksl.app.swing.common.app

import kotlin.test.Test
import kotlin.test.assertEquals

class KslAppIconsTest {

    @Test
    fun `every desktop app has every runtime icon size`() {
        KslDesktopApp.entries.forEach { app ->
            val images = KslAppIcons.imagesFor(app)
            assertEquals(KslAppIcons.imageSizes.size, images.size, app.name)
            assertEquals(
                KslAppIcons.imageSizes.map { it to it },
                images.map { it.getWidth(null) to it.getHeight(null) },
                app.name,
            )
        }
    }
}
