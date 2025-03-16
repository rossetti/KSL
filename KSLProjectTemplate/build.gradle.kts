
// An example gradle build file for a project that depends on the KSL

plugins {
    `java-library`
//    kotlin("jvm") version "1.9.20"

    kotlin("jvm") version "2.1.10"
}

repositories {

    mavenCentral()
}

dependencies {

    // next line allows use of KSL libraries within the project
    // update the release number when new releases become available
    api(group = "io.github.rossetti", name = "KSLCore", version = "R1.2.0")

    testImplementation(kotlin("test"))
//    implementation(kotlin("stdlib-jdk8"))
}

tasks.test {
    useJUnitPlatform()
}

//tasks.withType<KotlinCompile>() {
//    kotlinOptions.jvmTarget = "17"
//}

tasks.jar {
    exclude("logback.xml")
}

kotlin {
    jvmToolchain(17)
}

//val compileKotlin: KotlinCompile by tasks
//compileKotlin.kotlinOptions {
//    jvmTarget = "17"
//}
//
//val compileTestKotlin: KotlinCompile by tasks
//compileTestKotlin.kotlinOptions {
//    jvmTarget = "17"
//}