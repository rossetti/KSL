import java.io.File
import org.gradle.jvm.toolchain.JavaLanguageVersion

// An example gradle build file for a project that depends on the KSL

plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
}

// ── Your bundle's identity — EDIT THESE ──────────────────────────────────────
// Describe the bundle you are building. `bundleId` is the only required value;
// make it globally unique using reverse-DNS (e.g. edu.uark.<you>.<project>).
val bundleId          = "edu.example.mywork"
val bundleName        = "My Work"
val bundleVersion     = "1.0.0"
val bundleDescription = ""            // optional; leave "" to omit
// ─────────────────────────────────────────────────────────────────────────────

repositories {

    mavenCentral()
}

dependencies {

    // next line allows use of KSL libraries within the project
    // update the release number when new releases become available
    api("io.github.rossetti:KSLCore:R1.4")
    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

kotlin {
    jvmToolchain(21)
}

// ═════════════════════════════════════════════════════════════════════════════
//  KSL bundle tasks — package this project as a loadable KSL model bundle.
//
//    ./gradlew assembleBundle   →  build/libs/KSLProjectTemplate-bundle.jar
//    ./gradlew deployBundle     →  also copies it into your KSLWork/bundles folder
//
//  Both drive `kslpkg`, the tool the KSL suite install places on disk. If the
//  suite is not installed the tasks fail with instructions; plain `build`/`test`
//  are unaffected — the tool is located only when these tasks actually run.
//  (Reading env vars + the filesystem at task time means this project is not
//  set up for Gradle's configuration cache.)
// ═════════════════════════════════════════════════════════════════════════════

// Machine-specific overrides — read here, but never hardcode a path in this file.
// Use -Pkslpkg.jar=... / -Pksl.home=... / KSL_HOME / -Pksl.workspace=... / KSLWORK.
val kslpkgJarProp = findProperty("kslpkg.jar") as String?
val kslHomeProp   = (findProperty("ksl.home") as String?) ?: System.getenv("KSL_HOME")
val workspaceProp = (findProperty("ksl.workspace") as String?) ?: System.getenv("KSLWORK")

// Candidate locations for the installed kslpkg fat jar, in priority order.
fun kslpkgCandidates(jarProp: String?, kslHome: String?): List<File> {
    val list = mutableListOf<File>()
    jarProp?.takeIf { it.isNotBlank() }?.let { list += File(it) }
    kslHome?.takeIf { it.isNotBlank() }?.let { list += File(it, ".support/Tools/kslpkg/kslpkg.jar") }
    val userHome = File(System.getProperty("user.home"))
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    if (isWindows) {
        val localAppData = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(userHome, "AppData/Local")
        list += File(localAppData, "Programs/KSL/.support/Tools/kslpkg/kslpkg.jar")
    } else {
        list += File(userHome, "Applications/KSL/.support/Tools/kslpkg/kslpkg.jar")
    }
    return list
}

fun resolveKslpkgJar(jarProp: String?, kslHome: String?): File {
    val candidates = kslpkgCandidates(jarProp, kslHome)
    return candidates.firstOrNull { it.isFile } ?: error(
        buildString {
            appendLine("kslpkg not found. Looked in:")
            candidates.forEach { appendLine("  $it") }
            append("Install the KSL suite (see docs/guides/apps/install.md), or point at the tool ")
            append("with -Pkslpkg.jar=/path/to/kslpkg.jar  (or KSL_HOME=... / -Pksl.home=...).")
        }
    )
}

// <workspace>/bundles, resolved the way the KSL apps resolve it:
//   -Pksl.workspace / KSLWORK → ~/.ksl/settings.toml → ~/Documents/KSLWork (→ ~/KSLWork)
fun resolveBundlesDir(workspaceOverride: String?): File {
    val userHome = File(System.getProperty("user.home"))
    val workspace = when {
        !workspaceOverride.isNullOrBlank() -> File(workspaceOverride)
        else -> readSavedWorkspace(userHome) ?: defaultWorkspace(userHome)
    }
    return File(workspace, "bundles")
}

fun defaultWorkspace(userHome: File): File {
    val documents = File(userHome, "Documents")
    return if (documents.isDirectory) File(documents, "KSLWork") else File(userHome, "KSLWork")
}

// Best-effort: the saved workspace from ~/.ksl/settings.toml, only if it still exists.
fun readSavedWorkspace(userHome: File): File? {
    val settings = File(userHome, ".ksl/settings.toml")
    if (!settings.isFile) return null
    val m = Regex("""(?m)^\s*currentDirectory\s*=\s*"(.*)"\s*$""").find(settings.readText()) ?: return null
    return File(m.groupValues[1]).takeIf { it.isDirectory }
}

val bundleOutput = layout.buildDirectory.file("libs/${project.name}-bundle.jar")

// Locate kslpkg lazily. Discovery — and its "not installed" error — runs only
// when this provider is queried, i.e. when assembleBundle actually executes, so
// plain `build`/`test` never trigger it. Resolving through a provider (rather
// than in doFirst) also avoids Task.project access at execution time, which is
// deprecated and removed in Gradle 10.
val kslpkgProvider = providers.provider { resolveKslpkgJar(kslpkgJarProp, kslHomeProp) }

tasks.register<JavaExec>("assembleBundle") {
    group = "ksl bundle"
    description = "Assemble this project's jar into a KSL bundle JAR under build/libs."
    dependsOn("jar")

    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    mainClass.set("ksl.bundle.tools.MainKt")
    classpath = files(kslpkgProvider)

    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(jarFile)
    inputs.property("bundleId", bundleId)
    inputs.property("bundleName", bundleName)
    inputs.property("bundleVersion", bundleVersion)
    inputs.property("bundleDescription", bundleDescription)
    outputs.file(bundleOutput)

    // Command line is built lazily at execution time (no Task.project access).
    argumentProviders.add {
        val a = mutableListOf(
            "assemble", jarFile.get().asFile.absolutePath,
            "--id", bundleId,
            "--name", bundleName,
            "--version", bundleVersion,
            "-o", bundleOutput.get().asFile.absolutePath,
            "--force",
        )
        if (bundleDescription.isNotBlank()) { a += "--description"; a += bundleDescription }
        a
    }

    doFirst {
        require(bundleId.isNotBlank()) {
            "Set `bundleId` at the top of build.gradle.kts before assembling a bundle."
        }
        if (bundleId == "edu.example.mywork") {
            logger.warn("WARNING: bundleId is still the placeholder 'edu.example.mywork' — give your bundle its own reverse-DNS id.")
        }
        logger.lifecycle("kslpkg: ${kslpkgProvider.get()}")
    }

    doLast { logger.lifecycle("Bundle → ${bundleOutput.get().asFile}") }
}

tasks.register("deployBundle") {
    group = "ksl bundle"
    description = "Assemble the bundle and copy it into your KSLWork/bundles folder."
    dependsOn("assembleBundle")

    doLast {
        val src = bundleOutput.get().asFile
        val destDir = resolveBundlesDir(workspaceProp).apply { mkdirs() }
        src.copyTo(File(destDir, src.name), overwrite = true)
        logger.lifecycle("Deployed ${src.name} → $destDir")
        logger.lifecycle("Restart the KSL app (or use Bundles ▸ Load JAR…) to pick it up.")
    }
}
