package ksl.app.swing.bundle

import org.junit.jupiter.api.Test

/**
 * Guards against property/`init` ordering bugs in the Swing panels: constructing a
 * panel runs its `init` block, which would throw (e.g. an NPE on a not-yet-initialized
 * property) before any display is needed. The panels are otherwise verified manually.
 */
class PanelConstructionTest {

    @Test
    fun `the workbench panels construct without initialization errors`() {
        val c = BundleWorkbenchController("Test Workbench")
        try {
            ModelMetadataPanel(c)
            CatalogTablePanel(c)
            IdentityPanel(c)
        } finally {
            c.dispose()
        }
    }
}
