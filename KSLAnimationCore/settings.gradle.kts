/*
 * KSLAnimationCore — the shared animation core, compiled for the web.
 *
 * This is a STANDALONE Gradle build, deliberately NOT included in the KSL root settings.gradle.kts.
 *
 * Why standalone: the module needs no `project(...)` dependency on any KSL module — it compiles KSL's
 * own animation sources directly, by path (see build.gradle.kts). Keeping it out of the root build is
 * what guarantees the decision recorded as S5 in the plan: `./gradlew build` in the primary project
 * needs no Node.js, no Yarn, and no network, and its timing is unchanged. Students building KSLCore
 * never see the web toolchain.
 *
 * Build it explicitly:
 *   ./gradlew -p KSLAnimationCore build
 *
 * At Path A (the real extraction, plan §6) this inverts: the module will own its sources, KSLCore will
 * depend on it via `api(project(":KSLAnimationCore"))`, and it must then join the root settings.
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

rootProject.name = "KSLAnimationCore"
