/*
 * PHASE S SPIKE — DISPOSABLE.
 *
 * A standalone Gradle build, deliberately NOT included in the KSL root settings.gradle.kts, so that
 * nothing here can affect `./gradlew build` in the primary project. Deleted when Phase 1 begins
 * (see .claude/plans/animation-web-plan-2026-07-26.md, Phase S).
 *
 * Purpose: answer six unknowns before any KSLCore file is touched —
 *   1. Kotlin/JS + webpack toolchain viability and cold-build time
 *   2. DecompressionStream gzip decode of a .atf.gz in the browser
 *   3. G10 — Long -> BigInt cost in the Map<Long, ...> replay paths
 *   4. Bundle size of a Kotlin/JS + canvas page
 *   5. Whether the real .atf + .lay.json carry everything a renderer needs
 *   6. The natural shape of the DrawCmd vocabulary
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "animation-web-spike"
