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

package ksl.service.store

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStoreTest {

    @Test
    fun `save, load, list, and delete round-trip across kinds`() {
        val store = DocumentStore(Files.createTempDirectory("doc-store"))
        store.save("layout", "baseline", "{\"title\":\"x\"}")
        store.save("run", "cfg1", "run-content")

        assertEquals("{\"title\":\"x\"}", store.load("layout", "baseline"), "load returns the saved content")
        assertNull(store.load("layout", "missing"), "an unknown document is null")

        assertEquals(2, store.list().size, "both documents are listed")
        val layouts = store.list("layout")
        assertEquals(listOf("baseline"), layouts.map { it.name }, "kind filter isolates documents")
        assertEquals("layout", layouts.first().kind)

        assertTrue(store.delete("layout", "baseline"), "delete removes it")
        assertNull(store.load("layout", "baseline"))
        assertFalse(store.delete("layout", "baseline"), "deleting again is false")
        assertEquals(1, store.list().size, "only the run document remains")
    }

    @Test
    fun `names are sanitized so a document cannot escape the store root`() {
        val root = Files.createTempDirectory("doc-store-safe")
        val store = DocumentStore(root)
        val path = store.save("run", "../../evil", "x")
        assertTrue(path.normalize().startsWith(root.normalize()), "the file must stay under the root; got $path")
    }
}
