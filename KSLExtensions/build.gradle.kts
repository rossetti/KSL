import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// An example gradle build file for a project that depends on the JSL

plugins {
    `java-library`
    kotlin("jvm") version "1.7.10"
    //TODO will need to add signing and publishing plug-ins
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {

    mavenCentral()
}

dependencies {

    // depends on KSLCore as an internal project in multi-project build
    // this permits changes to the KSLCore to be immediately reflected in KSLExtensions
     api(project(":KSLCore"))

	//TODO add external dependencies based on JSLExtensions
    

    // https://mvnrepository.com/artifact/io.github.microutils/kotlin-logging-jvm
//    api(group = "io.github.microutils", name = "kotlin-logging-jvm", version = "2.1.21")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
//    api(group = "ch.qos.logback", name = "logback-classic", version = "1.2.10")
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-core
//    api(group = "ch.qos.logback", name = "logback-core", version = "1.2.10")

//TODO maybe not needed due to permit api via KSLCore
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.junit.jupiter:junit-jupiter:5.7.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.7.10")

    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
    
    // include local jar files found in libs folder in the compile
    implementation(fileTree(baseDir = "libs"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because user can then provide their own logging specification
// TODO need reference to why this is good
tasks.jar {
//    manifest {
//        attributes(
//                "Implementation-Title" to project.name,
//                "Implementation-Version" to project.version
//        )
//    }
    exclude("logback.xml")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}