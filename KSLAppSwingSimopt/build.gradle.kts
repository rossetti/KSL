plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":KSLCore"))
    implementation(project(":KSLApp"))
    // KSLAppSwingCommon hosts the shared appearance / theming code
    // (LookAndFeel, ThemeMenu) consumed at startup.
    implementation(project(":KSLAppSwingCommon"))
    // Test fixtures (example models + dogfood bundles) live in KSLTestModels.
    // TEST-ONLY: the released app ships no baked-in bundles (it discovers them
    // from ~/.ksl/bundles/); the tests load the bundles off the test classpath.
    // Keeping it out of `implementation` keeps the fixtures out of the distribution.
    testImplementation(project(":KSLTestModels"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

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
