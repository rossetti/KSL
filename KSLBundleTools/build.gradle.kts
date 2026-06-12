import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
    // badass-runtime: jlink image + bin/kslpkg launcher — a native CLI with a
    // bundled JRE (runs as `kslpkg`, no `java -jar`, no Maven).
    id("org.beryx.runtime") version "2.0.1"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // kslpkg only loads bundle JARs, instantiates the declared model, and
    // serialises its ModelDescriptor.  KSLCore's runtime tree pulls in DB
    // drivers, Apache POI, lets-plot, etc. that the CLI never exercises;
    // excluding them shrinks the fat JAR from ~154 MB to a fraction of that.
    // Exclusions are kept explicit (rather than using shadow's minimize{},
    // which can break reflection paths used by @KSLControl scanning and by
    // kotlinx-serialization's reflective serializer lookup).
    implementation(project(":KSLCore")) {
        // Database drivers and pooling — kslpkg never opens a DB connection
        exclude(group = "org.duckdb")
        exclude(group = "org.xerial")
        exclude(group = "org.postgresql")
        exclude(group = "org.apache.derby")
        exclude(group = "org.mariadb.jdbc")
        exclude(group = "com.zaxxer")

        // Plotting and SVG — no graphical output from kslpkg
        exclude(group = "org.jetbrains.lets-plot")
        exclude(group = "org.apache.xmlgraphics")

        // Format-specific dataframe modules — the underlying formats are
        // already excluded above; dataframe-core / -csv / -json stay
        exclude(group = "org.jetbrains.kotlinx", module = "dataframe-jdbc")
        exclude(group = "org.jetbrains.kotlinx", module = "dataframe-excel")

        // Swing coroutine dispatcher — kslpkg is a CLI, not a Swing app
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-swing")
    }
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass.set("ksl.bundle.tools.MainKt")
    applicationName = "kslpkg"   // launcher script name -> bin/kslpkg
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// NOTE: the shadow fat JAR is intentionally left at its DEFAULT name
// (KSLBundleTools-<version>-all.jar). badass-runtime auto-detects the shadow
// plugin and consumes the shadow jar by its default coordinates to build the
// runtime image; renaming it (archiveBaseName/classifier/version) makes badass
// fail to find it. The in-repo enrichExampleBundle task (KSLExamples) consumes
// shadowJar.archiveFile dynamically, so this name is internal-only.

// ── kslpkg native CLI packaging (badass-runtime) ─────────────────────────────
runtime {
    // jdeps baseline (./gradlew :KSLBundleTools:suggestModules) PLUS the
    // reflective/runtime extras jdeps can't see: zip FS for reading bundle jars,
    // and charsets/locales. `enrich` instantiates arbitrary user models, so this
    // set is verified by running `bin/kslpkg inspect`/`enrich` on a real bundle.
    modules.set(listOf(
        "java.base",
        "java.sql", "java.sql.rowset", "java.naming", "java.xml", "java.desktop",
        "java.logging", "java.compiler", "java.instrument",
        "jdk.unsupported", "jdk.zipfs", "jdk.charsets", "jdk.localedata"
    ))
    // Launcher is bin/kslpkg (from application.applicationName); the image lands
    // in build/image.
}

// badass's runtime task consumes the shadow jar but does not declare the
// dependency, so wire it explicitly.
tasks.named("runtime") { dependsOn("shadowJar") }

// Zip the runtime image (build/image: bin/kslpkg + bundled JRE) into a per-OS,
// per-arch archive for GitHub Releases. CI runs this on each runner, so the
// host's OS/arch ends up in the name; the Intel-mac zip is built locally and
// uploaded, exactly like the apps' Intel dmg.
tasks.register<Zip>("kslpkgZip") {   // badass already owns "runtimeZip"
    group = "ksl bundle"
    description = "Zip the kslpkg native CLI runtime image for distribution."
    dependsOn("runtime")
    val osArch = run {
        val os = OperatingSystem.current()
        val a = System.getProperty("os.arch").lowercase()
        val arch = if (a.contains("aarch64") || a.contains("arm")) "arm64" else "x64"
        val name = when { os.isMacOsX -> "macos"; os.isWindows -> "windows"; else -> "linux" }
        "$name-$arch"
    }
    val ver = ((project.findProperty("releaseVersion") as String?) ?: "1.0.0").substringBefore("-")
    archiveFileName.set("kslpkg-$ver-$osArch.zip")
    destinationDirectory.set(layout.buildDirectory.dir("kslpkg"))
    from(layout.buildDirectory.dir("image")) { into("kslpkg") }
}
