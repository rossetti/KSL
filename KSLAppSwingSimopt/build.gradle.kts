plugins {
    kotlin("jvm") version "2.2.0"
    // Enables `@Serializable` data classes in this module (used by
    // `ksl.app.swing.simopt.results.export.RunSummary` so its TOML
    // encoder can delegate to tomlkt instead of hand-rolling
    // quoting / escaping / key-ordering logic).  Both
    // `kotlinx-serialization-core` and `tomlkt` are pulled in
    // transitively via `:KSLCore`'s `api(...)` declarations, so no
    // explicit dependency lines are needed.
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":KSLCore"))
    // KSLAppSwingCommon hosts the shared appearance / theming code
    // (LookAndFeel, ThemeMenu) consumed at startup.
    implementation(project(":KSLAppSwingCommon"))
    // KSLExamples hosts the KSLModelBundle implementations for the
    // bundled example models (MM1Bundle, LKInventoryBundle wrapping
    // GIGcQueue and LKInventoryModel) — these are reference models,
    // not engine internals.
    implementation(project(":KSLExamples"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // tomlkt is used by `results.export.RunSummaryWriter` to encode
    // / decode `summary.toml`.  KSLCore consumes it as
    // `implementation`, not `api`, so consumers don't see it
    // transitively — declare it directly here.
    implementation("net.peanuuutz.tomlkt:tomlkt:0.4.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass.set("ksl.app.swing.simopt.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
