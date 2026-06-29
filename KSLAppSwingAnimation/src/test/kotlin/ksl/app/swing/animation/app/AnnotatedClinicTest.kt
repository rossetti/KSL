package ksl.app.swing.animation.app

import ksl.animation.animationInventory
import ksl.app.swing.animation.examples.AnimationDemo
import ksl.examples.general.animationbundle.Example14AnnotatedClinic
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P9 (#21): the annotated example model surfaces its discovery constructs in the pre-run inventory —
 * declared entity types via entityType<T>(), annotated processes, and the opted-out internal Janitor.
 */
class AnnotatedClinicTest {

    @Test
    fun `declared types and annotated processes appear in the inventory with include flags`() {
        val inv = Example14AnnotatedClinic.buildModel().animationInventory()
        val byName = inv.entityTypes.associateBy { it.typeName }

        val patient = byName["Patient"]; val vip = byName["VipPatient"]; val janitor = byName["Janitor"]
        assertNotNull(patient, "Patient declared via entityType<T>() is in the inventory: ${byName.keys}")
        assertNotNull(vip, "VipPatient declared via entityType<T>() is in the inventory")
        assertTrue(patient.include && vip.include, "declared patient types are included by default")

        assertNotNull(janitor, "the internal Janitor is discovered (reflection fallback)")
        assertFalse(janitor.include, "@KSLAnimatedEntity(include=false) opts the Janitor out")

        // Annotated process names are surfaced and the Janitor's process is opted out.
        assertTrue(patient.processes.any { it.name == "visit" && it.include }, "Patient's @KSLAnimatedProcess 'visit' surfaced")
        janitor.processes.forEach { assertFalse(it.include, "the Janitor's process '${it.name}' is opted out") }
    }

    @Test
    fun `object styles and process colors are editable tables of the model's types and processes (batch 3)`() {
        val c = AnimationAppController("clinic", object : ksl.simulation.ModelBuilderIfc {
            override fun build(mc: Map<String, String>?, e: ksl.simulation.ExperimentRunParametersIfc?) =
                Example14AnnotatedClinic.buildModel()
        })
        try {
            val r = onEdtClinic {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                val types = panel.objectStyleTypesForTest()
                val procs = panel.processNamesForTest()
                panel.addObjectStyleWithImageForTest("Patient", "")          // table-driven style apply
                panel.setProcessColorForTest("visit", "#123456")
                arrayOf(types, procs) to (c.layout.value!!.objectClasses.any { it.typeName == "Patient" } to
                    c.layout.value!!.processColors["visit"])
            }
            assertTrue(r.first[0].contains("Patient"), "object-styles table lists declared types: ${r.first[0]}")
            assertTrue(r.first[1].contains("visit"), "process-colors table lists the model's processes: ${r.first[1]}")
            assertTrue(r.second.first, "applying a style to a table-selected type works")
            assertEquals("#123456", r.second.second, "applying a process color to a table-selected process works")
        } finally { c.close() }
    }

    private fun <T> onEdtClinic(block: () -> T): T {
        var res: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { res = runCatching(block) }
        return res.getOrThrow()
    }

    @Test
    fun `the annotated clinic runs and renders end to end`() {
        val model = Example14AnnotatedClinic.buildModel()
        val files = AnimationDemo.generate(model, Example14AnnotatedClinic.buildLayout(model), baseName = "AnnotatedClinic")
        assertTrue(java.nio.file.Files.exists(files.traceFile), "a trace file is produced")
        assertTrue(java.nio.file.Files.exists(files.layoutFile), "a layout file is produced")
    }
}
