// Build file for KSLApp: the application-services layer (ksl.app.*).
//
// Extracted from KSLCore so that the published KSLCore library carries only the
// simulation engine, while the bundling / run / orchestration / session / config
// and distribution-app infrastructure lives here. KSLApp is an INTERNAL module
// (not published to Maven, per decision D1): it is consumed by the KSLAppSwing*
// desktop apps, KSLBundleTools, and the test/fixture modules.
//
// Dependencies: everything ksl.app needs (kotlin-logging, kotlinx-datetime/
// -serialization/-coroutines, lets-plot, dataframe, hipparchus, tomlkt) is a
// KSLCore `api` dependency, inherited transitively through api(project(":KSLCore")).

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"   // NOT published to Maven (D1)

repositories {

    mavenCentral()
}

dependencies {

    // api so that modules depending on KSLApp also see KSLCore (and its api deps:
    // kotlin-logging, kotlinx-datetime/-serialization/-coroutines, lets-plot,
    // dataframe, hipparchus, tomlkt, ...).
    api(project(":KSLCore"))

    // --- test suite (per-module; Phase 7) ---
    testImplementation(project(":KSLTestModels"))
    testImplementation(project(":KSLTestSupport"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because the user can then provide their own logging specification
tasks.jar {
    exclude("logback.xml")
}

tasks.test {
    useJUnitPlatform()
}

/*
 * Publishing an animation to the web.
 *
 * `exportAnimationHtml` writes ONE self-contained page — player, trace and layout all inside a single
 * file — which opens from a file:// path and can be emailed or handed to a student like a PDF.
 * `exportAnimationGallery` writes a directory of animations sharing one copy of the player, which is the
 * right shape for a published site.
 *
 * Both need the browser player, which lives in the standalone KSLAnimationCore build (kept out of the
 * root build so `./gradlew build` needs no Node.js). Build it first:
 *   ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack
 *
 * Usage:
 *   ./gradlew :KSLApp:exportAnimationHtml -Ptrace=run.atf -PlayoutFile=run.lay.json -Pout=run.html
 *   ./gradlew :KSLApp:exportAnimationGallery -Pdir=<traces dir> -Pout=build/gallery
 */
listOf(
    "exportAnimationHtml" to "Write a KSL animation as a single self-contained HTML file.",
    "exportAnimationGallery" to "Write a gallery page for every trace in a directory."
).forEach { (taskName, taskDescription) ->
    tasks.register<JavaExec>(taskName) {
        group = "documentation"
        description = taskDescription
        dependsOn("classes")
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("ksl.app.animation.web.ExportCliKt")
        // The player bundle path defaults to KSLAnimationCore's build output, resolved from the repo root.
        workingDir = rootDir
        // NOTE: "layout" is unusable as a project property name -- Gradle's Project already has a
        // `layout` (ProjectLayout), so hasProperty("layout") is always true and property("layout")
        // returns that object rather than a -P value. Hence layoutFile.
        listOf("trace", "layoutFile", "dir", "out", "player", "autoplay", "fit").forEach { p ->
            if (project.hasProperty(p)) systemProperty(p, project.property(p)!!)
        }
    }
}
