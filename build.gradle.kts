import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// An example gradle build file for a project that depends on the JSL

plugins {
    `java-library`
    //kotlin("jvm") version "1.5.31"
    kotlin("jvm") version "1.6.10"
}
group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {

    mavenCentral()
}

dependencies {

//    api(project(":KSLCore"))
//    api(project(":KSLExamples"))
//    api(project(":KSLExtensions"))
    
    api(group = "io.github.rossetti", name = "JSLCore", version = "R1.0.10")
    api(group = "io.github.rossetti", name = "JSLExtensions", version = "R1.0.10")

    // https://mvnrepository.com/artifact/io.github.microutils/kotlin-logging-jvm
    api(group = "io.github.microutils", name = "kotlin-logging-jvm", version = "2.1.21")
    implementation("org.junit.jupiter:junit-jupiter:5.7.0")
    implementation("org.junit.jupiter:junit-jupiter:5.7.0")

    testImplementation(kotlin("test"))
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