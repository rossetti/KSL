package ksl.testutils

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import java.awt.GraphicsEnvironment

/**
 * Disables the annotated test class or method when the JVM is headless (no display).
 *
 * KSL report/plot tests render lets-plot figures, which need AWT font/graphics metrics
 * and throw `java.awt.HeadlessException` in a headless JVM (e.g. CI without a display).
 * The first such failure raises an `ExceptionInInitializerError` that poisons the
 * lets-plot class for the rest of the JVM fork, so every later plot test in the module
 * fails too. Tests carrying this annotation are reported as skipped in a headless JVM
 * and run normally on any machine with a display.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(HeadlessCondition::class)
annotation class DisabledIfHeadless

/** Backing [ExecutionCondition] for [DisabledIfHeadless]. */
class HeadlessCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (GraphicsEnvironment.isHeadless())
            ConditionEvaluationResult.disabled("Headless JVM - renders a lets-plot figure that needs a display")
        else
            ConditionEvaluationResult.enabled("Display available")
}
