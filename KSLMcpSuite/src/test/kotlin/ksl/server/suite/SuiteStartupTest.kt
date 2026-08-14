package ksl.server.suite

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Guards the macOS accessory-process marking that keeps the suite out of the Dock.
 *
 * The tile itself is an OS effect and cannot be asserted from a unit test; what IS worth guarding
 * is that the property is set by our own code rather than inherited from the build, because only
 * the in-code path covers `java -jar` and IDE runs. The root build sets this same property on
 * every test JVM (see the root build.gradle.kts test configuration), so a bare "is it true?"
 * assertion would pass even with configureMacDesktop() deleted. Each test therefore clears or
 * poisons the value first, and the original is restored afterwards so nothing leaks to the other
 * test classes sharing this fork.
 */
class SuiteStartupTest {

    private var saved: String? = null

    @BeforeEach
    fun saveProperty() {
        saved = System.getProperty(UI_ELEMENT)
    }

    @AfterEach
    fun restoreProperty() {
        saved?.let { System.setProperty(UI_ELEMENT, it) } ?: System.clearProperty(UI_ELEMENT)
    }

    @Test
    @DisplayName("configureMacDesktop marks the process a UI accessory when nothing has set it")
    fun setsUiElementFromUnset() {
        System.clearProperty(UI_ELEMENT)

        configureMacDesktop()

        assertEquals("true", System.getProperty(UI_ELEMENT))
    }

    @Test
    @DisplayName("configureMacDesktop overrides an inherited value, so it does not depend on the build")
    fun overridesInheritedValue() {
        // Poison it: if configureMacDesktop() were removed, this test fails rather than riding on
        // the value the Gradle test JVM already supplies.
        System.setProperty(UI_ELEMENT, "false")

        configureMacDesktop()

        assertEquals("true", System.getProperty(UI_ELEMENT))
    }

    private companion object {
        const val UI_ELEMENT = "apple.awt.UIElement"
    }
}
