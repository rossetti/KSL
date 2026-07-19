package ksl.server.tray

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TrayIconsTest {

    @Test
    @DisplayName("statusImage renders a sized image for every state (works headless)")
    fun rendersEachState() {
        TrayIcons.State.entries.forEach { state ->
            val img = TrayIcons.statusImage(state, size = 20)
            assertNotNull(img)
            assertEquals(20, img.getWidth(null))
            assertEquals(20, img.getHeight(null))
        }
    }
}
