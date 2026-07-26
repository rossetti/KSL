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

/*
 * The shared animation core, compiled for Kotlin/JS so a browser can replay a `.atf` trace.
 *
 * HOW THIS WORKS — and why it looks unusual.
 *
 * There is exactly ONE copy of every source file below, and it lives where it always has: in KSLCore,
 * KSLApp, or KSLAppSwingAnimation. This build compiles those same files a second time, for a different
 * target. KSLCore keeps compiling them for the JVM exactly as before, so the desktop application is
 * untouched and there is no possibility of the two copies drifting — there is no second copy.
 *
 * The file list is spelled out one entry at a time rather than globbed. That is deliberate: a wildcard
 * over `ksl/animation` would silently pull in the parts that legitimately depend on the simulation
 * engine or on JVM I/O (AnimationCapture, the emitters, AnimationInventory's reflective model walk,
 * the trace reader/writer), and the failure would surface as a confusing compile error rather than as
 * an obvious "you added a file to the shared set" diff.
 *
 * Anything added here must compile for a non-JVM target. That rules out the obvious `java.*` imports,
 * but also — less obviously — Kotlin stdlib members that only exist on the JVM, such as `removeIf`,
 * `toSortedMap`, `toSortedSet` and `putIfAbsent`. The CI job that runs this build is what catches such
 * an addition, since the JVM compilation will happily accept it.
 *
 * This arrangement is a bridge, not a destination. At Path A (plan §6) these files move here for real,
 * this block disappears, and KSLCore depends on the published module instead.
 *
 * NOT PUBLISHED. Applying maven-publish here would create a second Maven artifact and force exactly the
 * KSLCore release that Path B exists to avoid.
 */

plugins {
    kotlin("multiplatform") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = "R1.4"

repositories {
    mavenCentral()
}

/** Sources shared with the JVM build, relative to each module's `src/main/kotlin`. */
val sharedSources = listOf(
    // ── the wire format: trace events + the presentation document (KSLCore) ──
    "ksl/animation/AnimationEvent.kt",
    "ksl/animation/AnimationTraceHeader.kt",
    "ksl/animation/AnimationLayout.kt",
    "ksl/animation/AnimationInventoryTypes.kt",
    "ksl/animation/CaptureSpec.kt",
    "ksl/animation/OverlaySpec.kt",
    "ksl/animation/AnimationSink.kt",
    "ksl/animation/geom/BoundingBox.kt",
    // ── grid geometry carried inside a layout (KSLCore) ──
    "ksl/modeling/agent/Cell.kt",
    "ksl/modeling/agent/MovementRule.kt",
    "ksl/modeling/agent/GridGeometrySpec.kt",
    // ── the replay engine: index a trace, query state at a time (KSLApp) ──
    "ksl/app/animation/io/AnimationSource.kt",
    "ksl/app/animation/replay/AnchorResolver.kt",
    "ksl/app/animation/replay/LayoutGeometry.kt",
    "ksl/app/animation/replay/ObjectClassSeeding.kt",
    "ksl/app/animation/replay/PositionInterpolator.kt",
    "ksl/app/animation/replay/ReplayCompatibility.kt",
    "ksl/app/animation/replay/ReplayModel.kt",
    "ksl/app/animation/replay/StepTimeline.kt",
    "ksl/app/animation/replay/StreamingTraceMiner.kt",
    // ── playback state machine (KSLAppSwingAnimation; already toolkit-free) ──
    "ksl/app/swing/animation/playback/PlaybackController.kt",
)

/*
 * Intentionally absent: AutoLayout.kt, AutoLayoutBuilder.kt and TraceAccumulators.kt. Those scaffold a
 * layout FROM a model, which is authoring, not replay — they reach for `Model` and the reflective
 * `AnimationInventory`, and a replay renderer never calls them. They stay JVM-only in KSLApp.
 */

kotlin {
    js(IR) {
        browser {
            testTask {
                useKarma {
                    // Headless Chrome only; no interactive browser is launched in CI.
                    useChromeHeadless()
                }
            }
        }
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            kotlin.setSrcDirs(
                listOf(
                    "${rootDir.parent}/KSLCore/src/main/kotlin",
                    "${rootDir.parent}/KSLApp/src/main/kotlin",
                    "${rootDir.parent}/KSLAppSwingAnimation/src/main/kotlin",
                )
            )
            kotlin.setIncludes(sharedSources)
            dependencies {
                // The platform-agnostic artifact, NOT the `-jvm` one KSLCore declares.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

/**
 * Fails the build if a shared source has picked up an import that cannot compile off the JVM. The
 * Kotlin compiler already catches this, but the message points at a line rather than at the reason, so
 * this reports the actual rule being broken.
 */
val checkSharedSourcesArePortable by tasks.registering {
    group = "verification"
    description = "Assert no shared animation source imports a JVM-only API."
    doLast {
        val roots = listOf("KSLCore", "KSLApp", "KSLAppSwingAnimation").map { File(rootDir.parent, "$it/src/main/kotlin") }
        val offenders = mutableListOf<String>()
        for (rel in sharedSources) {
            val file = roots.map { File(it, rel) }.firstOrNull { it.exists() }
                ?: throw GradleException("shared source not found: $rel")
            file.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("import java.") || t.startsWith("import javax.")) {
                    offenders += "$rel:${i + 1}  $t"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "These sources are compiled for the web and cannot use JVM-only APIs:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\nMove the platform-bound part into a same-package file that stays in the JVM module " +
                    "(see AnimationLayoutFiles.kt for the pattern)."
            )
        }
        logger.lifecycle("shared animation sources are portable (${sharedSources.size} files checked)")
    }
}

tasks.named("check") { dependsOn(checkSharedSourcesArePortable) }
