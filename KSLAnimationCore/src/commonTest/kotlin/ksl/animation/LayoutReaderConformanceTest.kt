/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the web player's layout reader against drifting from the layout the desktop app writes.
 *
 * The reader in `AnimationLayoutReader.kt` is a second declaration of a format KSLCore also declares.
 * That is deliberate — it is what keeps KSLCore free of edits made only to serve a renderer — but a
 * duplicated format declaration is exactly the kind of thing that rots quietly, and the failure would be
 * invisible until someone opened an animation and found an element missing.
 *
 * So the documents below are verbatim `.lay.json` output from the desktop app, and the assertions are on
 * the fields the player actually draws with. If the app starts writing a field the player needs and the
 * reader does not model, this is what says so.
 *
 * The reader deliberately omits `spaceGeometry` (grid obstacle overlays, not drawn yet), and one test
 * below pins that omission as *tolerated* rather than accidental — the codec ignores unknown keys, so a
 * layout carrying it still loads.
 */
class LayoutReaderConformanceTest {

    /** Verbatim from `AnnotatedClinic.lay.json`, trimmed to the sections a reader must handle. */
    private val clinicJson = """
        {
          "title": "Annotated Clinic (declared entity types + annotated processes)",
          "baseTimeUnit": "MILLISECOND",
          "width": 640.0,
          "height": 380.0,
          "objectClasses": [
            { "typeName": "Patient", "shape": "CIRCLE", "color": "#1f77b4", "size": 14.0, "imageRef": null, "label": null },
            { "typeName": "VipPatient", "shape": "CIRCLE", "color": "#d62728", "size": 14.0, "imageRef": null, "label": null }
          ],
          "background": [
            { "kind": "TEXT", "points": [ { "x": 24.0, "y": 60.0, "z": 0.0 } ], "text": "Clinic",
              "color": "#333333", "strokeWidth": 1.0, "imageRef": null, "fontSize": 14.0, "fontFamily": null }
          ],
          "queues": [
            { "queueName": "Nurse:Q", "position": { "x": 320.0, "y": 170.0, "z": 0.0 },
              "growthDegrees": 90.0, "spacing": 12.0, "maxShown": 10 }
          ],
          "resources": [
            { "resourceName": "Nurse", "position": { "x": 430.0, "y": 170.0, "z": 0.0 }, "size": 20.0,
              "idleColor": "#2ca02c", "busyColor": "#d62728", "failedColor": "#7f7f7f",
              "inactiveColor": "#cccccc", "idleImage": null, "busyImage": null, "failedImage": null,
              "inactiveImage": null, "showValue": false }
          ],
          "clocks": [
            { "position": { "x": 24.0, "y": 30.0, "z": 0.0 }, "format": "0.0", "label": "Time", "fontSize": 12.0 }
          ],
          "spaceGeometry": []
        }
    """.trimIndent()

    /** Verbatim shape from `FlockVecOn.lay.json`: a spatial layout with a continuous space. */
    private val flockJson = """
        {
          "title": null, "baseTimeUnit": "SECOND", "width": 100.0, "height": 116.0,
          "objectClasses": [
            { "typeName": "Boid", "shape": "CIRCLE", "color": "#1f77b4", "size": 1.8, "imageRef": null, "label": null }
          ],
          "spaces": [
            { "type": "Continuous", "name": "sky", "xMin": 0.0, "xMax": 100.0, "yMin": 0.0, "yMax": 100.0, "torus": true }
          ],
          "agentStateColors": {},
          "spaceGeometry": []
        }
    """.trimIndent()

    @Test
    fun readsAProcessViewLayoutTheDesktopWrote() {
        val layout = AnimationLayout.fromJson(clinicJson)
        assertEquals(640.0, layout.width)
        assertEquals(380.0, layout.height)
        assertEquals("MILLISECOND", layout.baseTimeUnit)

        val patient = assertNotNull(layout.objectClasses.firstOrNull { it.typeName == "Patient" })
        assertEquals(LayoutShape.CIRCLE, patient.shape)
        assertEquals("#1f77b4", patient.color)
        assertEquals(14.0, patient.size)

        val queue = assertNotNull(layout.queues.singleOrNull())
        assertEquals("Nurse:Q", queue.queueName)
        assertEquals(320.0, queue.position.x)
        assertEquals(90.0, queue.growthDegrees, "the growth angle drives which way members stack")
        assertEquals(12.0, queue.spacing)

        val resource = assertNotNull(layout.resources.singleOrNull())
        assertEquals("Nurse", resource.resourceName)
        assertEquals("#2ca02c", resource.idleColor)
        assertEquals("#d62728", resource.busyColor)

        val clock = assertNotNull(layout.clocks.singleOrNull())
        assertEquals("Time", clock.label)
        assertEquals(12.0, clock.fontSize)

        val text = assertNotNull(layout.background.singleOrNull())
        assertEquals(BackgroundKind.TEXT, text.kind)
        assertEquals("Clinic", text.text)
        assertEquals(14.0, text.fontSize)
    }

    @Test
    fun readsASpatialLayoutIncludingItsSpaceDiscriminator() {
        val layout = AnimationLayout.fromJson(flockJson)
        assertEquals(100.0, layout.width)
        val space = assertNotNull(layout.spaces.singleOrNull() as? SpatialSpaceDescriptor.Continuous)
        assertEquals("sky", space.name)
        assertEquals(100.0, space.xMax)
        assertTrue(space.torus, "a toroidal space must survive, or agents wrap wrongly")
        assertEquals(1.8, layout.objectClasses.single().size)
    }

    /**
     * An obstacle overlay is read for the cells a wall is drawn from, and the pathfinding half of the same
     * document — movement rule, corner cutting, per-cell costs — is skipped rather than fatal. That split is
     * the whole reason the player can draw a wall without pulling in the agent-modelling machinery, so both
     * halves of it are asserted here.
     */
    @Test
    fun readsAnObstacleOverlayAndSkipsThePathfindingHalfOfIt() {
        val withGeometry = """
            { "width": 100.0, "height": 100.0,
              "spaceGeometry": [ { "spaceName": "grid", "cols": 4, "rows": 4, "torus": false,
                                   "movementRule": "MOORE", "allowCornerCutting": false,
                                   "blockedCells": [ { "col": 1, "row": 1 }, { "col": 1, "row": 2 } ],
                                   "cellCosts": [ { "col": 0, "row": 0, "cost": 3.0 } ] } ],
              "someFutureField": { "nested": true } }
        """.trimIndent()
        val layout = AnimationLayout.fromJson(withGeometry)
        assertEquals(100.0, layout.width, "unmodelled fields must be skipped, not fatal")
        val geometry = assertNotNull(layout.spaceGeometry.singleOrNull())
        assertEquals("grid", geometry.spaceName)
        assertEquals(4, geometry.cols)
        assertEquals(
            listOf(1 to 1, 1 to 2), geometry.blockedCells.map { it.col to it.row },
            "the blocked cells are what a wall is drawn from",
        )
    }

    /** An empty document must produce a usable layout rather than throwing, so a bare trace still plays. */
    @Test
    fun readsAMinimalDocument() {
        val layout = AnimationLayout.fromJson("{}")
        assertEquals(1000.0, layout.width, "the declared default canvas")
        assertEquals(700.0, layout.height)
        assertTrue(layout.queues.isEmpty() && layout.resources.isEmpty())
    }

    /** Round-tripping through the reader preserves what the player reads back. */
    @Test
    fun readerRoundTripsItsOwnOutput() {
        val layout = AnimationLayout.fromJson(clinicJson)
        assertEquals(layout, AnimationLayout.fromJson(layout.toJson()))
    }
}
