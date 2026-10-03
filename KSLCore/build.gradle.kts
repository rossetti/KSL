
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

plugins {
    `java-library`
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.rossetti"
version = "R1.7.1"

repositories {

    mavenCentral()
}

dependencies {

    api("io.github.oshai:kotlin-logging-jvm:7.0.14")  //TODO consider making implementation
    api("org.slf4j:slf4j-api:2.0.20")  //TODO consider making implementation

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    implementation("ch.qos.logback:logback-classic:1.6.5")
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-core
    implementation("ch.qos.logback:logback-core:1.6.5")

    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1") //TODO fix later, 0.7.0 has code breaking changes
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")

    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-kotlin-jvm
    api("org.jetbrains.lets-plot:lets-plot-kotlin-jvm:4.14.0") //TODO consider making implementation
    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-batik
    implementation("org.jetbrains.lets-plot:lets-plot-batik:4.10.1")
    // https://mvnrepository.com/artifact/org.jetbrains.lets-plot/lets-plot-image-export
    api("org.jetbrains.lets-plot:lets-plot-image-export:4.8.2") //TODO consider making implementation

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/dataframe-core
    api("org.jetbrains.kotlinx:dataframe:1.0.0-Beta2") {//TODO update when version 1.0 stabilizes
        exclude(group = "org.jetbrains.kotlinx", module = "dataframe-excel")
    }

    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.20")

// https://mvnrepository.com/artifact/org.hipparchus/hipparchus-core
    api("org.hipparchus:hipparchus-core:4.0.3")
// https://mvnrepository.com/artifact/org.hipparchus/hipparchus-stat
    api("org.hipparchus:hipparchus-stat:4.0.3")
// Source: https://mvnrepository.com/artifact/org.hipparchus/hipparchus-optim
    implementation("org.hipparchus:hipparchus-optim:4.0.3")

    // https://db.apache.org/derby/releases
    implementation("org.apache.derby:derby:10.17.1.0")
    implementation("org.apache.derby:derbyshared:10.17.1.0")
    implementation("org.apache.derby:derbyclient:10.17.1.0")
    implementation("org.apache.derby:derbytools:10.17.1.0")

    implementation("org.postgresql:postgresql:42.7.13")

    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    implementation("com.zaxxer:HikariCP:7.1.0")

    // fastexcel — streaming xlsx writer/reader used by ExcelUtil
    // https://mvnrepository.com/artifact/org.dhatim/fastexcel
    implementation("org.dhatim:fastexcel:0.20.2")
    // https://mvnrepository.com/artifact/org.dhatim/fastexcel-reader
    implementation("org.dhatim:fastexcel-reader:0.20.2")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-html-jvm
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.12.0")

    // https://mvnrepository.com/artifact/net.peanuuutz.tomlkt/tomlkt
    // api (not implementation) so KSLApp inherits tomlkt transitively; it is also
    // used directly by non-app KSLCore code (station/supplychain TOML serialization).
    api("net.peanuuutz.tomlkt:tomlkt:0.5.0")

    // --- test suite (per-module; Phase 7) ---
    // Core-domain tests use shared example models (KSLTestModels) and JUnit helpers
    // (KSLTestSupport, e.g. @DisabledIfHeadless). No cycle: these are test-only and
    // KSLTestModels depends on KSLCore's MAIN, which builds before KSLCore's tests.
    testImplementation(project(":KSLTestModels"))
    testImplementation(project(":KSLTestSupport"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation(kotlin("test"))
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because the user can then provide their own logging specification
tasks.jar {
    exclude("logback.xml")
    // Read back at run time (for example into an animation trace's header) so a result can say which
    // library produced it. Absent when running from classes rather than the jar.
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    // Forward the MODA baseline recording switch to the test JVM. The canonical MODA fixtures are
    // compared on every run and re-recorded only when a behaviour change has been approved, which
    // requires the switch to reach the test worker rather than stopping at the Gradle JVM.
    System.getProperty("moda.baseline.record")?.let { systemProperty("moda.baseline.record", it) }
}

/**
 * Everything except the long-running validation runs.
 *
 * Measured: the suite is 395 tests in about 22 minutes, and **one test accounts for 19 of them** --
 * `PaintingFlowLineCrossCheckTest`, which reproduces a published shop against a reference tool.
 * With that and the three other `slow`-tagged classes excluded, all 365 remaining tests finish in
 * under three seconds.
 *
 * So this is the task to run while working, and `test` is for a milestone: a merge, a release, or
 * any change to the movement engine or the space layer, where reproducing the validated shops is
 * the point rather than an overhead. Tagging is by class, on the four that are validation runs
 * rather than unit tests.
 */
val fastTest by tasks.registering(Test::class) {
    description = "Runs every test except the long validation runs tagged 'slow'."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { excludeTags("slow") }
    System.getProperty("moda.baseline.record")?.let { systemProperty("moda.baseline.record", it) }
}

kotlin {
    jvmToolchain(21)
    // KSLCore's own classes carry Kotlin 2.2 metadata. That alone does not make the artifact usable from
    // Kotlin 2.2: the published kotlin-stdlib dependency follows the compiler (2.4.20 for R1.7.1), so
    // consumers need Kotlin 2.3+. Pinning `kotlin { coreLibrariesVersion = ... }` would be the lever.
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
    //TODO revisit
//    explicitApiWarning()
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "KSLCore", version.toString())

    pom {
        name = "KSLCore"
        description = "The KSL, an open source kotlin library for simulation."
        inceptionYear = "2023"
        url = "https://github.com/rossetti/KSL"
        licenses {
            license {
                name = "GPL, Version 3.0"
                url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                distribution = "https://www.gnu.org/licenses/gpl-3.0.txt"
            }
        }
        developers {
            developer {
                id = "rossetti"
                name = "Manuel D. Rossetti"
                email = "rossetti@uark.edu"
                url = "https://github.com/rossetti"
            }
        }
        scm {
            url = "https://github.com/rossetti/KSL"
            connection = "scm:git:git://github.com/rossetti/KSL.git"
            developerConnection = "scm:git:ssh://git@github.com/rossetti/KSL.git"
        }
    }
}

dokka {

    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }

    dokkaSourceSets.main {
        documentedVisibilities.set(setOf(VisibilityModifier.Public, VisibilityModifier.Protected))
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/rossetti/KSL/tree/main/KSLCore/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

// --- Publishing the API docs to KSLDocs (release step 14) ---------------------------------------------
//
// https://rossetti.github.io/KSLDocs/ is GitHub Pages serving `docs/` on the main branch of a separate
// repository, rossetti/KSLDocs, and that folder is exactly Dokka's HTML output. `publishKSLDocs` replaces
// it with a fresh build (a sync, so pages that no longer exist are removed too) and commits the result in
// the KSLDocs checkout as "Release <version>". It pushes only with -PpushKSLDocs=true.
//
//   ./gradlew publishKSLDocs                          # build, sync, commit; review and push yourself
//   ./gradlew publishKSLDocs -PpushKSLDocs=true       # ... and push
//   ./gradlew publishKSLDocs -PkslDocsDir=/path/to/KSLDocs   # default: KSLDocs next to this repository

val kslDocsRepoDir: File = (findProperty("kslDocsDir") as String?)?.let { file(it) }
    ?: rootDir.resolveSibling("KSLDocs")
val pushKSLDocs: Boolean = (findProperty("pushKSLDocs") as String?)?.toBoolean() ?: false

/** Runs git in [dir] and returns its trimmed output, failing the build with git's own message on error. */
fun gitIn(dir: File, vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() != 0) throw GradleException("git ${args.joinToString(" ")} failed in $dir:\n$output")
    return output
}

// Checked before Dokka runs, so a KSLDocs checkout that cannot take the commit fails in seconds, not after
// a full documentation build.
val checkKSLDocsRepo by tasks.registering {
    group = "documentation"
    description = "Checks the KSLDocs checkout is the right repository, on main, clean and current."
    doLast {
        val dir = kslDocsRepoDir
        if (!dir.resolve(".git").exists()) throw GradleException(
            "No KSLDocs checkout at $dir. Clone https://github.com/rossetti/KSLDocs there, or pass -PkslDocsDir=<path>."
        )
        val remote = gitIn(dir, "remote", "get-url", "origin")
        if (!remote.contains("rossetti/KSLDocs")) throw GradleException("$dir's origin is $remote, not rossetti/KSLDocs.")
        val branch = gitIn(dir, "branch", "--show-current")
        if (branch != "main") throw GradleException("KSLDocs is on '$branch'; GitHub Pages serves main. Switch to main first.")
        val dirty = gitIn(dir, "status", "--porcelain")
        if (dirty.isNotEmpty()) throw GradleException("KSLDocs has uncommitted changes; commit or discard them first:\n$dirty")
        gitIn(dir, "fetch", "--quiet", "origin")
        val behind = gitIn(dir, "rev-list", "--count", "HEAD..origin/main").toInt()
        if (behind > 0) throw GradleException("KSLDocs is $behind commit(s) behind origin/main; pull first.")
    }
}

tasks.matching { it.name.startsWith("dokkaGenerate") }.configureEach { mustRunAfter(checkKSLDocsRepo) }

tasks.register("publishKSLDocs") {
    group = "documentation"
    description = "Builds KSLCore's Dokka HTML and commits it to the KSLDocs checkout (push with -PpushKSLDocs=true)."
    dependsOn(checkKSLDocsRepo, "dokkaGenerateHtml")
    val dokkaHtml = layout.buildDirectory.dir("dokka/html")
    val releaseVersion = version.toString()
    val kslDir = rootDir
    doLast {
        val source = dokkaHtml.get().asFile
        if (!source.resolve("index.html").isFile) throw GradleException("No Dokka output at $source.")
        val docs = kslDocsRepoDir.resolve("docs")
        docs.deleteRecursively()
        source.copyRecursively(docs)

        gitIn(kslDocsRepoDir, "add", "-A", "docs")
        if (gitIn(kslDocsRepoDir, "status", "--porcelain").isEmpty()) {
            logger.lifecycle("publishKSLDocs: KSLDocs already matches the $releaseVersion docs; nothing to commit.")
            return@doLast
        }
        val kslCommit = gitIn(kslDir, "rev-parse", "--short", "HEAD")
        val kslBranch = gitIn(kslDir, "branch", "--show-current")
        val kslDirty = gitIn(kslDir, "status", "--porcelain", "--", "KSLCore/src/main").isNotEmpty()
        if (kslDirty) logger.warn("publishKSLDocs: KSLCore/src/main has uncommitted changes; the docs include them.")
        gitIn(
            kslDocsRepoDir, "commit", "--quiet",
            "-m", "Release $releaseVersion",
            "-m", "Dokka HTML for KSLCore $releaseVersion, built from KSL $kslCommit ($kslBranch)" +
                (if (kslDirty) " with uncommitted KSLCore source changes." else ".")
        )
        val commit = gitIn(kslDocsRepoDir, "rev-parse", "--short", "HEAD")
        if (pushKSLDocs) {
            gitIn(kslDocsRepoDir, "push", "--quiet", "origin", "main")
            logger.lifecycle("publishKSLDocs: committed $commit (Release $releaseVersion) and pushed to rossetti/KSLDocs.")
        } else {
            logger.lifecycle(
                "publishKSLDocs: committed $commit (Release $releaseVersion) in $kslDocsRepoDir. " +
                    "Review it and push, or re-run with -PpushKSLDocs=true."
            )
        }
    }
}