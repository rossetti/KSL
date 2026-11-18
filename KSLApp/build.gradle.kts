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
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
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
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
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
    // with -Dksl.checkInvariants=true every model in the tests checks its resources' accounting after each event
    System.getProperty("ksl.checkInvariants")?.let { systemProperty("ksl.checkInvariants", it) }
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

/*
 * Packaging the animation web player.
 *
 * The player is built by the standalone KSLAnimationCore build, which is deliberately outside this one so
 * that `./gradlew build` needs no Node.js (decision S5). That leaves the question of how a *running* app
 * gets hold of it, since the desktop app and the MCP server both offer "export this animation as HTML".
 *
 * Answer: package it as a resource when it exists. Then neither of them needs to know where a build put
 * anything -- SelfContainedHtmlExporter.bundled() reads it off the classpath.
 *
 * Absence is tolerated on purpose. A plain `./gradlew build` with no web build produces a jar without the
 * player, and the export action reports what to run rather than failing obscurely. A release always has
 * it, because cutting the suite builds the web module first (see docs/releasing-suite.md).
 */
val animationPlayerBundle: File =
    rootDir.resolve("KSLAnimationCore/build/kotlin-webpack/js/productionExecutable/ksl-animation.js")

/**
 * The sources the player is compiled from that changed after it was built. The player compiles KSLCore,
 * KSLApp and KSLAppSwingAnimation sources by path (the shared list in KSLAnimationCore/build.gradle.kts)
 * plus its own, so a change to any of them, such as a new trace event or a fix to the replay, reaches the
 * player only when it is built again. Empty when the player is current, or has not been built.
 */
fun animationPlayerStaleSources(): List<File> {
    if (!animationPlayerBundle.isFile) return emptyList()
    val built = animationPlayerBundle.lastModified()
    val webBuild = rootDir.resolve("KSLAnimationCore")
    val buildScript = webBuild.resolve("build.gradle.kts")
    val roots = listOf("KSLCore", "KSLApp", "KSLAppSwingAnimation").map { rootDir.resolve("$it/src/main/kotlin") }
    val shared = Regex(""""(ksl/[^"]+\.kt)"""").findAll(buildScript.readText()).map { it.groupValues[1] }.toSet()
    val sources = shared.flatMap { path -> roots.map { it.resolve(path) } }.filter { it.isFile } +
        webBuild.resolve("src").walkTopDown().filter { it.isFile && !it.path.contains("Test") }.toList() +
        buildScript
    return sources.filter { it.lastModified() > built }.sortedBy { it.path }
}

val packageAnimationPlayer by tasks.registering(Copy::class) {
    description = "Package the animation web player into KSLApp resources, when it has been built."
    group = "build"
    from(animationPlayerBundle)
    into(layout.buildDirectory.dir("generated/animation-player/ksl/animation/web"))
    // Nothing to copy is a normal state, not a failure.
    onlyIf { animationPlayerBundle.isFile }
    doFirst {
        val stale = animationPlayerStaleSources()
        if (stale.isNotEmpty()) {
            logger.warn(
                "The animation web player is older than ${stale.size} of the sources it is built from " +
                    "(${stale.first().name}, ...). Rebuild it with: ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack"
            )
        }
    }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/animation-player"))
tasks.named("processResources") { dependsOn(packageAnimationPlayer) }

/**
 * Checks, before a release, that the player is built and current: it fails when the player is missing,
 * when it is older than any source it is compiled from, or when the copy packaged into KSLApp is not the
 * player as built. A suite cut with a stale player ships an animation export that reads today's traces
 * with yesterday's replay.
 */
tasks.register("checkAnimationPlayerPackaged") {
    group = "verification"
    description = "Fail unless the animation web player is built, current, and packaged into KSLApp."
    dependsOn(packageAnimationPlayer)
    doLast {
        val rebuild = "  Build it with: ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack"
        if (!animationPlayerBundle.isFile) {
            throw GradleException("animation web player NOT built - HTML export would be unavailable.\n$rebuild")
        }
        val stale = animationPlayerStaleSources()
        if (stale.isNotEmpty()) {
            throw GradleException(
                "animation web player is older than these sources it is built from:\n" +
                    stale.joinToString("\n") { "  ${it.relativeTo(rootDir)}" } + "\n$rebuild"
            )
        }
        val packaged = layout.buildDirectory.file("generated/animation-player/ksl/animation/web/ksl-animation.js")
            .get().asFile
        if (!packaged.isFile || !packaged.readBytes().contentEquals(animationPlayerBundle.readBytes())) {
            throw GradleException("the player packaged into KSLApp is not the one built; run :KSLApp:processResources")
        }
        logger.lifecycle("animation web player built, current and packaged (${animationPlayerBundle.length() / 1024} KB)")
    }
}
